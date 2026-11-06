package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.ArchivePaths
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * PanVK, the session's Vulkan driver on Mali (glibc build of panvk-kbase-android), as a managed component next to
 * Blobferry: a verified `panvk-kbase-<V>.zip` goes into the existing driver slot `linux_vulkan_drivers/PanVK_Kbase`, so
 * the driver choice (SessionPrefs: the slot id) stays as it is, and the .so it replaces is kept for one roll back.
 *
 * Package (tools/make-panvk-component.py): one top folder `panvk-kbase-<V>/` with `libvulkan_panfrost.so` (AArch64
 * ELF, glibc), `meta.json` (kind linux-vulkan-icd, backend panvk-kbase), `SOURCE.txt`, `LICENSE` and `SHA256SUMS`
 * listing every other file (both ways, as Blobferry's MD5SUMS). Only the .so is installed.
 *
 * Store `files/panvk/`: `state.json` = the active package record (none = the slot holds a hand import, which is left
 * alone until an install), `previous/` = the .so before (+ its meta.json, icd.json, package.json), `applying` = the
 * journal. The live .so is replaced by one rename, so the slot never lacks a driver; recover() (every stage()) finishes
 * the bookkeeping of a switch a crash interrupted. Switches only while no session runs. [app, Claude Opus 5.5] 2026-11-06
 */
object PanvkPackage {
    private const val TAG = "PanvkPackage"
    const val SLOT_ID = "PanVK_Kbase"
    const val STORE = "panvk"
    const val LIB = LinuxVulkanDriverManager.PANVK_LIB_NAME
    const val BUNDLED_ASSET = "panvk/panvk-kbase.zip"
    const val BUNDLED_META = "panvk/BUNDLE.json"
    const val SOURCE_BUNDLED = "bundled"
    const val SOURCE_HAND = "hand-imported"
    const val MAX_ZIP_BYTES = 160L shl 20
    const val MAX_UNPACKED_BYTES = 256L shl 20
    const val MAX_ENTRIES = 64
    private const val STATE = "state.json"
    private const val JOURNAL = "applying"
    private const val PACKAGE = "package.json"
    val VERSION = Regex("[A-Za-z0-9._+-]{1,48}")
    private val SUM_LINE = Regex("""^([0-9a-f]{64}) [ *](?:\./)?(.+)$""")
    private val COMMIT = Regex("""\b([0-9a-f]{40})\b""")
    private val HEX64 = Regex("(?i)[0-9a-f]{64}")
    private val TOP_FILES = listOf(LIB, "meta.json", "SOURCE.txt", "LICENSE", "SHA256SUMS")

    class Layout(val store: File, val slot: File, val slotName: String = "PanVK Kbase")

    interface Host {
        val sessionRunning: Boolean
        /** The PanVK package the APK bundles (version + zip sha256), null without one. */
        val bundled: Bundled?
    }

    class Bundled(val version: String, val sha256: String)

    class Prepared internal constructor(
        val version: String, val driverVersion: String, val commit: String, val zipSha256: String,
        val soMd5: String, val soSha256: String, val minGlibc: String,
        internal val staging: File, internal val root: File,
    )

    class Status(
        val source: String, val version: String, val driverVersion: String, val commit: String, val soMd5: String,
        val previous: String?, val bundledVersion: String?, val installed: Boolean, val canSwitch: Boolean, val slot: String,
    ) {
        fun json(): JSONObject = JSONObject().put("source", source).put("version", version).put("driverVersion", driverVersion)
            .put("commit", commit).put("soMd5", soMd5).put("hasPrevious", previous != null).put("previous", previous ?: JSONObject.NULL)
            .put("bundledVersion", bundledVersion ?: JSONObject.NULL).put("installed", installed).put("canSwitch", canSwitch)
            .put("slot", slot)
    }

    // ---- context entry points ----

    fun layout(context: Context) = Layout(File(context.filesDir, STORE), LinuxVulkanDriverManager(context).getDriverDir(SLOT_ID))

    fun host(context: Context): Host = object : Host {
        override val sessionRunning get() = BlobferryPackage.sessionBusy()
        override val bundled get() = bundled(context)
    }

    fun bundled(context: Context): Bundled? = runCatching {
        context.assets.open(BUNDLED_META).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()?.let { j -> Bundled(j.optString("version"), j.optString("sha256")).takeIf { it.version.isNotEmpty() && HEX64.matches(it.sha256) } }

    fun prepare(context: Context, input: InputStream, expectedSha256: String? = null) = prepare(layout(context), input, expectedSha256)
    fun install(context: Context, prepared: Prepared, source: String) = install(layout(context), host(context), prepared, source)
    fun rollback(context: Context) = rollback(layout(context), host(context))
    fun status(context: Context) = status(layout(context), host(context))
    fun recover(context: Context) = recover(layout(context))

    /** Mali, at stage(): a phone without any PanVK gets the bundled one. An existing slot is never replaced here. */
    @JvmStatic fun ensureBundled(context: Context): Boolean {
        if (!com.droiddeck.launcher.core.DeviceSupport.mali()) return false
        val layout = layout(context)
        recover(layout)
        if (File(layout.slot, LIB).isFile || LinuxVulkanDriverManager(context).hasPanvk()) return false
        val bundled = bundled(context) ?: return false
        return runCatching {
            val prepared = context.assets.open(BUNDLED_ASSET).use { prepare(layout, it, bundled.sha256) }
            install(layout, object : Host {
                override val sessionRunning = false // a new slot: nothing has loaded it yet
                override val bundled = bundled
            }, prepared, SOURCE_BUNDLED)
            Log.i(TAG, "installed the bundled PanVK ${bundled.version} into $SLOT_ID")
            true
        }.onFailure { Log.e(TAG, "bundled PanVK not installed", it) }.getOrDefault(false)
    }

    // ---- verify ----

    fun prepare(layout: Layout, input: InputStream, expectedSha256: String? = null): Prepared {
        if (expectedSha256 != null && !HEX64.matches(expectedSha256)) throw BlobferryException("BAD_CHECKSUM", "the expected sha256 is not 64 hex digits")
        layout.store.mkdirs()
        val id = System.nanoTime()
        val zip = File(layout.store, "incoming-$id.zip")
        val staging = File(layout.store, "staging-$id")
        var ok = false
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var total = 0L
            input.use { inp ->
                zip.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_BYTES) throw BlobferryException("TOO_LARGE", "the package is larger than ${MAX_ZIP_BYTES shr 20} MB")
                        sha.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                }
            }
            val zipSha = hex(sha.digest())
            if (expectedSha256 != null && !expectedSha256.equals(zipSha, ignoreCase = true)) {
                throw BlobferryException("BAD_CHECKSUM", "sha256 $zipSha does not match the expected $expectedSha256")
            }
            return verify(zip, staging, zipSha).also { ok = true }
        } finally {
            zip.delete()
            if (!ok) staging.deleteRecursively()
        }
    }

    fun discard(prepared: Prepared) {
        prepared.staging.deleteRecursively()
    }

    internal fun verify(zip: File, staging: File, zipSha: String): Prepared {
        val entries = BlobferryPackage.centralDirectory(zip)
        if (entries.size > MAX_ENTRIES) throw BlobferryException("TOO_LARGE", "the package has more than $MAX_ENTRIES entries")
        if (entries.map { it.name }.toSet().size != entries.size) throw BlobferryException("BAD_STRUCTURE", "duplicate zip entries")
        val top = entries.map { it.name.substringBefore('/') }.toSet().singleOrNull()
        if (top == null || !top.startsWith("panvk-kbase-") || entries.any { !it.name.startsWith("$top/") }) {
            throw BlobferryException("NOT_A_PACKAGE", "not a PanVK package (one panvk-kbase-<version>/ folder)")
        }
        val version = top.removePrefix("panvk-kbase-")
        if (!VERSION.matches(version)) throw BlobferryException("BAD_STRUCTURE", "invalid package version '$version'")
        if (!staging.mkdirs()) throw IOException("cannot create $staging")
        val root = File(staging, top)
        val regular = LinkedHashMap<String, File>()
        var unpacked = 0L
        ZipFile(zip).use { z ->
            if (z.size() != entries.size) throw BlobferryException("BAD_STRUCTURE", "the zip directory and its entries disagree")
            for (e in entries) {
                if (e.directory) continue
                if (e.symlink) throw BlobferryException("UNSAFE_PATH", "symbolic link in the package: ${e.name}")
                val rel = e.name.removePrefix("$top/")
                if (rel.isEmpty() || rel.contains('\\') || rel.contains('\u0000') || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                    throw BlobferryException("UNSAFE_PATH", "unsafe path in the package: ${e.name}")
                }
                val target = ArchivePaths.inside(root, rel) ?: throw BlobferryException("UNSAFE_PATH", "path leaves the package: ${e.name}")
                target.parentFile?.mkdirs()
                z.getInputStream(z.getEntry(e.name) ?: throw BlobferryException("BAD_STRUCTURE", "missing entry ${e.name}")).use { inp ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = inp.read(buffer)
                            if (n < 0) break
                            unpacked += n
                            if (unpacked > MAX_UNPACKED_BYTES) throw BlobferryException("TOO_LARGE", "the package unpacks to more than ${MAX_UNPACKED_BYTES shr 20} MB")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                regular[rel] = target
            }
        }
        for (name in TOP_FILES) if (name !in regular) throw BlobferryException("BAD_STRUCTURE", "$name is missing")
        val sums = LinkedHashMap<String, String>()
        for (line in regular.getValue("SHA256SUMS").readLines().filter { it.isNotBlank() }) {
            val m = SUM_LINE.matchEntire(line) ?: throw BlobferryException("BAD_CHECKSUM", "SHA256SUMS line not understood: $line")
            if (sums.put(m.groupValues[2], m.groupValues[1]) != null) throw BlobferryException("BAD_CHECKSUM", "SHA256SUMS lists ${m.groupValues[2]} twice")
        }
        for ((rel, sum) in sums) {
            val file = regular[rel] ?: throw BlobferryException("BAD_CHECKSUM", "SHA256SUMS lists $rel, the package lacks it")
            val actual = sha256(file)
            if (actual != sum) throw BlobferryException("BAD_CHECKSUM", "$rel: sha256 $actual, SHA256SUMS says $sum")
        }
        for (rel in regular.keys) if (rel != "SHA256SUMS" && rel !in sums) throw BlobferryException("BAD_CHECKSUM", "$rel is not in SHA256SUMS")
        val so = regular.getValue(LIB)
        if (!BlobferryPackage.aarch64Elf(so)) throw BlobferryException("BAD_ARCH", "$LIB is not a 64-bit ARM (AArch64) ELF library")
        if (!LinuxVulkanDriverManager.containsAscii(so, "libc.so.6")) throw BlobferryException("BAD_ARCH", "$LIB is not a glibc build")
        val meta = runCatching { JSONObject(regular.getValue("meta.json").readText()) }.getOrNull()
            ?: throw BlobferryException("BAD_STRUCTURE", "meta.json is not valid JSON")
        if (meta.optString("kind") != "linux-vulkan-icd" || meta.optString("backend") != "panvk-kbase") {
            throw BlobferryException("NOT_A_PACKAGE", "meta.json is not a panvk-kbase linux-vulkan-icd")
        }
        val commit = COMMIT.find(regular.getValue("SOURCE.txt").readText())?.groupValues?.get(1).orEmpty()
        return Prepared(
            version, meta.optString("driverVersion").ifEmpty { version }, commit, zipSha, md5(so), sums.getValue(LIB),
            meta.optString("minGlibc"), staging, root,
        )
    }

    // ---- switch ----

    fun install(layout: Layout, host: Host, prepared: Prepared, source: String): Status = synchronized(BlobferryPackage.LOCK) {
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            val so = File(prepared.root, LIB)
            if (!so.isFile) throw BlobferryException("STALE", "the prepared package is gone; import it again")
            if (md5(so) != prepared.soMd5) throw BlobferryException("BAD_CHECKSUM", "the prepared $LIB changed")
            val state = JSONObject().put("version", prepared.version).put("driverVersion", prepared.driverVersion)
                .put("commit", prepared.commit).put("zipSha256", prepared.zipSha256).put("soMd5", prepared.soMd5)
                .put("soSha256", prepared.soSha256).put("minGlibc", prepared.minGlibc).put("source", source)
                .put("installedAt", System.currentTimeMillis())
            switchTo(layout, so, state, null)
            Log.i(TAG, "PanVK ${prepared.version} installed from $source into ${layout.slot}")
            status(layout, host)
        } finally {
            prepared.staging.deleteRecursively()
        }
    }

    /** Swaps the live .so and previous/ (rolling back again goes forward again). */
    fun rollback(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
        val previous = File(layout.store, "previous")
        val so = File(previous, LIB)
        val state = readJson(File(previous, PACKAGE))
        if (!so.isFile || state == null) throw BlobferryException("NO_PREVIOUS", "there is no previous PanVK")
        if (md5(so) != state.optString("soMd5")) throw BlobferryException("BAD_CHECKSUM", "the previous $LIB changed on disk")
        // switchTo stages a copy of previous/'s .so before it rewrites previous/ with the live one
        switchTo(layout, so, state, readJson(File(previous, LinuxVulkanDriverManager.META_NAME)))
        status(layout, host)
    }

    /**
     * The live .so := [so]: journal, keep the live one as previous/, stage the new one beside the live one and rename it
     * over (atomic), then meta.json, icd.json and state.json. [meta] = the slot meta to restore (roll back), else built.
     */
    private fun switchTo(layout: Layout, so: File, state: JSONObject, meta: JSONObject?) {
        val slot = layout.slot
        slot.mkdirs()
        val live = File(slot, LIB)
        val liveMeta = readJson(File(slot, LinuxVulkanDriverManager.META_NAME))
        val newMeta = meta ?: slotMeta(layout, liveMeta, state)
        writeAtomic(File(layout.store, JOURNAL), JSONObject().put("soMd5", state.getString("soMd5")).put("state", state).put("meta", newMeta).toString())
        val staged = File(slot, "$LIB.staged")
        try {
            so.copyTo(staged, overwrite = true)
            if (md5(staged) != state.getString("soMd5")) throw IOException("the staged $LIB does not match")
            if (live.isFile) keepPrevious(layout, live, liveMeta)
            if (!staged.setExecutable(true, false) || !staged.renameTo(live)) throw IOException("cannot install $live")
        } catch (e: Exception) {
            staged.delete()
            File(layout.store, JOURNAL).delete()
            throw if (e is IOException) BlobferryException("APPLY_FAILED", "could not write the driver (${e.message}); the old one stays") else e
        }
        finish(layout, state, newMeta)
    }

    private fun keepPrevious(layout: Layout, live: File, liveMeta: JSONObject?) {
        val previous = File(layout.store, "previous")
        val next = File(layout.store, "previous.new")
        next.deleteRecursively()
        next.mkdirs()
        live.copyTo(File(next, LIB), overwrite = true)
        val record = readJson(File(layout.store, STATE)) ?: JSONObject().put("source", SOURCE_HAND)
            .put("version", liveMeta?.optString("driverVersion").orEmpty().ifEmpty { "hand-imported" })
            .put("driverVersion", liveMeta?.optString("driverVersion").orEmpty())
        record.put("soMd5", md5(File(next, LIB)))
        liveMeta?.let { writeAtomic(File(next, LinuxVulkanDriverManager.META_NAME), it.toString(2)) }
        File(layout.slot, LinuxVulkanDriverManager.ICD_NAME).takeIf { it.isFile }?.copyTo(File(next, LinuxVulkanDriverManager.ICD_NAME), overwrite = true)
        writeAtomic(File(next, PACKAGE), record.toString(2))
        previous.deleteRecursively()
        if (!next.renameTo(previous)) throw IOException("cannot keep the previous PanVK")
    }

    private fun finish(layout: Layout, state: JSONObject, meta: JSONObject) {
        val live = File(layout.slot, LIB)
        val icdFile = File(layout.slot, LinuxVulkanDriverManager.ICD_NAME)
        val api = readJson(icdFile)?.optJSONObject("ICD")?.optString("api_version").orEmpty().ifEmpty { "1.3.0" }
        val icd = JSONObject().put("file_format_version", "1.0.0")
            .put("ICD", JSONObject().put("library_path", live.absolutePath).put("api_version", api))
        writeAtomic(icdFile, icd.toString(2))
        writeAtomic(File(layout.slot, LinuxVulkanDriverManager.META_NAME), meta.toString(2))
        if (state.optString("source") == SOURCE_HAND) File(layout.store, STATE).delete()
        else writeAtomic(File(layout.store, STATE), state.toString(2))
        File(layout.store, JOURNAL).delete()
    }

    private fun slotMeta(layout: Layout, live: JSONObject?, state: JSONObject): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("kind", "linux-vulkan-icd")
        // the name the user sees and picked stays; the slot id (the stored choice) never changes
        .put("name", live?.optString("name").orEmpty().ifEmpty { layout.slotName })
        .put("driverVersion", state.optString("driverVersion")).put("libc", "glibc").put("minGlibc", state.optString("minGlibc"))
        .put("sourceLibraryName", LIB).put("importedAt", System.currentTimeMillis())
        .put("source", state.optString("source")).put("packageVersion", state.optString("version")).put("commit", state.optString("commit"))

    /** At every stage(): finishes the bookkeeping of a switch a crash interrupted. The live .so is always one whole file. */
    fun recover(layout: Layout): Boolean = synchronized(BlobferryPackage.LOCK) {
        File(layout.slot, "$LIB.staged").delete()
        File(layout.store, "previous.new").let { next ->
            if (File(next, PACKAGE).isFile && File(next, LIB).isFile) {
                File(layout.store, "previous").deleteRecursively()
                next.renameTo(File(layout.store, "previous"))
            } else next.deleteRecursively()
        }
        layout.store.listFiles()?.filter { it.name.startsWith("staging-") || it.name.startsWith("incoming-") }
            ?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000L }?.forEach { it.deleteRecursively() }
        val journal = readJson(File(layout.store, JOURNAL)) ?: return@synchronized false
        val live = File(layout.slot, LIB)
        if (live.isFile && md5(live) == journal.optString("soMd5")) {
            Log.w(TAG, "an interrupted PanVK switch: finishing it")
            finish(layout, journal.getJSONObject("state"), journal.getJSONObject("meta"))
        } else {
            Log.w(TAG, "an interrupted PanVK switch: the old driver stayed")
            File(layout.store, JOURNAL).delete()
        }
        true
    }

    // ---- status ----

    fun status(layout: Layout, host: Host): Status {
        val live = File(layout.slot, LIB)
        val state = readJson(File(layout.store, STATE))
        val meta = readJson(File(layout.slot, LinuxVulkanDriverManager.META_NAME))
        val md5 = if (live.isFile) md5(live) else ""
        val managed = state != null && state.optString("soMd5") == md5
        val previous = readJson(File(layout.store, "previous/$PACKAGE"))?.takeIf { File(layout.store, "previous/$LIB").isFile }
        return Status(
            source = when {
                !live.isFile -> "none"
                managed -> state!!.optString("source")
                state != null -> "changed on disk"
                else -> SOURCE_HAND
            },
            version = if (managed) state!!.optString("version") else meta?.optString("driverVersion").orEmpty(),
            driverVersion = meta?.optString("driverVersion").orEmpty(),
            commit = if (managed) state!!.optString("commit") else meta?.optString("commit").orEmpty(),
            soMd5 = md5, previous = previous?.optString("version")?.ifEmpty { "?" }, bundledVersion = host.bundled?.version,
            installed = live.isFile, canSwitch = !host.sessionRunning, slot = layout.slot.name,
        )
    }

    // ---- helpers ----

    private fun readJson(file: File): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".staged")
        try {
            staged.writeText(text)
            if (!staged.renameTo(target)) throw IOException("cannot write $target")
        } finally {
            staged.delete()
        }
    }

    internal fun md5(file: File) = digest(file, "MD5")
    internal fun sha256(file: File) = digest(file, "SHA-256")

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
        return hex(d.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
