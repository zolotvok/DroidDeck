package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import com.droiddeck.launcher.gpu.BlobferryReleases.Release
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Where the app looks for component updates (the user's forks on GitHub). PLACEHOLDERS until the first release is
 * published: nothing is published yet, so "Check for updates" answers "no release yet". One place to change them.
 * [app, Claude Opus 5.5] 2026-11-06
 */
object UpdateSources {
    /** PanVK (glibc, kbase): release assets `panvk-kbase-<V>.zip` + `.sha256` (tools/make-panvk-component.py). */
    @JvmField val PANVK = ComponentReleases.Channel(
        name = "PanVK", repo = "zolotvok/panvk-kbase-android", releasePath = "releases/latest",
        asset = Regex("^panvk-kbase-([A-Za-z0-9._+-]{1,48})\\.zip$"), prefs = "panvk_releases",
    )

    /** Per-game env defaults: a rolling release tagged `env-defaults` on the DroidDeck fork, asset
     *  `env-defaults-<V>.json` + `.sha256` (the manifest tools/linuxfs/overlay/usr/share/droiddeck-mali/env-defaults.json). */
    @JvmField val ENV_DEFAULTS = ComponentReleases.Channel(
        name = "env defaults", repo = "zolotvok/DroidDeck", releasePath = "releases/tags/env-defaults",
        asset = Regex("^env-defaults-([0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\.[0-9]{1,3})\\.json$"), prefs = "env_defaults_releases",
    )
}

/**
 * The Blobferry updater's release path for the other components (PanVK, env defaults): only on the user's tap, no
 * polling, no download without the user's yes. Stricter than Blobferry in two points, since these have no package
 * checksum list of their own that ties them to a commit: a download needs a sha256 (GitHub digest or `.sha256`
 * asset, which must agree), and the asset must come from the channel's own repo (no other URL).
 */
object ComponentReleases {
    class Channel(val name: String, val repo: String, val releasePath: String, val asset: Regex, val prefs: String) {
        val apiUrl get() = "https://api.github.com/repos/$repo/$releasePath"
        val downloadPrefix get() = "https://github.com/$repo/releases/download/"
    }

    private const val KEY_LATEST = "latest"

    fun parse(channel: Channel, json: JSONObject, checkedAt: Long = System.currentTimeMillis()): Release? =
        BlobferryReleases.parseAsset(json, channel.asset, checkedAt)?.takeIf { fromRepo(channel, it) }

    /** The asset and its sidecar must live in the channel's repo: never an arbitrary URL. */
    fun fromRepo(channel: Channel, release: Release): Boolean =
        release.url.startsWith(channel.downloadPrefix) && (release.sha256Url?.startsWith(channel.downloadPrefix) ?: true)

    fun cached(context: Context, channel: Channel): Release? = runCatching {
        BlobferryReleases.fromJson(JSONObject(context.getSharedPreferences(channel.prefs, Context.MODE_PRIVATE).getString(KEY_LATEST, null)!!))
    }.getOrNull()

    /** Asks GitHub once. Null = no release with that asset yet (404 or no asset). */
    fun check(context: Context, channel: Channel): Release? {
        val prefs = context.getSharedPreferences(channel.prefs, Context.MODE_PRIVATE)
        val release = BlobferryReleases.get(channel.apiUrl)?.let { parse(channel, JSONObject(it)) }
        prefs.edit().apply { if (release != null) putString(KEY_LATEST, release.json().toString()) else remove(KEY_LATEST) }.apply()
        return release
    }

    /** The sha256 a download must have; a release that offers none is refused (BAD_CHECKSUM). */
    fun requiredSha256(channel: Channel, release: Release, fetch: (String) -> String?): String {
        if (!fromRepo(channel, release)) throw BlobferryException("UNTRUSTED_SOURCE", "${release.asset} is not from ${channel.repo}")
        return BlobferryReleases.expectedSha256(release, fetch)
            ?: throw BlobferryException("BAD_CHECKSUM", "${release.asset} has no sha256 (GitHub digest or .sha256 asset)")
    }

    /** Downloads the asset after the user's yes; returns the file and the sha256 it matched. */
    fun download(context: Context, channel: Channel, release: Release, progress: (Int) -> Unit): Pair<File, String> {
        val expected = requiredSha256(channel, release) { Downloader.downloadString(it) }
        val target = File(context.cacheDir, release.asset)
        FileUtils.delete(target)
        val ok = Downloader.downloadFile(release.url, target, false, expected) { f -> progress(if (f < 0) -1 else (f * 100).toInt()) }
        if (!ok || !expected.equals(Hashes.sha256(target), ignoreCase = true)) {
            FileUtils.delete(target)
            throw if (ok) BlobferryException("BAD_CHECKSUM", "the download does not match its sha256") else IOException("download failed")
        }
        return target to expected
    }
}
