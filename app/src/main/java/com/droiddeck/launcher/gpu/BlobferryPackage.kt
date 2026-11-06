package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.ArchivePaths
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionFiles
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Blobferry, the Mali system driver (github.com/zolotvok/blobferry): installs a package zip (package.sh output) over the
 * one the APK bundles, keeps the one before it for a roll back, and resets to the bundled one.
 *
 * Store `files/blobferry/`: `active/` and `previous/` are complete verified packages (`package.json` + the unpacked
 * tree); a `previous/BUNDLED` marker means "the bundled one". No `active/` = the bundled package (or a hand-deployed
 * `.dev` build). Every switch writes the journal `applying` first and deletes it last; `recover()` (called by
 * SessionFiles.stage() before anything is staged) makes the live files match `active/` again, so a crash never leaves
 * files of two packages mixed. Live files: the rootfs (`usr/lib/mali_bridge`, libhybris, the implicit layer
 * manifests) and the Mali_Bridge driver slot. A `.user` marker in both tells stage() and ensureBundledBridge() to keep
 * them; `.dev` (Blobferry's build-bridge.sh deploys) stays "hands off, not managed" and an install replaces it.
 * Switches only while no session runs; the loader reads the files at the next session start. [app, Claude Opus 5.5] 2026-11-05
 */
object BlobferryPackage {
    private const val TAG = "BlobferryPackage"

    /** Held by every switch and by SessionFiles.stage(), so a session start never stages half a switch. */
    @JvmField val LOCK = Any()

    const val STORE = "blobferry"
    const val USER_MARKER = ".user"
    const val DEV_MARKER = ".dev"
    const val SOURCE_FILE = "file"
    const val SOURCE_DOWNLOAD = "download"
    const val SOURCE_ADB = "adb"
    const val SOURCE_DEV = "dev"
    const val DEV_NO_COMMIT = "dev build (no commit)"
    const val MAX_ZIP_BYTES = 64L shl 20
    const val MAX_ENTRIES = 512
    const val MAX_UNPACKED_BYTES = 256L shl 20
    private const val META = "package.json"
    private const val BUNDLED_MARK = "BUNDLED"
    private const val JOURNAL = "applying"
    private const val APPLIED = "applied.json"
    private const val AARCH64 = 183

    val VERSION = Regex("[A-Za-z0-9._-]{1,40}")
    private val COMMIT_LINE = Regex("""^Source: https://github\.com/zolotvok/blobferry commit ([0-9a-f]{40})( \(built from a dirty tree\))?$""")
    private val MD5_LINE = Regex("""^([0-9a-f]{32}) [ *]\./(.+)$""")
    private val HEX64 = Regex("(?i)[0-9a-f]{64}")

    const val BRIDGE_DIR = "usr/lib/mali_bridge"
    private const val LAYERS = "usr/share/vulkan/implicit_layer.d"
    const val SHIM = "$BRIDGE_DIR/libvulkan_mali_bridge.so"
    private const val COMPAT = "$BRIDGE_DIR/libVkLayer_droiddeck_mali_compat.so"
    private const val WSI = "$BRIDGE_DIR/libVkLayer_window_system_integration.so"
    const val HYBRIS = "usr/lib/hybris/libhybris-common.so.1"
    private const val LINKER = "usr/lib/hybris/libhybris/linker/q.so"
    const val VERSION_FILE = "$BRIDGE_DIR/VERSION"

    /** What the APK bundles, as (asset under linuxfs/, rootfs target): SessionFiles stages exactly this list. */
    @JvmField val BUNDLED_FILES: List<Pair<String, String>> = listOf(
        "libvulkan_mali_bridge.so", "libVkLayer_droiddeck_mali_compat.so", "libVkLayer_window_system_integration.so",
        "mali_bridge_icd.json", "VkLayer_droiddeck_mali_compat.json", "VkLayer_window_system_integration.json", "VERSION",
    ).map { "$BRIDGE_DIR/$it" to "$BRIDGE_DIR/$it" } +
        listOf("VkLayer_droiddeck_mali_compat.json", "VkLayer_window_system_integration.json").map { "$BRIDGE_DIR/$it" to "$LAYERS/$it" } +
        listOf(HYBRIS, LINKER).map { it to it }

    /** Every package provides these rootfs files (the bundled set without VERSION, which the app writes). */
    val REQUIRED: List<String> = BUNDLED_FILES.map { it.second }.filter { it != VERSION_FILE }
    private val ELF_FILES = listOf(SHIM, COMPAT, WSI, HYBRIS, LINKER)
    private val JSON_FILES = REQUIRED.filter { it.endsWith(".json") }
    /** A package may only write here (no usr/local/bin, no etc/). */
    private val ROOTFS_AREAS = listOf("$BRIDGE_DIR/", "usr/lib/hybris/", "$LAYERS/")
    private val TOP_FILES = listOf("MD5SUMS", "SOURCE.txt", "LICENSE", "NOTICE.md", "Mali_Bridge.zip")
    private val HYBRIS_LINK = Regex("""rootfs/usr/lib/hybris/libhybris-common\.so(\.[0-9]+)*""")

    class BlobferryException(val code: String, message: String) : IOException(message)

    /** Where the files live; the tests use temporary folders. */
    class Layout(val store: File, val rootfs: File, val slot: File, val slotName: String = "Mali system driver (bridge)")

    class Bundled(val version: String, val commit: String = "", val zipMd5: String = "", val files: Map<String, String> = emptyMap())

    /** What the store cannot know by itself: the session, the APK's bundled package, and how to restage it. */
    interface Host {
        val sessionRunning: Boolean
        val bundled: Bundled?
        fun restoreBundled()
    }

    /** A verified package, unpacked into the store's staging folder, waiting for the user's yes (or discard()). */
    class Prepared internal constructor(
        val version: String, val commit: String, val dirty: Boolean, val zipMd5: String, val zipSha256: String,
        internal val staging: File, internal val root: File, internal val files: Map<String, String>, internal val links: Map<String, String>,
    )

    class Status(
        val source: String, val version: String, val commit: String, val dirty: Boolean, val zipMd5: String, val shimMd5: String,
        val previous: String?, val bundledVersion: String?, val devMarker: Boolean, val userMarker: Boolean,
        val changed: List<String>, val canSwitch: Boolean, val files: Map<String, String>,
        /** Hand-deployed (.dev) only: the managed files that differ from the bundled package (null = unknown, no BUNDLE.json). */
        val devFiles: List<String>? = null,
        /** Hand-deployed (.dev) only: the bundled package's commit, which the files outside [devFiles] come from. */
        val baseCommit: String = "",
    ) {
        /**
         * The commit as people read it: the short commit (+ "-dirty"), "dev build (no commit)" for a hand-deployed build whose
         * files are not all the bundled package's, "none" without Blobferry. Never empty, so no one reads "" as "unknown".
         */
        val commitLabel: String get() = when {
            commit.isNotEmpty() -> commit.take(7) + (if (dirty) "-dirty" else "") +
                if (source == SOURCE_DEV) " (dev marker; files = bundled package)" else ""
            source == SOURCE_DEV -> DEV_NO_COMMIT
            source == "none" -> "none"
            else -> "?"
        }

        fun json(): JSONObject = JSONObject().put("source", source).put("version", version).put("commit", commit).put("dirty", dirty)
            .put("commitLabel", commitLabel)
            .put("zipMd5", zipMd5).put("shimMd5", shimMd5).put("hasPrevious", previous != null).put("previous", previous ?: JSONObject.NULL)
            .put("bundledVersion", bundledVersion ?: JSONObject.NULL).put("devMarker", devMarker).put("userMarker", userMarker)
            .put("changed", JSONArray(changed)).put("canSwitch", canSwitch).put("files", JSONObject(files as Map<*, *>))
            .put("devFiles", devFiles?.let { JSONArray(it) } ?: JSONObject.NULL).put("baseCommit", baseCommit)
    }

    // ---- context entry points (UI, agent provider, SessionFiles) ----

    fun layout(context: Context) = Layout(
        File(context.filesDir, STORE), LinuxRuntime.rootDir(context),
        LinuxVulkanDriverManager(context).getDriverDir(LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID),
        context.getString(R.string.drivers_bridge_name),
    )

    fun host(context: Context): Host = object : Host {
        override val sessionRunning get() = sessionBusy()
        override val bundled get() = bundled(context)
        override fun restoreBundled() {
            SessionFiles.stageBridge(context, LinuxRuntime.rootDir(context))
            LinuxVulkanDriverManager(context).ensureBundledBridge()
        }
    }

    /** A session runs, starts or stops (its guest may still hold the bridge files). */
    @JvmStatic fun sessionBusy(): Boolean =
        SessionState.running || SessionState.guestPid > 0 || SessionState.phase == com.droiddeck.launcher.session.SessionPhase.STOPPING

    /** The package the APK bundles (tools/build_local.sh writes VERSION and BUNDLE.json), null without one. */
    fun bundled(context: Context): Bundled? {
        val version = LinuxVulkanDriverManager(context).bundledBridgeVersion().ifEmpty { return null }
        val json = runCatching {
            context.assets.open("linuxfs/$BRIDGE_DIR/BUNDLE.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrNull()
        return Bundled(version, json?.optString("commit").orEmpty(), json?.optString("zipMd5").orEmpty(), json?.optJSONObject("files").toMap())
    }

    fun prepare(context: Context, input: InputStream, expectedSha256: String? = null) = prepare(layout(context), input, expectedSha256)
    fun install(context: Context, prepared: Prepared, source: String) = install(layout(context), host(context), prepared, source)
    fun rollback(context: Context) = rollback(layout(context), host(context))
    fun reset(context: Context) = reset(layout(context), host(context))
    fun status(context: Context) = status(layout(context), host(context))
    fun recover(context: Context) = recover(layout(context), host(context))

    /** stage()'s question: keep the rootfs bridge files as they are (an imported package or a hand-deployed build)? */
    @JvmStatic fun handsOff(rootfs: File): Boolean =
        File(rootfs, "$BRIDGE_DIR/$USER_MARKER").exists() || File(rootfs, "$BRIDGE_DIR/$DEV_MARKER").exists()

    // ---- verify ----

    /** Copies the zip into the store (size cap, hashes on the way), unpacks it with the path guards and checks it all. */
    fun prepare(layout: Layout, input: InputStream, expectedSha256: String? = null): Prepared {
        if (expectedSha256 != null && !HEX64.matches(expectedSha256)) throw BlobferryException("BAD_CHECKSUM", "the expected sha256 is not 64 hex digits")
        layout.store.mkdirs()
        sweep(layout)
        val id = System.nanoTime()
        val zip = File(layout.store, "incoming-$id.zip")
        val staging = File(layout.store, "staging-$id")
        var ok = false
        try {
            val md5 = MessageDigest.getInstance("MD5")
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
                        md5.update(buffer, 0, n); sha.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                }
            }
            val zipMd5 = hex(md5.digest())
            val zipSha = hex(sha.digest())
            if (expectedSha256 != null && !expectedSha256.equals(zipSha, ignoreCase = true)) {
                throw BlobferryException("BAD_CHECKSUM", "sha256 $zipSha does not match the expected $expectedSha256")
            }
            val prepared = verify(zip, staging, zipMd5, zipSha)
            ok = true
            return prepared
        } finally {
            zip.delete()
            if (!ok) staging.deleteRecursively()
        }
    }

    fun discard(prepared: Prepared) {
        prepared.staging.deleteRecursively()
    }

    internal fun verify(zip: File, staging: File, zipMd5: String, zipSha: String): Prepared {
        val entries = centralDirectory(zip)
        val names = HashSet<String>()
        for (e in entries) if (!names.add(e.name)) throw BlobferryException("BAD_STRUCTURE", "duplicate zip entry ${e.name}")
        val tops = entries.map { it.name.substringBefore('/') }.toSet()
        val top = tops.singleOrNull() ?: throw BlobferryException("NOT_A_PACKAGE", "a package has exactly one top folder blobferry-<version>/")
        if (!top.startsWith("blobferry-") || entries.any { !it.name.startsWith("$top/") }) {
            throw BlobferryException("NOT_A_PACKAGE", "not a Blobferry package (no blobferry-<version>/ folder)")
        }
        val version = top.removePrefix("blobferry-")
        if (!VERSION.matches(version)) throw BlobferryException("BAD_STRUCTURE", "invalid package version '$version'")
        if (!staging.mkdirs()) throw IOException("cannot create $staging")
        val root = File(staging, top)
        val regular = LinkedHashMap<String, File>()
        val links = LinkedHashMap<String, String>()
        var unpacked = 0L
        ZipFile(zip).use { z ->
            if (z.size() != entries.size) throw BlobferryException("BAD_STRUCTURE", "the zip directory and its entries disagree")
            for (e in entries) {
                if (e.directory) continue
                val rel = e.name.removePrefix("$top/")
                if (rel.isEmpty() || rel.startsWith("/") || rel.contains('\\') || rel.contains('\u0000') ||
                    rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                    throw BlobferryException("UNSAFE_PATH", "unsafe path in the package: ${e.name}")
                }
                val target = ArchivePaths.inside(root, rel) ?: throw BlobferryException("UNSAFE_PATH", "path leaves the package: ${e.name}")
                val entry = z.getEntry(e.name) ?: throw BlobferryException("BAD_STRUCTURE", "missing entry ${e.name}")
                if (e.symlink) {
                    if (!HYBRIS_LINK.matches(rel)) throw BlobferryException("UNSAFE_PATH", "symbolic link in the package: $rel")
                    val link = z.getInputStream(entry).use { readCapped(it, 256) }.toString(Charsets.UTF_8)
                    if (!Regex("""libhybris-common\.so(\.[0-9]+)*""").matches(link)) throw BlobferryException("UNSAFE_PATH", "link $rel points outside its folder")
                    links[rel] = rel.substringBeforeLast('/') + "/" + link
                    continue
                }
                target.parentFile?.mkdirs()
                z.getInputStream(entry).use { inp ->
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

        // MD5SUMS: package.sh's `find . -type f ! -name MD5SUMS | md5sum`, so every regular file but itself, and nothing else
        val sums = LinkedHashMap<String, String>()
        for (line in regular.getValue("MD5SUMS").readLines().filter { it.isNotBlank() }) {
            val m = MD5_LINE.matchEntire(line) ?: throw BlobferryException("BAD_CHECKSUM", "MD5SUMS line not understood: $line")
            if (sums.put(m.groupValues[2], m.groupValues[1]) != null) throw BlobferryException("BAD_CHECKSUM", "MD5SUMS lists ${m.groupValues[2]} twice")
        }
        for ((rel, md5) in sums) {
            val file = regular[rel] ?: throw BlobferryException("BAD_CHECKSUM", "MD5SUMS lists $rel, the package lacks it")
            val actual = md5(file)
            if (actual != md5) throw BlobferryException("BAD_CHECKSUM", "$rel: md5 $actual, MD5SUMS says $md5")
        }
        for (rel in regular.keys) if (rel != "MD5SUMS" && rel !in sums) throw BlobferryException("BAD_CHECKSUM", "$rel is not in MD5SUMS")
        for ((rel, to) in links) if (to !in sums) throw BlobferryException("UNSAFE_PATH", "link $rel points to a file outside MD5SUMS")

        // SOURCE.txt: "Blobferry <V>" and the commit it was built from
        val source = regular.getValue("SOURCE.txt").readLines()
        if (source.getOrNull(0) != "Blobferry $version") throw BlobferryException("BAD_SOURCE", "SOURCE.txt does not name Blobferry $version")
        val commitLine = COMMIT_LINE.matchEntire(source.getOrNull(1).orEmpty())
            ?: throw BlobferryException("BAD_SOURCE", "SOURCE.txt line 2 is not 'Source: https://github.com/zolotvok/blobferry commit <sha>'")
        val commit = commitLine.groupValues[1]
        val dirty = commitLine.groupValues[2].isNotEmpty() || version.endsWith("-dirty")

        // rootfs: only the bridge's own folders; every file the runtime needs; libhybris-common.so.1 may be a link
        val files = LinkedHashMap<String, String>()
        for ((rel, md5) in sums) if (rel.startsWith("rootfs/")) {
            val target = rel.removePrefix("rootfs/")
            if (ROOTFS_AREAS.none { target.startsWith(it) }) throw BlobferryException("UNSAFE_PATH", "the package writes outside the bridge folders: $target")
            if (target == VERSION_FILE || target.endsWith("/$USER_MARKER") || target.endsWith("/$DEV_MARKER")) {
                throw BlobferryException("UNSAFE_PATH", "the package may not ship $target")
            }
            files[target] = md5
        }
        val resolved = LinkedHashMap<String, String>()
        for ((rel, to) in links) {
            val target = rel.removePrefix("rootfs/")
            if (target in REQUIRED && target !in files) {
                resolved[target] = to.removePrefix("rootfs/")
                files[target] = sums.getValue(to)
            }
        }
        for (rel in REQUIRED) if (rel !in files) throw BlobferryException("BAD_STRUCTURE", "rootfs/$rel is missing")
        for (rel in ELF_FILES) {
            val file = File(root, "rootfs/" + (resolved[rel] ?: rel))
            if (!aarch64Elf(file)) throw BlobferryException("BAD_ARCH", "$rel is not a 64-bit ARM (AArch64) ELF library")
        }
        for (rel in JSON_FILES) {
            runCatching { JSONObject(File(root, "rootfs/$rel").readText()) }
                .onFailure { throw BlobferryException("BAD_STRUCTURE", "$rel is not valid JSON") }
        }
        // Mali_Bridge.zip, the Runtime-driver-page zip, must hold the same shim
        val inner = innerShimMd5(regular.getValue("Mali_Bridge.zip"))
            ?: throw BlobferryException("BAD_STRUCTURE", "Mali_Bridge.zip has no libvulkan_mali_bridge.so")
        if (inner != files.getValue(SHIM)) throw BlobferryException("BAD_CHECKSUM", "Mali_Bridge.zip's shim differs from rootfs/$SHIM")
        return Prepared(version, commit, dirty, zipMd5, zipSha, staging, root, files, resolved)
    }

    internal class Entry(val name: String, val symlink: Boolean, val directory: Boolean)

    /** The zip's central directory, read by hand: java.util.zip does not say which entries are symbolic links. */
    internal fun centralDirectory(zip: File): List<Entry> = RandomAccessFile(zip, "r").use { f ->
        val length = f.length()
        val tailLength = minOf(length, 22L + 0xffff).toInt()
        if (tailLength < 22) throw BlobferryException("NOT_A_PACKAGE", "not a zip file")
        val tail = ByteArray(tailLength)
        f.seek(length - tailLength)
        f.readFully(tail)
        var eocd = -1
        for (i in tailLength - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
        if (eocd < 0) throw BlobferryException("NOT_A_PACKAGE", "not a zip file")
        val count = u16(tail, eocd + 10)
        val size = u32(tail, eocd + 12)
        val offset = u32(tail, eocd + 16)
        if (count == 0xffff || offset == 0xffffffffL) throw BlobferryException("BAD_STRUCTURE", "zip64 packages are not supported")
        if (count > MAX_ENTRIES) throw BlobferryException("TOO_LARGE", "the package has more than $MAX_ENTRIES entries")
        if (offset + size > length) throw BlobferryException("BAD_STRUCTURE", "broken zip directory")
        val cd = ByteArray(size.toInt())
        f.seek(offset)
        f.readFully(cd)
        val out = ArrayList<Entry>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.size || u32(cd, p) != 0x02014b50L) throw BlobferryException("BAD_STRUCTURE", "broken zip directory")
            val madeBy = u16(cd, p + 4) ushr 8
            val nameLength = u16(cd, p + 28)
            val next = p + 46 + nameLength + u16(cd, p + 30) + u16(cd, p + 32)
            if (next > cd.size) throw BlobferryException("BAD_STRUCTURE", "broken zip directory")
            val name = String(cd, p + 46, nameLength, Charsets.UTF_8)
            val mode = if (madeBy == 3) (u32(cd, p + 38) ushr 16).toInt() and 0xf000 else 0
            out.add(Entry(name, mode == 0xa000, name.endsWith("/") || mode == 0x4000))
            p = next
        }
        out
    }

    private fun innerShimMd5(zip: File): String? {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: return null
                if (e.isDirectory || e.name.substringAfterLast('/') != "libvulkan_mali_bridge.so") continue
                val md5 = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = z.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ZIP_BYTES) throw BlobferryException("TOO_LARGE", "Mali_Bridge.zip's shim is too large")
                    md5.update(buffer, 0, n)
                }
                return hex(md5.digest())
            }
        }
    }

    internal fun aarch64Elf(file: File): Boolean {
        if (!file.isFile) return false
        val h = ByteArray(20)
        val n = file.inputStream().use { it.read(h) }
        return n == 20 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
            h[4] == 2.toByte() && h[5] == 1.toByte() && u16(h, 18) == AARCH64
    }

    // ---- switch ----

    /** Makes the prepared package the active one: the old active becomes previous/, live files follow at once. */
    fun install(layout: Layout, host: Host, prepared: Prepared, source: String): Status = synchronized(LOCK) {
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            if (!prepared.root.isDirectory) throw BlobferryException("STALE", "the prepared package is gone; import it again")
            val store = layout.store
            val active = File(store, "active")
            val previous = File(store, "previous")
            val next = File(store, "new")
            next.deleteRecursively()
            if (!prepared.root.renameTo(next)) throw IOException("cannot move the package into the store")
            for ((target, from) in prepared.links) File(next, "rootfs/$from").copyTo(File(next, "rootfs/$target"), overwrite = true)
            val meta = JSONObject().put("version", prepared.version).put("commit", prepared.commit).put("dirty", prepared.dirty)
                .put("zipMd5", prepared.zipMd5).put("zipSha256", prepared.zipSha256).put("source", source)
                .put("installedAt", System.currentTimeMillis()).put("files", JSONObject(prepared.files as Map<*, *>))
            writeAtomic(File(next, META), meta.toString(2))
            journal(layout)
            previous.deleteRecursively()
            if (packageMeta(active) != null) {
                if (!active.renameTo(previous)) throw IOException("cannot keep the active package")
            } else {
                active.deleteRecursively()
                host.bundled?.let { b -> previous.mkdirs(); writeAtomic(File(previous, BUNDLED_MARK), b.version) }
            }
            if (!next.renameTo(active)) throw IOException("cannot activate the package")
            try {
                apply(layout, active)
            } catch (e: Exception) {
                Log.e(TAG, "apply of Blobferry ${prepared.version} failed, going back", e)
                val failed = File(store, "failed")
                failed.deleteRecursively()
                active.renameTo(failed)
                if (packageMeta(previous) != null) previous.renameTo(active) else previous.deleteRecursively()
                runCatching { sync(layout, host) }.onFailure { Log.e(TAG, "going back failed too", it) }
                failed.deleteRecursively()
                throw BlobferryException("APPLY_FAILED", "could not write the files (${e.message}); the package before it is back")
            }
            endJournal(layout)
            Log.i(TAG, "Blobferry ${prepared.version} (${prepared.commit.take(7)}) installed from $source")
            status(layout, host)
        } finally {
            prepared.staging.deleteRecursively()
        }
    }

    /** Swaps active and previous (previous can be the bundled package; rolling back again goes forward again). */
    fun rollback(layout: Layout, host: Host): Status = synchronized(LOCK) {
        if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
        val active = File(layout.store, "active")
        val previous = File(layout.store, "previous")
        val previousBundled = File(previous, BUNDLED_MARK).isFile
        if (packageMeta(previous) == null && !previousBundled) throw BlobferryException("NO_PREVIOUS", "there is no previous package")
        if (previousBundled && host.bundled == null) throw BlobferryException("NO_BUNDLED", "this app has no bundled Blobferry")
        journal(layout)
        val swap = File(layout.store, "swap")
        swap.deleteRecursively()
        if (packageMeta(active) != null) {
            if (!active.renameTo(swap)) throw IOException("cannot move the active package")
        } else {
            active.deleteRecursively()
            host.bundled?.let { b -> swap.mkdirs(); writeAtomic(File(swap, BUNDLED_MARK), b.version) }
        }
        if (!previous.renameTo(active)) throw IOException("cannot activate the previous package")
        if (swap.exists() && !swap.renameTo(previous)) swap.deleteRecursively()
        if (File(active, BUNDLED_MARK).isFile) active.deleteRecursively()
        sync(layout, host)
        endJournal(layout)
        status(layout, host)
    }

    /** Back to what the APK bundles: drops active/, previous/ and both markers (.user and .dev). */
    fun reset(layout: Layout, host: Host): Status = synchronized(LOCK) {
        if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
        if (host.bundled == null) throw BlobferryException("NO_BUNDLED", "this app has no bundled Blobferry")
        journal(layout)
        File(layout.store, "active").deleteRecursively()
        File(layout.store, "previous").deleteRecursively()
        sync(layout, host)
        endJournal(layout)
        status(layout, host)
    }

    /** At every stage(): finishes a switch a crash interrupted (the journal), so the live files match active/ again. */
    fun recover(layout: Layout, host: Host): Boolean = synchronized(LOCK) {
        if (!File(layout.store, JOURNAL).exists()) return@synchronized false
        Log.w(TAG, "an interrupted Blobferry switch: making the live files match the store again")
        File(layout.store, "new").deleteRecursively()
        File(layout.store, "swap").deleteRecursively()
        File(layout.store, "failed").deleteRecursively()
        try {
            sync(layout, host)
        } catch (e: Exception) {
            Log.e(TAG, "re-applying the active package failed, back to bundled", e)
            File(layout.store, "active").deleteRecursively()
            sync(layout, host)
        }
        endJournal(layout)
        true
    }

    /** Live files := active/ (a package) or the bundled package (no active/). */
    private fun sync(layout: Layout, host: Host) {
        val active = File(layout.store, "active")
        if (packageMeta(active) != null) apply(layout, active) else {
            active.deleteRecursively()
            restoreBundledLive(layout, host)
        }
    }

    internal fun apply(layout: Layout, pkg: File) {
        val meta = packageMeta(pkg) ?: throw IOException("no $META in $pkg")
        val version = meta.getString("version")
        val files = meta.getJSONObject("files").toMap()
        for (rel in REQUIRED) if (rel !in files) throw IOException("$META lacks $rel")
        File(layout.rootfs, "$BRIDGE_DIR/$DEV_MARKER").delete()
        File(layout.slot, DEV_MARKER).delete()
        mark(File(layout.rootfs, "$BRIDGE_DIR/$USER_MARKER"))
        mark(File(layout.slot, USER_MARKER))
        for ((rel, md5) in files) {
            val src = File(pkg, "rootfs/$rel")
            if (md5(src) != md5) throw IOException("$rel in the store changed (md5)")
            copyAtomic(src, File(layout.rootfs, rel))
        }
        for (rel in applied(layout) - files.keys - BUNDLED_FILES.map { it.second }.toSet()) File(layout.rootfs, rel).delete()
        writeAtomic(File(layout.rootfs, VERSION_FILE), version + "\n")
        // the Runtime driver page's slot: the same shim, its ICD and meta
        val so = File(layout.slot, LinuxVulkanDriverManager.BRIDGE_LIB_NAME)
        copyAtomic(File(pkg, "rootfs/$SHIM"), so)
        val icd = JSONObject().put("file_format_version", "1.0.0")
            .put("ICD", JSONObject().put("library_path", so.absolutePath).put("api_version", "1.3.0"))
        writeAtomic(File(layout.slot, LinuxVulkanDriverManager.ICD_NAME), icd.toString(2))
        val slotMeta = JSONObject().put("schemaVersion", 1).put("kind", "linux-vulkan-icd").put("name", layout.slotName)
            .put("driverVersion", "Blobferry $version").put("libc", "glibc").put("minGlibc", "2.36")
            .put("sourceLibraryName", LinuxVulkanDriverManager.BRIDGE_LIB_NAME).put("bundled", false)
            .put("source", meta.optString("source")).put("commit", meta.optString("commit"))
        writeAtomic(File(layout.slot, LinuxVulkanDriverManager.META_NAME), slotMeta.toString(2))
        writeAtomic(File(layout.store, APPLIED), JSONArray(files.keys.toList()).toString())
    }

    private fun restoreBundledLive(layout: Layout, host: Host) {
        File(layout.rootfs, "$BRIDGE_DIR/$USER_MARKER").delete()
        File(layout.rootfs, "$BRIDGE_DIR/$DEV_MARKER").delete()
        File(layout.slot, USER_MARKER).delete()
        File(layout.slot, DEV_MARKER).delete()
        val bundledTargets = BUNDLED_FILES.map { it.second }.toSet()
        for (rel in applied(layout) - bundledTargets) File(layout.rootfs, rel).delete()
        File(layout.store, APPLIED).delete()
        if (host.bundled == null) return
        // meta.json gone = the slot's version no longer matches, so ensureBundledBridge() rewrites it
        File(layout.slot, LinuxVulkanDriverManager.META_NAME).delete()
        host.restoreBundled()
    }

    // ---- status ----

    fun status(layout: Layout, host: Host): Status {
        val bundled = host.bundled
        val active = File(layout.store, "active")
        val meta = packageMeta(active)
        val previous = File(layout.store, "previous").let { p ->
            packageMeta(p)?.optString("version") ?: if (File(p, BUNDLED_MARK).isFile) "bundled" else null
        }
        val dev = File(layout.rootfs, "$BRIDGE_DIR/$DEV_MARKER").exists() || File(layout.slot, DEV_MARKER).exists()
        val user = File(layout.rootfs, "$BRIDGE_DIR/$USER_MARKER").exists()
        val liveVersion = runCatching { File(layout.rootfs, VERSION_FILE).readText().trim() }.getOrDefault("")
        val shim = File(layout.rootfs, SHIM).takeIf { it.isFile }?.let { md5(it) }.orEmpty()
        val (source, expected) = when {
            meta != null -> meta.optString("source", SOURCE_FILE) to meta.optJSONObject("files").toMap()
            dev -> SOURCE_DEV to emptyMap()
            bundled != null -> "bundled" to bundled.files
            else -> "none" to emptyMap()
        }
        // A hand-deployed tree has no package record: compare it with the bundled package file by file. No file differs = it
        // is the bundled build (its commit applies); otherwise there is no commit for it, and devFiles names what differs.
        val devFiles = if (source == SOURCE_DEV && bundled != null && bundled.files.isNotEmpty()) {
            bundled.files.filter { (rel, md5) -> File(layout.rootfs, rel).let { !it.isFile || md5(it) != md5 } }.keys.sorted() +
                listOfNotNull(bundled.files[SHIM]?.let { md5 ->
                    File(layout.slot, LinuxVulkanDriverManager.BRIDGE_LIB_NAME).takeIf { it.isFile && md5(it) != md5 }?.let { "slot/${it.name}" }
                })
        } else null
        val devCommit = if (devFiles != null && devFiles.isEmpty()) bundled!!.commit else ""
        val changed = expected.filter { (rel, md5) -> File(layout.rootfs, rel).let { !it.isFile || md5(it) != md5 } }.keys.toList() +
            // the Runtime driver page's slot runs the games' shim: it must be the package's too
            listOfNotNull(expected[SHIM]?.takeIf { meta != null }?.let { md5 ->
                File(layout.slot, LinuxVulkanDriverManager.BRIDGE_LIB_NAME).takeIf { !it.isFile || md5(it) != md5 }?.let { "slot/${it.name}" }
            })
        return Status(
            source = source,
            version = meta?.optString("version") ?: if (source == "bundled") bundled!!.version else liveVersion,
            commit = meta?.optString("commit") ?: if (source == "bundled") bundled!!.commit else devCommit,
            dirty = meta?.optBoolean("dirty") ?: false,
            zipMd5 = meta?.optString("zipMd5") ?: if (source == "bundled") bundled!!.zipMd5 else "",
            shimMd5 = shim, previous = previous, bundledVersion = bundled?.version, devMarker = dev, userMarker = user,
            changed = changed, canSwitch = !host.sessionRunning,
            // a hand-deployed tree shows what is on disk (the details dialog, the report's known hashes)
            files = if (source == SOURCE_DEV) liveFiles(layout) else expected,
            devFiles = devFiles, baseCommit = if (source == SOURCE_DEV) bundled?.commit.orEmpty() else "",
        )
    }

    /** md5 of every managed rootfs file that exists (the bundled list's targets plus what the active package applied). */
    fun liveFiles(layout: Layout): Map<String, String> {
        val rels = (BUNDLED_FILES.map { it.second } + applied(layout)).toSortedSet()
        return rels.mapNotNull { rel -> File(layout.rootfs, rel).takeIf { it.isFile }?.let { rel to md5(it) } }.toMap()
    }

    // ---- helpers ----

    private fun journal(layout: Layout) {
        layout.store.mkdirs()
        writeAtomic(File(layout.store, JOURNAL), "sync\n")
    }

    private fun endJournal(layout: Layout) {
        File(layout.store, JOURNAL).delete()
    }

    private fun applied(layout: Layout): Set<String> = runCatching {
        val a = JSONArray(File(layout.store, APPLIED).readText())
        (0 until a.length()).map { a.getString(it) }.filter { rel -> ROOTFS_AREAS.any { rel.startsWith(it) } && !rel.contains("..") }.toSet()
    }.getOrDefault(emptySet())

    private fun packageMeta(dir: File): JSONObject? = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull()
        ?.takeIf { it.optString("version").isNotEmpty() && it.optJSONObject("files") != null }

    /** Removes what an interrupted import left (older than an hour, so a concurrent import keeps its files). */
    private fun sweep(layout: Layout) {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        layout.store.listFiles()?.filter { (it.name.startsWith("incoming-") || it.name.startsWith("staging-")) && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private fun mark(file: File) {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("Blobferry package installed by the app; Reset to bundled removes it\n")
    }

    private fun copyAtomic(src: File, target: File) {
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".staged")
        try {
            src.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            if (!staged.setExecutable(true, false) || !staged.renameTo(target)) throw IOException("cannot install $target")
        } finally {
            staged.delete()
        }
    }

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

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(cap + 1)
        while (out.size() <= cap) {
            val n = input.read(buffer, 0, cap + 1 - out.size())
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        if (out.size() > cap) throw BlobferryException("UNSAFE_PATH", "symbolic link target too long")
        return out.toByteArray()
    }

    internal fun md5(file: File): String {
        val md5 = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md5.update(buffer, 0, n)
            }
        }
        return hex(md5.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
    private fun JSONObject?.toMap(): Map<String, String> =
        this?.keys()?.asSequence()?.associateWith { optString(it) }.orEmpty()

    /** First token of a `sha256sum` line (a release's blobferry-<V>.zip.sha256), null if it is not one. */
    fun parseSha256File(text: String): String? = text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { HEX64.matches(it) }?.lowercase()
}
