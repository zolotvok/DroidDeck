package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates" of the Blobferry card: the latest GitHub release of zolotvok/blobferry, only when the user taps,
 * no polling, no download without the user's yes, never an install by itself. Asset contract (bridge/package.sh):
 * `blobferry-<V>.zip` plus `blobferry-<V>.zip.sha256`; the GitHub asset digest and the .sha256 must agree, the
 * download must match them, then BlobferryPackage.prepare runs every package check (MD5SUMS is the second layer).
 * [app, Claude Opus 5.5] 2026-11-05
 */
object BlobferryReleases {
    private const val TAG = "BlobferryReleases"
    const val REPO = "zolotvok/blobferry"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "blobferry_releases"
    private const val KEY_LATEST = "latest"
    private val ASSET = Regex("^blobferry-([A-Za-z0-9._-]{1,40})\\.zip$")

    class Release(
        val tag: String, val version: String, val asset: String, val url: String, val size: Long,
        val digestSha256: String?, val sha256Url: String?, val checkedAt: Long,
    ) {
        fun json(): JSONObject = JSONObject().put("tag", tag).put("version", version).put("asset", asset).put("url", url)
            .put("size", size).put("digest", digestSha256 ?: "").put("sha256Url", sha256Url ?: "").put("checkedAt", checkedAt)
    }

    /** The package asset of a GitHub "release" JSON, null for a draft or a release without one. */
    fun parse(json: JSONObject, checkedAt: Long = System.currentTimeMillis()): Release? = parseAsset(json, ASSET, checkedAt)

    /** The first asset whose name matches [asset] (group 1 = its version) and its `.sha256` sidecar; shared with
     *  ComponentReleases (PanVK, env defaults). */
    internal fun parseAsset(json: JSONObject, asset: Regex, checkedAt: Long): Release? {
        if (json.optBoolean("draft")) return null
        val assets = json.optJSONArray("assets") ?: return null
        val list = (0 until assets.length()).map { assets.getJSONObject(it) }
        val zip = list.firstOrNull { asset.matches(it.optString("name")) } ?: return null
        val name = zip.getString("name")
        val sidecar = list.firstOrNull { it.optString("name") == "$name.sha256" }
        return Release(
            tag = json.optString("tag_name"), version = asset.matchEntire(name)!!.groupValues[1], asset = name,
            url = zip.getString("browser_download_url"), size = zip.optLong("size"),
            digestSha256 = Hashes.githubSha256(zip.optString("digest")), sha256Url = sidecar?.optString("browser_download_url")?.ifEmpty { null },
            checkedAt = checkedAt,
        )
    }

    internal fun fromJson(j: JSONObject) = Release(
        j.getString("tag"), j.getString("version"), j.getString("asset"), j.getString("url"), j.optLong("size"),
        j.optString("digest").ifEmpty { null }, j.optString("sha256Url").ifEmpty { null }, j.optLong("checkedAt"),
    )

    fun cached(context: Context): Release? =
        runCatching { fromJson(JSONObject(prefs(context).getString(KEY_LATEST, null)!!)) }.getOrNull()

    /** Asks GitHub once. Null = no release with a package yet (404 or no asset). */
    fun check(context: Context): Release? {
        val body = get(LATEST_URL) ?: run {
            prefs(context).edit().remove(KEY_LATEST).apply()
            return null
        }
        val release = parse(JSONObject(body))
        prefs(context).edit().apply { if (release != null) putString(KEY_LATEST, release.json().toString()) else remove(KEY_LATEST) }.apply()
        return release
    }

    /** The sha256 the download must have: the GitHub digest and the .sha256 asset, which must agree; null if neither. */
    fun expectedSha256(release: Release, fetch: (String) -> String?): String? {
        val sidecar = release.sha256Url?.let { url ->
            val text = fetch(url) ?: throw IOException("could not fetch ${release.asset}.sha256")
            BlobferryPackage.parseSha256File(text) ?: throw BlobferryException("BAD_CHECKSUM", "${release.asset}.sha256 is not a sha256sum line")
        }
        val digest = release.digestSha256?.lowercase()
        if (sidecar != null && digest != null && sidecar != digest) {
            throw BlobferryException("BAD_CHECKSUM", "GitHub's digest and ${release.asset}.sha256 disagree")
        }
        return sidecar ?: digest
    }

    /** Downloads the package (only after the user said yes); returns the file and the sha256 it was checked against. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): Pair<File, String?> {
        val expected = expectedSha256(release) { Downloader.downloadString(it) }
        val target = File(context.cacheDir, release.asset)
        FileUtils.delete(target)
        val ok = Downloader.downloadFile(release.url, target, false, expected) { f -> progress(if (f < 0) -1 else (f * 100).toInt()) }
        if (!ok) {
            FileUtils.delete(target)
            throw IOException("download failed")
        }
        if (expected != null && !expected.equals(Hashes.sha256(target), ignoreCase = true)) {
            FileUtils.delete(target)
            throw BlobferryException("BAD_CHECKSUM", "the download does not match its sha256")
        }
        return target to expected
    }

    /** GET with the app's GitHub mirrors (as TurnipReleases); null on 404. */
    internal fun get(url: String): String? {
        var last: IOException? = null
        for (source in DownloadSource.candidates(url)) {
            val c = URL(source).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "DroidDeck-app")
                when (val code = c.responseCode) {
                    200 -> return c.inputStream.bufferedReader().use { it.readText() }.also { DownloadSource.succeeded(source) }
                    404 -> return null
                    403, 429 -> last = IOException("GitHub rate limit, try again later")
                    else -> last = IOException("GitHub answered HTTP $code")
                }
            } catch (e: IOException) {
                last = e
                Log.i(TAG, "release check via $source: ${e.message}")
            } finally {
                c.disconnect()
            }
        }
        DownloadSource.failed()
        throw last ?: IOException("no source answered")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
