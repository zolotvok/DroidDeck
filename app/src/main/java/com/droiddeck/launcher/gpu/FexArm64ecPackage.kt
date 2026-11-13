package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The patched FEX arm64ec DLLs as a table package (release-perf-1111.md § 3.3): `fex-arm64ec-<V>/` with `meta.json`
 * (§ 3.1, lists every other file), `table.json` = rows {stock_md5, fex_version, proton_build?, patched, patched_md5, patch,
 * patch_md5, fex_commit?, supersedes?}, one ARM64EC DLL per row under `dll/`, the patches under `patches/`, LICENSE and
 * NOTICE (tools/make-fex-arm64ec-package.py). droiddeck-mali-defaults' fex_patch() picks the row whose stock_md5 is the
 * Proton's current DLL, or whose `supersedes` list (older patched builds of that stock DLL) holds it; every md5 of a table
 * has exactly one role in one row.
 *
 * The APK bundles one package at linuxfs/[BUNDLED_DIR]; an installed one lives in the store `files/fex-arm64ec/`
 * (ComponentStore: active/, previous/, journal) and is copied to the rootfs at [ACTIVE_DIR], which the guest reads first.
 * stage() calls [restage]: an interrupted switch is finished, [ACTIVE_DIR] follows active/ (removed without one), the
 * guest's fex events go into the install log. Switches only while no session runs.
 *
 * APK-upgrade rule (as guest-tools, ComponentStore.upgradeAtStart): the guest reads only one table, so an installed table
 * older than the bundled one would hide the newer APK's rows. At the first stage() of such an APK the installed table goes to
 * previous/ (logged `upgrade-at-start`) and the bundled table is used; install and roll back refuse a table older than the
 * bundled one (OLDER_THAN_BUNDLED). Equal versions: the installed table wins. [app, Claude Opus 5.5] 2026-11-13
 */
object FexArm64ecPackage {
    private const val TAG = "FexArm64ecPackage"
    const val COMPONENT = "fex-arm64ec"
    const val STORE = "fex-arm64ec"
    const val PREFIX = "fex-arm64ec-"
    const val BUNDLED_DIR = "usr/local/lib/droiddeck-fex-arm64ec"
    const val ACTIVE_DIR = "usr/local/lib/droiddeck-fex-arm64ec-active"
    const val TABLE = "table.json"
    /** The guest's own event log (droiddeck-mali-defaults), moved into the install log at stage() and `components log`. */
    const val GUEST_LOG = "root/.local/share/droiddeck-components/install.log"
    val GUEST_OPS = setOf("fex-unmatched", "fex-apply", "fex-stock")
    const val SOURCE_BUNDLED = "bundled"
    const val MAX_ZIP_BYTES = 96L shl 20
    const val MAX_ENTRIES = 256
    const val MAX_UNPACKED_BYTES = 512L shl 20
    const val MAX_ROWS = 64
    private const val MAX_DLL_BYTES = 64L shl 20
    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("(?i)[0-9a-f]{64}")
    val FEX_VERSION = Regex("""FEX-\d{4}(?:\.\d+)?(?:-\d+-g[0-9a-f]{6,})?""")
    private val PROTON_BUILD = Regex("[A-Za-z0-9._+-]{1,64}")
    private val FEX_COMMIT = Regex("[0-9a-f]{7,40}")
    private val ROW_KEYS = setOf("stock_md5", "fex_version", "proton_build", "patched", "patched_md5", "patch", "patch_md5", "fex_commit", "supersedes")
    /** The md5 of an empty file: never a stock, patched or superseded DLL (a crash can leave a 0-byte DLL). */
    const val EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e"

    class Layout(val store: File, val rootfs: File)

    class Row(
        val stockMd5: String, val fexVersion: String, val protonBuild: String?, val patched: String, val patchedMd5: String,
        val patch: String, val patchMd5: String, val fexCommit: String?,
        /** Patched md5s of older builds for this stock DLL: a Proton DLL with one of them gets this row's DLL. */
        val supersedes: List<String> = emptyList(),
    ) {
        fun json(): JSONObject = JSONObject().put("stock_md5", stockMd5).put("fex_version", fexVersion)
            .put("proton_build", protonBuild ?: JSONObject.NULL).put("patched_md5", patchedMd5).put("patch_md5", patchMd5)
            .put("fex_commit", fexCommit ?: JSONObject.NULL).put("supersedes", JSONArray(supersedes))
    }

    class Bundled(val version: String, val rows: List<Row>, val tableMd5: String, val commit: String = "", val date: String = "", val changelog: String = "")

    interface Host {
        val sessionRunning: Boolean
        val bundled: Bundled?
    }

    class Prepared internal constructor(
        val version: String, val meta: ComponentMeta, val rows: List<Row>, val zipSha256: String, val tableMd5: String,
        internal val staging: File, internal val root: File,
    )

    class Status(
        val source: String, val version: String, val commit: String, val date: String, val changelog: String, val rows: List<Row>,
        val tableMd5: String, val previous: String?, val bundledVersion: String?, val canSwitch: Boolean,
        /** Which table the guest reads now: "active" ([ACTIVE_DIR]), "bundled" ([BUNDLED_DIR]) or "none". */
        val guestTable: String,
    ) {
        fun json(): JSONObject = JSONObject().put("source", source).put("version", version).put("commit", commit).put("date", date)
            .put("changelog", changelog).put("rows", JSONArray(rows.map { it.json() })).put("tableMd5", tableMd5)
            .put("hasPrevious", previous != null).put("previous", previous ?: JSONObject.NULL)
            .put("bundledVersion", bundledVersion ?: JSONObject.NULL).put("canSwitch", canSwitch).put("guestTable", guestTable)
    }

    // ---- context entry points ----

    fun layout(context: Context) = Layout(File(context.filesDir, STORE), LinuxRuntime.rootDir(context))

    fun host(context: Context): Host = object : Host {
        override val sessionRunning get() = BlobferryPackage.sessionBusy()
        override val bundled get() = bundled(context)
    }

    /** The package the APK bundles (assets linuxfs/[BUNDLED_DIR]/meta.json + table.json), null without one. */
    fun bundled(context: Context): Bundled? = runCatching {
        val meta = ComponentMeta.parse(context.assets.open("linuxfs/$BUNDLED_DIR/${ComponentMeta.NAME}").use { it.readBytes().toString(Charsets.UTF_8) })
        val table = context.assets.open("linuxfs/$BUNDLED_DIR/$TABLE").use { it.readBytes() }
        if (meta.component != COMPONENT) null
        else Bundled(meta.version, parseTable(table.toString(Charsets.UTF_8)), hex(MessageDigest.getInstance("MD5").digest(table)), meta.commit, meta.date, meta.changelog)
    }.onFailure { Log.w(TAG, "no bundled fex-arm64ec table", it) }.getOrNull()

    /** The bundled package's files (asset path = rootfs path) for SessionFiles.stage(); empty without a package. */
    fun bundledFiles(context: Context): List<String> = runCatching {
        val meta = ComponentMeta.parse(context.assets.open("linuxfs/$BUNDLED_DIR/${ComponentMeta.NAME}").use { it.readBytes().toString(Charsets.UTF_8) })
        (listOf(ComponentMeta.NAME) + meta.files.keys).map { "$BUNDLED_DIR/$it" }
    }.getOrDefault(emptyList())

    fun prepare(context: Context, input: InputStream, expectedSha256: String? = null) = prepare(layout(context), input, expectedSha256)
    fun install(context: Context, prepared: Prepared, source: String) = install(layout(context), host(context), prepared, source)
    fun rollback(context: Context) = rollback(layout(context), host(context))
    fun reset(context: Context) = reset(layout(context), host(context))
    fun status(context: Context) = status(layout(context), host(context))

    /** stage(): see [restage]; the bundled files are already staged, [bundledFiles] says which stay in [BUNDLED_DIR]. */
    fun restage(context: Context, rootfs: File, bundledFiles: List<String>) {
        val layout = Layout(File(context.filesDir, STORE), rootfs)
        runCatching { restage(layout, host(context), bundledFiles) }.onFailure { Log.w(TAG, "fex-arm64ec restage failed", it) }
    }

    // ---- table ----

    /** table.json's rows; BAD_TABLE when it is not a list of 1..[MAX_ROWS] well-formed rows with unique keys. */
    fun parseTable(text: String): List<Row> {
        fun bad(why: String): Nothing = throw BlobferryException("BAD_TABLE", "table.json: $why")
        val list = runCatching { JSONArray(text) }.getOrNull() ?: bad("not a JSON list")
        if (list.length() !in 1..MAX_ROWS) bad("must have 1..$MAX_ROWS rows")
        val rows = (0 until list.length()).map { i ->
            val j = list.optJSONObject(i) ?: bad("row $i is not an object")
            j.keys().forEach { if (it !in ROW_KEYS) bad("row $i: unknown key $it") }
            fun text(key: String, rx: Regex, optional: Boolean = false): String? {
                if (optional && (!j.has(key) || j.isNull(key))) return null
                val v = j.opt(key) as? String ?: bad("row $i: $key missing")
                if (!rx.matches(v)) bad("row $i: bad $key '$v'")
                return v
            }
            val patched = j.optString("patched")
            val patch = j.optString("patch")
            if (!ComponentMeta.safePath(patched) || !patched.startsWith("dll/")) bad("row $i: patched must be a dll/ file of the package")
            if (!ComponentMeta.safePath(patch) || !patch.startsWith("patches/")) bad("row $i: patch must be a patches/ file of the package")
            val supersedes = if (!j.has("supersedes")) emptyList() else {
                val list = j.optJSONArray("supersedes") ?: bad("row $i: supersedes must be a list")
                if (list.length() !in 1..MAX_ROWS) bad("row $i: supersedes must list 1..$MAX_ROWS md5s")
                (0 until list.length()).map { k -> (list.opt(k) as? String)?.takeIf { HEX32.matches(it) } ?: bad("row $i: bad supersedes entry ${list.opt(k)}") }
            }
            Row(
                text("stock_md5", HEX32)!!, text("fex_version", FEX_VERSION)!!, text("proton_build", PROTON_BUILD, true), patched,
                text("patched_md5", HEX32)!!, patch, text("patch_md5", HEX32)!!, text("fex_commit", FEX_COMMIT, true), supersedes,
            )
        }
        if (rows.map { it.patched }.toSet().size != rows.size) bad("one DLL per row")
        val seen = HashSet<String>()
        for (md5 in rows.flatMap { listOf(it.stockMd5, it.patchedMd5) + it.supersedes }) {
            if (md5 == EMPTY_MD5) bad("$md5 is the md5 of an empty file")
            if (!seen.add(md5)) bad("$md5 is listed twice (one role in one row only)")
        }
        return rows
    }

    /** The rows against the package files: each DLL and patch has its md5, is listed in meta.json, the DLL is ARM64EC with the row's FEX version; no other DLL. */
    internal fun verifyRows(root: File, rows: List<Row>, meta: ComponentMeta) {
        for (r in rows) {
            val dll = File(root, r.patched)
            if (r.patched !in meta.files || !dll.isFile) throw BlobferryException("BAD_TABLE", "${r.fexVersion}: ${r.patched} is not in the package")
            if (md5(dll) != r.patchedMd5) throw BlobferryException("BAD_CHECKSUM", "${r.patched}: md5 is not ${r.patchedMd5}")
            if (!arm64ecDll(dll)) throw BlobferryException("BAD_ARCH", "${r.patched} is not an ARM64EC PE DLL")
            val version = fexVersion(dll)
            if (version != r.fexVersion) throw BlobferryException("BAD_TABLE", "${r.patched} says ${version ?: "no FEX version"}, the row ${r.fexVersion}")
            val patch = File(root, r.patch)
            if (r.patch !in meta.files || !patch.isFile || md5(patch) != r.patchMd5) {
                throw BlobferryException("BAD_CHECKSUM", "${r.fexVersion}: ${r.patch} missing or not md5 ${r.patchMd5}")
            }
        }
        val used = rows.map { it.patched }.toSet()
        meta.files.keys.firstOrNull { it.startsWith("dll/") && it !in used }?.let { throw BlobferryException("BAD_TABLE", "$it is in no row") }
        for (name in listOf("LICENSE", "NOTICE")) if (name !in meta.files) throw BlobferryException("BAD_STRUCTURE", "$name is missing")
    }

    /** The longest FEX version string in the file (as droiddeck-mali-defaults reads it), null without one. */
    fun fexVersion(file: File): String? {
        if (file.length() > MAX_DLL_BYTES) return null
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        return FEX_VERSION.findAll(text).map { it.value }.maxByOrNull { it.length }
    }

    /**
     * A PE32+ DLL of machine AMD64 (0x8664) whose load config carries a CHPE (hybrid) metadata pointer: how ARM64EC images
     * look (both FEX builds of 11-04 and 11-11, and Proton's stock DLL). x86-64 DLLs lack the pointer; ARM64X would say 0xAA64.
     */
    fun arm64ecDll(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() > MAX_DLL_BYTES) return false
        RandomAccessFile(file, "r").use { f ->
            fun u16(at: Long): Int { f.seek(at); return f.read() or (f.read() shl 8) }
            fun u32(at: Long): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
            fun u64(at: Long): Long = u32(at) or (u32(at + 4) shl 32)
            if (u16(0) != 0x5a4d) return false
            val pe = u32(0x3c)
            if (pe + 24 > f.length() || u32(pe) != 0x4550L) return false
            val machine = u16(pe + 4)
            val sections = u16(pe + 6)
            val optionalSize = u16(pe + 20)
            val characteristics = u16(pe + 22)
            val opt = pe + 24
            if (machine != 0x8664 || characteristics and 0x2000 == 0 || u16(opt) != 0x20b) return false
            val imageBase = u64(opt + 24)
            val configRva = u32(opt + 112 + 10 * 8)
            val configSize = u32(opt + 112 + 10 * 8 + 4)
            if (configSize < 0xd0) return false
            val table = opt + optionalSize
            fun offset(rva: Long): Long? {
                for (i in 0 until sections) {
                    val s = table + 40L * i
                    val virtualSize = u32(s + 8)
                    val address = u32(s + 12)
                    val rawSize = u32(s + 16)
                    val raw = u32(s + 20)
                    if (rva >= address && rva < address + maxOf(virtualSize, rawSize) && rva - address < rawSize) return raw + rva - address
                }
                return null
            }
            val config = offset(configRva) ?: return false
            if (u32(config) < 0xd0) return false
            val chpe = u64(config + 0xc8)
            chpe > imageBase && offset(chpe - imageBase) != null
        }
    }.getOrDefault(false)

    // ---- verify ----

    fun prepare(layout: Layout, input: InputStream, expectedSha256: String? = null): Prepared {
        if (expectedSha256 != null && !HEX64.matches(expectedSha256)) throw BlobferryException("BAD_CHECKSUM", "the expected sha256 is not 64 hex digits")
        val store = ComponentStore(layout.store)
        store.sweep()
        var unpacked: ComponentStore.Unpacked? = null
        try {
            unpacked = ComponentStore.unpack(layout.store, input, PREFIX, MAX_ZIP_BYTES, MAX_ENTRIES, MAX_UNPACKED_BYTES, expectedSha256)
            return verify(unpacked)
        } catch (e: BlobferryException) {
            unpacked?.staging?.deleteRecursively()
            ComponentLog.append(ComponentLog.forStore(layout.store), COMPONENT, ComponentLog.REFUSED, null, unpacked?.version, unpacked?.zipSha256, null, e.code)
            throw e
        } catch (e: Exception) {
            unpacked?.staging?.deleteRecursively()
            throw e
        }
    }

    fun discard(prepared: Prepared) {
        prepared.staging.deleteRecursively()
    }

    internal fun verify(u: ComponentStore.Unpacked, appVersionCode: Long = BuildConfig.VERSION_CODE.toLong()): Prepared {
        if (!Regex("[A-Za-z0-9._+-]{1,48}").matches(u.version)) {
            throw BlobferryException("BAD_STRUCTURE", "invalid package version '${u.version}'")
        }
        val metaFile = u.files[ComponentMeta.NAME] ?: throw BlobferryException("BAD_META", "meta.json is missing")
        val meta = ComponentMeta.parse(metaFile.readText())
        if (meta.component != COMPONENT) throw BlobferryException("BAD_META", "meta.json is for '${meta.component}', not $COMPONENT")
        if (meta.version != u.version) throw BlobferryException("BAD_META", "meta.json says version ${meta.version}, the package is ${u.version}")
        // strict: every file, LICENSE and NOTICE included, must be listed
        meta.verifyTree(u.root, appVersionCode) { false }
        val table = u.files[TABLE] ?: throw BlobferryException("BAD_STRUCTURE", "$TABLE is missing")
        if (TABLE !in meta.files) throw BlobferryException("BAD_META", "$TABLE is not listed in meta.json")
        val rows = parseTable(table.readText())
        verifyRows(u.root, rows, meta)
        return Prepared(u.version, meta, rows, u.zipSha256, md5(table), u.staging, u.root)
    }

    // ---- switch ----

    fun install(layout: Layout, host: Host, prepared: Prepared, source: String): Status = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val from = currentVersion(store, host)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            if (!prepared.root.isDirectory) throw BlobferryException("STALE", "the prepared package is gone; import it again")
            requireNotOlder(prepared.version, host)
            val record = JSONObject().put("version", prepared.version).put("commit", prepared.meta.commit).put("zipSha256", prepared.zipSha256)
                .put("tableMd5", prepared.tableMd5).put("rows", prepared.rows.size).put("source", source)
                .put("installedAt", System.currentTimeMillis()).put("meta", prepared.meta.record())
            store.install(prepared.root, record, host.bundled?.version) { sync(layout, it) }
            Log.i(TAG, "fex-arm64ec ${prepared.version} installed from $source (${prepared.rows.size} rows)")
            log(layout, ComponentLog.INSTALL, from, prepared.version, prepared.zipSha256, source, ComponentLog.OK)
            status(layout, host)
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, prepared.version, prepared.zipSha256, source, e.code)
            throw e
        } finally {
            prepared.staging.deleteRecursively()
        }
    }

    fun rollback(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val from = currentVersion(store, host)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            store.record(store.previous)?.let { requireNotOlder(it.optString("version"), host) }
            store.rollback(host.bundled?.version) { sync(layout, it) }
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, null, null, null, e.code, ComponentLog.ROLLBACK)
            throw e
        }
        status(layout, host).also { log(layout, ComponentLog.ROLLBACK, from, it.version, store.activeRecord()?.optString("zipSha256"), it.source, ComponentLog.OK) }
    }

    /** Back to the bundled table; the installed one is kept as previous/ (a roll back undoes it). */
    fun reset(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val from = currentVersion(store, host)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            val bundled = host.bundled ?: throw BlobferryException("NO_BUNDLED", "this app has no bundled fex-arm64ec table")
            store.reset(bundled.version) { sync(layout, it) }
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, null, null, null, e.code, ComponentLog.RESET)
            throw e
        }
        status(layout, host).also { log(layout, ComponentLog.RESET, from, it.version, null, it.source, ComponentLog.OK) }
    }

    /**
     * At every stage(): finishes an interrupted switch, applies the APK-upgrade rule (an installed table older than the bundled
     * one goes to previous/), makes [ACTIVE_DIR] match active/ (removed without one), removes files of an older bundled
     * package from [BUNDLED_DIR] (the 0.6.9-mali.1 single DLL included) and moves the guest's fex events into the install
     * log. Returns what it did.
     */
    fun restage(layout: Layout, host: Host, bundledFiles: List<String>): String = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val recovered = store.recover { sync(layout, it) }
        if (recovered) log(layout, ComponentLog.RESTAGE, null, currentVersion(store, host), null, null, ComponentLog.OK)
        val bundled = host.bundled?.version
        val upgraded = store.upgradeAtStart(bundled) { sync(layout, it) }
        if (upgraded != null) {
            log(layout, ComponentLog.UPGRADE_AT_START, upgraded, "$bundled (bundled)", null, SOURCE_BUNDLED, ComponentLog.OK,
                "the APK's table is newer; $upgraded kept as previous")
        }
        val changed = sync(layout, store.active.takeIf { store.record(it) != null })
        cleanBundled(layout.rootfs, bundledFiles.toSet())
        ingestGuestLog(layout)
        when {
            recovered -> "recovered"
            upgraded != null -> "upgrade-at-start"
            changed -> "restaged"
            else -> "current"
        }
    }

    private fun requireNotOlder(version: String, host: Host) {
        val bundled = host.bundled?.version
        if (ComponentStore.olderThanBundled(version, bundled)) throw BlobferryException(
            "OLDER_THAN_BUNDLED", "fex-arm64ec $version is older than the table this app bundles ($bundled); it would never be used",
        )
    }

    /** Moves the guest's fex-arm64ec events (droiddeck-mali-defaults) and guest-tools events into the app's install log. */
    fun ingestGuestLog(layout: Layout): Int = ComponentLog.ingest(ComponentLog.forStore(layout.store), File(layout.rootfs, GUEST_LOG)) { f ->
        // one guest log for every component: droiddeck-guest-tools-check writes its events there too
        (f[1] == COMPONENT && f[2] in GUEST_OPS) || GuestToolsPackage.guestEvent(f)
    }

    /** [ACTIVE_DIR] := [pkg]'s files (copied beside it, checked, swapped in by rename), or removed for null. True when it changed. */
    private fun sync(layout: Layout, pkg: File?): Boolean {
        val target = File(layout.rootfs, ACTIVE_DIR)
        val next = File(layout.rootfs, "$ACTIVE_DIR.new")
        val old = File(layout.rootfs, "$ACTIVE_DIR.old")
        next.deleteRecursively()
        old.deleteRecursively()
        if (pkg == null) return target.exists().also { if (it && !target.deleteRecursively()) throw IOException("cannot remove $target") }
        val record = File(pkg, ComponentStore.RECORD).readText()
        val files = pkg.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(pkg).invariantSeparatorsPath }
        if (current(target, pkg, record, files)) return false
        for ((rel, f) in files) f.copyTo(File(next, rel), overwrite = true)
        for (row in parseTable(File(next, TABLE).readText())) {
            if (md5(File(next, row.patched)) != row.patchedMd5) throw IOException("${row.patched} changed in the store")
        }
        if (target.exists() && !target.renameTo(old)) throw IOException("cannot replace $target")
        if (!next.renameTo(target)) {
            old.renameTo(target)
            throw IOException("cannot write $target")
        }
        old.deleteRecursively()
        return true
    }

    /**
     * [target] holds [pkg]'s files: the same record and meta.json text, every file meta.json lists with its md5 (the DLLs and
     * table, so a same-length change is caught too), the others byte-equal, nothing else.
     */
    private fun current(target: File, pkg: File, record: String, files: Map<String, File>): Boolean {
        return runCatching {
            if (File(target, ComponentStore.RECORD).readText() != record) return false
            val metaText = File(pkg, ComponentMeta.NAME).readText()
            if (File(target, ComponentMeta.NAME).readText() != metaText) return false
            val sums = ComponentMeta.parse(metaText).files
            val present = target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).invariantSeparatorsPath }.toSet()
            present == files.keys && files.all { (rel, f) ->
                val t = File(target, rel)
                val md5 = sums[rel]?.md5
                if (md5 != null) t.length() == f.length() && md5(t) == md5 else t.readBytes().contentEquals(f.readBytes())
            }
        }.getOrDefault(false)
    }

    /** Regular files in the rootfs [BUNDLED_DIR] that the APK's package does not list go (they belong to an older APK). */
    private fun cleanBundled(rootfs: File, keep: Set<String>) {
        val dir = File(rootfs, BUNDLED_DIR)
        if (!dir.isDirectory) return
        dir.walkBottomUp().forEach { f ->
            val rel = BUNDLED_DIR + "/" + f.relativeTo(dir).invariantSeparatorsPath
            if (f.isFile && rel !in keep && !f.name.endsWith(".staged")) f.delete()
            else if (f.isDirectory && f != dir && f.list().isNullOrEmpty()) f.delete()
        }
    }

    // ---- status ----

    fun status(layout: Layout, host: Host): Status {
        val store = ComponentStore(layout.store)
        val record = store.activeRecord()
        val bundled = host.bundled
        val meta = record?.optJSONObject("meta")
        val rows = if (record != null) runCatching { parseTable(File(store.active, TABLE).readText()) }.getOrDefault(emptyList()) else bundled?.rows.orEmpty()
        val guestTable = when {
            File(layout.rootfs, "$ACTIVE_DIR/$TABLE").isFile -> "active"
            File(layout.rootfs, "$BUNDLED_DIR/$TABLE").isFile -> "bundled"
            else -> "none"
        }
        return Status(
            source = record?.optString("source") ?: if (bundled != null) SOURCE_BUNDLED else "none",
            version = record?.optString("version") ?: bundled?.version.orEmpty(),
            commit = record?.optString("commit") ?: bundled?.commit.orEmpty(),
            date = if (record != null) meta?.optString("date").orEmpty() else bundled?.date.orEmpty(),
            changelog = if (record != null) meta?.optString("changelog").orEmpty() else bundled?.changelog.orEmpty(),
            rows = rows, tableMd5 = record?.optString("tableMd5") ?: bundled?.tableMd5.orEmpty(),
            previous = store.previousVersion(), bundledVersion = bundled?.version, canSwitch = !host.sessionRunning, guestTable = guestTable,
        )
    }

    /** The patched md5s of the table the guest reads (report: which FEX DLLs this install may put in). */
    fun guestPatchedMd5s(rootfs: File): List<String> = listOf(ACTIVE_DIR, BUNDLED_DIR).firstNotNullOfOrNull { dir ->
        runCatching { parseTable(File(rootfs, "$dir/$TABLE").readText()).map { it.patchedMd5 } }.getOrNull()
    }.orEmpty()

    // ---- helpers ----

    private fun currentVersion(store: ComponentStore, host: Host): String =
        store.activeRecord()?.optString("version") ?: host.bundled?.version?.let { "$it (bundled)" } ?: "none"

    private fun log(layout: Layout, op: String, from: String?, to: String?, sha256: String?, source: String?, result: String, note: String? = null) =
        ComponentLog.append(ComponentLog.forStore(layout.store), COMPONENT, op, from, to, sha256, source, result, note)

    internal fun md5(file: File): String {
        val d = MessageDigest.getInstance("MD5")
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
