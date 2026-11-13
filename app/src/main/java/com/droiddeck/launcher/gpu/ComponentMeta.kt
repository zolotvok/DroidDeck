package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * The common `meta.json` of an updater package (docs/agents/release-perf-1111.md § 3.1): component, version, date,
 * changelog, source {repo, commit}, files {path: {sha256, md5}}, minApp. Optional for now: a package without it installs
 * as before; one that carries it must match it exactly (every listed file by sha256, nothing unlisted, minApp <= the
 * app's versionCode). [app, Claude Opus 5.5] 2026-11-13
 */
class ComponentMeta private constructor(
    val component: String, val version: String, val date: String, val changelog: String,
    val repo: String, val commit: String, val files: Map<String, FileSum>, val minApp: Long,
) {
    class FileSum(val sha256: String, val md5: String?)

    /** What goes into the package record (state.json / package.json) and the components list. */
    fun record(): JSONObject = JSONObject().put("component", component).put("version", version).put("date", date)
        .put("changelog", changelog).put("repo", repo).put("commit", commit).put("minApp", minApp)

    /**
     * The unpacked package at [root] against this meta: every listed file is a regular file with that sha256 (and md5
     * when given); every regular file is listed except meta.json and the top-level files [exempt] allows (the package's
     * own checksum file, LICENSE, NOTICE, SOURCE.txt). A link or other non-regular entry is refused.
     */
    fun verifyTree(root: File, appVersionCode: Long, exempt: (String) -> Boolean = ::defaultExempt) {
        if (minApp > appVersionCode) throw BlobferryException("APP_TOO_OLD", "the package needs app version $minApp, this app is $appVersionCode")
        val canonical = root.canonicalFile
        for ((rel, sum) in files) {
            val file = File(root, rel)
            if (!file.canonicalPath.startsWith(canonical.path + File.separator)) throw BlobferryException("UNSAFE_PATH", "meta.json path leaves the package: $rel")
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw BlobferryException("BAD_META", "meta.json lists $rel, the package lacks it")
            val sha = digest(file, "SHA-256")
            if (sha != sum.sha256) throw BlobferryException("BAD_CHECKSUM", "$rel: sha256 $sha, meta.json says ${sum.sha256}")
            if (sum.md5 != null && digest(file, "MD5") != sum.md5) throw BlobferryException("BAD_CHECKSUM", "$rel: md5 differs from meta.json")
        }
        root.walkTopDown().forEach { f ->
            if (f == root) return@forEach
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val path = f.toPath()
            if (Files.isSymbolicLink(path)) throw BlobferryException("UNSAFE_PATH", "symbolic link in the package: $rel")
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw BlobferryException("UNSAFE_PATH", "not a regular file: $rel")
            if (rel != NAME && !exempt(rel) && rel !in files) throw BlobferryException("BAD_META", "$rel is not listed in meta.json")
        }
    }

    companion object {
        const val NAME = "meta.json"
        val COMPONENTS = setOf("blobferry", "panvk", "fex-arm64ec", "guest-tools", "env-defaults")
        private val VERSION = Regex("[A-Za-z0-9._+-]{1,48}")
        private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        private val COMMIT = Regex("[0-9a-f]{40}")
        private val REPO = Regex("""https://github\.com/[A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.-]{1,100}""")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val HEX32 = Regex("[0-9a-f]{32}")
        private const val MAX_FILES = 512

        /** Top-level license / notice / source / checksum files may stay unlisted (the existing package rules sum them). */
        fun defaultExempt(rel: String): Boolean = '/' !in rel &&
            (rel in setOf("MD5SUMS", "SHA256SUMS", "SOURCE.txt") || rel.startsWith("LICENSE") || rel.startsWith("NOTICE") || rel.startsWith("COPYING"))

        /** A meta.json in the § 3.1 shape (has "component"), as opposed to an older package-specific meta.json. */
        fun isComponentMeta(json: JSONObject?): Boolean = json?.has("component") == true

        fun parse(text: String): ComponentMeta = parse(
            runCatching { JSONObject(text) }.getOrNull() ?: throw BlobferryException("BAD_META", "meta.json is not valid JSON"),
        )

        fun parse(j: JSONObject): ComponentMeta {
            fun bad(why: String): Nothing = throw BlobferryException("BAD_META", "meta.json: $why")
            val component = j.optString("component")
            if (component !in COMPONENTS) bad("unknown component '$component'")
            val version = j.optString("version")
            if (!VERSION.matches(version)) bad("invalid version '$version'")
            val date = j.optString("date")
            if (!DATE.matches(date)) bad("date is not YYYY-MM-DD")
            val changelog = j.optString("changelog")
            if (changelog.length > 500 || changelog.any { it < ' ' }) bad("changelog must be one line of at most 500 characters")
            val source = j.optJSONObject("source") ?: bad("no source")
            val repo = source.optString("repo")
            if (!REPO.matches(repo)) bad("source.repo is not https://github.com/<owner>/<repo>")
            val commit = source.optString("commit")
            if (!COMMIT.matches(commit)) bad("source.commit is not 40 lowercase hex digits")
            val minApp = if (j.has("minApp")) j.opt("minApp").let { (it as? Number)?.toLong() ?: bad("minApp is not a number") } else 0L
            if (minApp < 0) bad("minApp is negative")
            val list = j.optJSONObject("files") ?: bad("no files")
            if (list.length() == 0 || list.length() > MAX_FILES) bad("files must list 1..$MAX_FILES files")
            val files = LinkedHashMap<String, FileSum>()
            for (rel in list.keys()) {
                if (!safePath(rel)) throw BlobferryException("UNSAFE_PATH", "meta.json: unsafe path '$rel'")
                if (rel == NAME) bad("meta.json cannot list itself")
                val e = list.optJSONObject(rel) ?: bad("$rel has no {sha256, md5}")
                val sha = e.optString("sha256")
                if (!HEX64.matches(sha)) bad("$rel: sha256 is not 64 lowercase hex digits")
                val md5 = e.optString("md5").ifEmpty { null }
                if (md5 != null && !HEX32.matches(md5)) bad("$rel: md5 is not 32 lowercase hex digits")
                files[rel] = FileSum(sha, md5)
            }
            return ComponentMeta(component, version, date, changelog, repo, commit, files, minApp)
        }

        /** Relative, '/'-separated, no empty / . / .. segment, no backslash, NUL or control character. */
        fun safePath(rel: String): Boolean = rel.isNotEmpty() && rel.length <= 512 && !rel.startsWith("/") &&
            rel.none { it == '\\' || it < ' ' } && rel.split('/').none { it.isEmpty() || it == "." || it == ".." }

        private fun digest(file: File, algorithm: String): String {
            val d = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    d.update(buffer, 0, n)
                }
            }
            return d.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
