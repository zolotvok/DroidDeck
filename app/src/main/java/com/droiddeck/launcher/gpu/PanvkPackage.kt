package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.ArchivePaths
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * PanVK, the session's Vulkan driver on Mali (glibc build of panvk-kbase-android), as a managed component next to
 * Blobferry. A verified `panvk-kbase-<V>.zip` goes into OUR OWN reserved driver slot `linux_vulkan_drivers/Blobferry_PanVK`
 * (release-perf-1111.md § 3.3, user 11-11: "our PanVK should be separate"); its meta.json says `owner: blobferry`. No other
 * slot (PanVK_Kbase, Turnip, a hand import) is ever written, removed or renamed here; the hand-import path cannot take the
 * reserved id (LinuxVulkanDriverManager.isReservedId). Install, roll back and reset write the slot only while it is absent
 * or ours.
 *
 * Package (tools/make-panvk-component.py): one top folder `panvk-kbase-<V>/` with `libvulkan_panfrost.so` (AArch64
 * ELF, glibc), `meta.json` (kind linux-vulkan-icd, backend panvk-kbase), `SOURCE.txt`, `LICENSE` and `SHA256SUMS`
 * listing every other file (both ways, as Blobferry's MD5SUMS). Only the .so is installed.
 *
 * Store `files/panvk-blobferry/`: `state.json` = the active package record, `previous/` = the .so before (+ its
 * meta.json, icd.json, package.json), `applying` = the journal. The live .so is replaced by one rename, so the slot never
 * lacks a driver; recover() finishes the bookkeeping of a switch a crash interrupted. ensureCurrent() (every stage())
 * creates the slot, copies a bundled PanVK_Kbase into it once (migration), and upgrades it when the APK bundles a newer
 * one or its .so is a known-stale build. Nothing but [use] / [unuse] (an explicit droiddeckctl command, D10 c) changes a
 * driver choice. Switches only while no session runs. Status says which PanVK the session uses (review M1).
 * [app, Claude Opus 5.5] 2026-11-06, own slot 2026-11-13
 */
object PanvkPackage {
    private const val TAG = "PanvkPackage"
    const val SLOT_ID = LinuxVulkanDriverManager.RESERVED_PANVK_ID
    const val SLOT_NAME = "PanVK (Blobferry)"
    const val OWNER = LinuxVulkanDriverManager.RESERVED_PANVK_OWNER
    const val STORE = "panvk-blobferry"
    /** The slot the APK's bundled PanVK went into before 0.6.9-mali.2 (now someone else's; only read once, to migrate). */
    const val LEGACY_SLOT_ID = "PanVK_Kbase"
    const val LEGACY_STORE = "panvk"
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
    /** The driver choice before the last [use] (key + exact value), so [unuse] restores it. */
    private const val USE_RECORD = "use.json"
    val VERSION = Regex("[A-Za-z0-9._+-]{1,48}")
    private val SUM_LINE = Regex("""^([0-9a-f]{64}) [ *](?:\./)?(.+)$""")
    private val COMMIT = Regex("""\b([0-9a-f]{40})\b""")
    private val HEX64 = Regex("(?i)[0-9a-f]{64}")
    private val TOP_FILES = listOf(LIB, "meta.json", "SOURCE.txt", "LICENSE", "SHA256SUMS")

    /**
     * .so md5s of PanVK builds that must not stay in our slot (docs/agents/global-regress-1106.md, release-perf-1111.md
     * § 1 row 1): a3ead172 (sha256 735ec272dc27c9c2..., the pre-MSRTSS build the test phone ran 11-06..11-11) and
     * 81ec6d60 (the 234-236 build before it). The fixed build is bda2be8e.
     */
    val STALE_MD5 = setOf("a3ead17274c7ab3d7eda5ade964a176e", "81ec6d602322309464b10c81dec5ff2b")

    class Layout(val store: File, val slot: File, val slotName: String = SLOT_NAME)

    /** The pre-0.6.9-mali.2 bundled slot and its store, read once by the migration. */
    class Legacy(val store: File, val slot: File)

    interface Host {
        val sessionRunning: Boolean
        /** The PanVK package the APK bundles (version + zip sha256), null without one. */
        val bundled: Bundled?
        /** The bundled zip (reset, ensureCurrent), null without one. */
        fun openBundled(): InputStream? = null
        /** The Linux driver the session uses (LinuxVulkanDriver.sessionDriver; "" = the runtime's own), null = unknown. */
        val sessionDriver: String? get() = null
    }

    class Bundled(val version: String, val sha256: String)

    class Prepared internal constructor(
        val version: String, val driverVersion: String, val commit: String, val zipSha256: String,
        val soMd5: String, val soSha256: String, val minGlibc: String,
        internal val staging: File, internal val root: File,
        /** The § 3.1 meta fields (component/date/changelog/repo), when the package's meta.json carries them. */
        val meta: ComponentMeta? = null,
    )

    /** A PanVK slot that is not ours: listed read-only ("user-installed, not managed"), never touched. */
    class Foreign(val id: String, val name: String, val driverVersion: String, val soMd5: String) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("driverVersion", driverVersion).put("soMd5", soMd5)
            .put("managed", false).put("note", "user-installed, not managed")
    }

    /** The driver the session uses ([driver] "" = the runtime's own): [panvk] describes it when it is a PanVK slot. */
    class SessionUse(val driver: String, val ours: Boolean, val panvk: JSONObject?, val running: Boolean)

    class Status(
        val source: String, val version: String, val driverVersion: String, val commit: String, val soMd5: String,
        val previous: String?, val bundledVersion: String?, val installed: Boolean, val canSwitch: Boolean, val slot: String,
        val date: String = "", val changelog: String = "",
        /** The slot exists but its meta.json does not say owner blobferry: nothing here writes it. */
        val notOurs: Boolean = false,
        val foreign: List<Foreign> = emptyList(),
        /** null when the caller does not know the session's driver (the pure entry points' hosts). */
        val session: SessionUse? = null,
        /** While a [use] record exists: the session driver before the first use ("" = the runtime's own), what [unuse] goes back to. */
        val useBack: String? = null,
    ) {
        /** Our slot is the session's PanVK; null = unknown. */
        val inUse: Boolean? get() = session?.ours

        /** One plain line when our slot is installed but the session runs another driver ([op] status or a change). */
        fun note(op: String): String? {
            val s = session ?: return null
            if (s.ours || !installed || notOurs) return null
            val uses = "the session uses ${label(s.driver)}"
            // `panvk use` is accepted only while the session's driver is a PanVK slot (review L1)
            val hint = if (s.panvk != null) "run `droiddeckctl panvk use` to switch" else "the session does not use a PanVK"
            return if (op == "status") "$uses, not $slot; $hint"
            else "$uses; this changed $slot, which is not in use. ${hint.replaceFirstChar { it.uppercase() }}"
        }

        fun json(): JSONObject = JSONObject().put("source", source).put("version", version).put("driverVersion", driverVersion)
            .put("commit", commit).put("soMd5", soMd5).put("hasPrevious", previous != null).put("previous", previous ?: JSONObject.NULL)
            .put("bundledVersion", bundledVersion ?: JSONObject.NULL).put("installed", installed).put("canSwitch", canSwitch)
            .put("slot", slot).put("date", date).put("changelog", changelog).put("owner", if (notOurs) JSONObject.NULL else OWNER)
            .put("notOurs", notOurs).put("foreign", org.json.JSONArray(foreign.map { it.json() }))
            .put("inUse", inUse ?: JSONObject.NULL).put("sessionDriver", session?.driver?.ifEmpty { null } ?: JSONObject.NULL)
            .put("sessionPanvk", session?.panvk ?: JSONObject.NULL)
            .put("sessionFrom", session?.let { if (it.running) "running session" else "next session start" } ?: JSONObject.NULL)
            .put("useBack", useBack ?: JSONObject.NULL)
    }

    /** The result of [use] / [unuse]: the session driver before and after, and whether a stored choice changed. */
    class Choice(val status: Status, val from: String, val to: String, val changed: Boolean, val note: String)

    fun label(driver: String) = driver.ifEmpty { "the runtime's own driver" }

    // ---- context entry points ----

    fun layout(context: Context) = Layout(File(context.filesDir, STORE), LinuxVulkanDriverManager(context).getDriverDir(SLOT_ID))

    fun legacy(context: Context) = Legacy(File(context.filesDir, LEGACY_STORE), LinuxVulkanDriverManager(context).getDriverDir(LEGACY_SLOT_ID))

    fun host(context: Context, mali: Boolean = com.droiddeck.launcher.core.DeviceSupport.mali()): Host = object : Host {
        override val sessionRunning get() = BlobferryPackage.sessionBusy()
        override val bundled get() = bundled(context)
        override fun openBundled(): InputStream? = openBundledAsset(context)
        override val sessionDriver get() = runCatching { LinuxVulkanDriver.sessionDriver(context, mali) }
            .onFailure { Log.w(TAG, "session driver unknown", it) }.getOrNull()
    }

    private fun openBundledAsset(context: Context): InputStream? = runCatching { context.assets.open(BUNDLED_ASSET) }.getOrNull()

    fun bundled(context: Context): Bundled? = runCatching {
        context.assets.open(BUNDLED_META).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()?.let { j -> Bundled(j.optString("version"), j.optString("sha256")).takeIf { it.version.isNotEmpty() && HEX64.matches(it.sha256) } }

    fun prepare(context: Context, input: InputStream, expectedSha256: String? = null) = prepare(layout(context), input, expectedSha256)
    fun install(context: Context, prepared: Prepared, source: String) = install(layout(context), host(context), prepared, source)
    fun rollback(context: Context) = rollback(layout(context), host(context))
    fun reset(context: Context) = reset(layout(context), host(context))
    fun status(context: Context) = status(layout(context), host(context))
    fun recover(context: Context) = recover(layout(context))

    /**
     * Mali, at stage(): our slot is created, migrated or upgraded (see the pure ensureCurrent). No driver choice changes.
     * [startsSession]: the session start's own stage(), before its guest starts; stage() from a guest command or Proton
     * extras may run during a session, which then defers any write (review L1).
     */
    @JvmStatic fun ensureCurrent(context: Context, startsSession: Boolean = false): String {
        if (!com.droiddeck.launcher.core.DeviceSupport.mali()) return "not-mali"
        val bundled = bundled(context)
        val busy = !startsSession && BlobferryPackage.sessionBusy()
        val host = object : Host {
            override val sessionRunning = busy
            override val bundled = bundled
            override fun openBundled(): InputStream? = openBundledAsset(context)
        }
        return ensureCurrent(layout(context), host, legacy(context)).also { Log.i(TAG, "PanVK slot $SLOT_ID: $it") }
    }

    /**
     * Our slot at stage(): finishes an interrupted switch; copies a bundled PanVK_Kbase into it while ours is missing
     * (migration, the source stays); creates it from the APK's PanVK when still missing; upgrades it when the bundled
     * version is newer than our managed one or its .so is on [STALE_MD5] (a hand-deployed unknown .so in our slot is left
     * alone otherwise). previous/ keeps the .so before. Returns what it did ("deferred" while a session runs and a write
     * would be needed). Never reads or writes a driver choice.
     */
    fun ensureCurrent(layout: Layout, host: Host, legacy: Legacy? = null, staleMd5: Set<String> = STALE_MD5): String = synchronized(BlobferryPackage.LOCK) {
        recover(layout)
        if (!ownedOrAbsent(layout)) {
            log(layout, ComponentLog.REFUSED, currentVersion(layout), host.bundled?.version, null, SOURCE_BUNDLED, "NOT_OURS")
            return@synchronized "not-ours"
        }
        if (host.sessionRunning && slotAbsent(layout)) return@synchronized "deferred"
        val migrated = legacy != null && slotAbsent(layout) && migrate(layout, legacy)
        val bundled = host.bundled ?: return@synchronized if (migrated) "migrated" else if (slotAbsent(layout)) "none" else "current"
        val live = File(layout.slot, LIB)
        val creating = !live.isFile
        val liveMd5 = if (creating) "" else md5(live)
        if (!creating) {
            val state = readJson(File(layout.store, STATE))?.takeIf { it.optString("soMd5") == liveMd5 }
            val stale = liveMd5 in staleMd5
            // Only a slot the APK filled follows the APK: a rollback or a chosen older install stays (review M2).
            val newer = state != null && state.optString("source") == SOURCE_BUNDLED && compareVersions(bundled.version, state.optString("version")) > 0
            if (!stale && !newer) return@synchronized if (migrated) "migrated" else "current"
        }
        if (host.sessionRunning) return@synchronized "deferred"
        val prepared = host.openBundled()?.use { prepare(layout, it, bundled.sha256) }
            ?: return@synchronized if (migrated) "migrated" else "no-bundled-zip"
        if (prepared.soMd5 == liveMd5) {
            discard(prepared)
            return@synchronized "current"
        }
        install(layout, host, prepared, SOURCE_BUNDLED, if (creating) ComponentLog.INSTALL else ComponentLog.UPGRADE_AT_START)
        if (creating) (if (migrated) "migrated" else "created") else if (migrated) "migrated+upgraded" else "upgraded"
    }

    /**
     * Copies a PanVK_Kbase slot whose package record says source bundled (ours, before the own slot) into our missing slot.
     * The legacy slot, its store and every driver choice stay as they are; only its .so is read.
     */
    fun migrate(layout: Layout, legacy: Legacy): Boolean = synchronized(BlobferryPackage.LOCK) {
        if (!slotAbsent(layout)) return@synchronized false
        val record = readJson(File(legacy.store, STATE)) ?: return@synchronized false
        if (record.optString("source") != SOURCE_BUNDLED) return@synchronized false
        val so = File(legacy.slot, LIB)
        if (!so.isFile || md5(so) != record.optString("soMd5")) return@synchronized false
        val state = JSONObject(record.toString()).put("migratedFrom", legacy.slot.name).put("installedAt", System.currentTimeMillis())
        switchTo(layout, so, state, null)
        log(layout, ComponentLog.MIGRATE, legacy.slot.name, state.optString("version"), state.optString("zipSha256"), SOURCE_BUNDLED, ComponentLog.OK)
        Log.i(TAG, "copied the bundled PanVK ${state.optString("version")} from ${legacy.slot.name} into ${layout.slot.name}")
        true
    }

    /** The slot does not exist, or is an empty folder. */
    private fun slotAbsent(layout: Layout): Boolean = !layout.slot.exists() || (layout.slot.isDirectory && layout.slot.list().isNullOrEmpty())

    /** Absent, or a folder whose meta.json says owner blobferry: the only slot states anything here writes to. */
    fun ownedOrAbsent(layout: Layout): Boolean = slotAbsent(layout) ||
        (layout.slot.isDirectory && readJson(File(layout.slot, LinuxVulkanDriverManager.META_NAME))?.optString("owner") == OWNER)

    private fun requireOurs(layout: Layout) {
        if (!ownedOrAbsent(layout)) throw BlobferryException(
            "NOT_OURS", "${layout.slot.name} exists but is not the Blobferry PanVK slot (meta.json has no owner $OWNER); it is left alone",
        )
    }

    /** Version order: numbers numerically, letters as text ("v26.3.1-234-237" > "v26.3.1+234-236"). */
    fun compareVersions(a: String, b: String): Int {
        val ta = Regex("[0-9]+|[A-Za-z]+").findAll(a).map { it.value }.toList()
        val tb = Regex("[0-9]+|[A-Za-z]+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').padStart(1, '0').let { xs ->
                val ys = y.trimStart('0').padStart(1, '0')
                if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ta.size.compareTo(tb.size)
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
        } catch (e: BlobferryException) {
            ComponentLog.append(ComponentLog.forStore(layout.store), COMPONENT, ComponentLog.REFUSED, null, null, null, null, e.code)
            throw e
        } finally {
            zip.delete()
            if (!ok) staging.deleteRecursively()
        }
    }

    fun discard(prepared: Prepared) {
        prepared.staging.deleteRecursively()
    }

    internal fun verify(zip: File, staging: File, zipSha: String, appVersionCode: Long = BuildConfig.VERSION_CODE.toLong()): Prepared {
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
        // the § 3.1 fields are optional (older packages lack them); when there, they must describe exactly this package
        val component = if (!ComponentMeta.isComponentMeta(meta)) null else ComponentMeta.parse(meta).also { m ->
            if (m.component != COMPONENT) throw BlobferryException("BAD_META", "meta.json is for '${m.component}', not $COMPONENT")
            if (m.version != version) throw BlobferryException("BAD_META", "meta.json says version ${m.version}, the package is $version")
            if (commit.isNotEmpty() && m.commit != commit) throw BlobferryException("BAD_META", "meta.json says commit ${m.commit}, SOURCE.txt says $commit")
            m.verifyTree(root, appVersionCode)
        }
        return Prepared(
            version, meta.optString("driverVersion").ifEmpty { version }, commit.ifEmpty { component?.commit.orEmpty() }, zipSha, md5(so),
            sums.getValue(LIB), meta.optString("minGlibc"), staging, root, component,
        )
    }

    // ---- switch ----

    fun install(layout: Layout, host: Host, prepared: Prepared, source: String, op: String = ComponentLog.INSTALL): Status = synchronized(BlobferryPackage.LOCK) {
        val from = currentVersion(layout)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            requireOurs(layout)
            val so = File(prepared.root, LIB)
            if (!so.isFile) throw BlobferryException("STALE", "the prepared package is gone; import it again")
            if (md5(so) != prepared.soMd5) throw BlobferryException("BAD_CHECKSUM", "the prepared $LIB changed")
            val state = JSONObject().put("version", prepared.version).put("driverVersion", prepared.driverVersion)
                .put("commit", prepared.commit).put("zipSha256", prepared.zipSha256).put("soMd5", prepared.soMd5)
                .put("soSha256", prepared.soSha256).put("minGlibc", prepared.minGlibc).put("source", source)
                .put("installedAt", System.currentTimeMillis())
            prepared.meta?.let { m -> state.put("date", m.date).put("changelog", m.changelog).put("repo", m.repo) }
            switchTo(layout, so, state, null)
            Log.i(TAG, "PanVK ${prepared.version} installed from $source into ${layout.slot}")
            log(layout, op, from, prepared.version, prepared.zipSha256, source, ComponentLog.OK, notInUse(layout, host))
            status(layout, host)
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, prepared.version, prepared.zipSha256, source, e.code)
            throw e
        } finally {
            prepared.staging.deleteRecursively()
        }
    }

    /** Swaps the live .so and previous/ (rolling back again goes forward again). */
    fun rollback(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
        try {
            requireOurs(layout)
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, currentVersion(layout), null, null, null, e.code)
            throw e
        }
        val previous = File(layout.store, "previous")
        val so = File(previous, LIB)
        val state = readJson(File(previous, PACKAGE))
        if (!so.isFile || state == null) throw BlobferryException("NO_PREVIOUS", "there is no previous PanVK")
        if (md5(so) != state.optString("soMd5")) throw BlobferryException("BAD_CHECKSUM", "the previous $LIB changed on disk")
        // switchTo stages a copy of previous/'s .so before it rewrites previous/ with the live one
        val from = currentVersion(layout)
        switchTo(layout, so, state, readJson(File(previous, LinuxVulkanDriverManager.META_NAME)))
        log(layout, ComponentLog.ROLLBACK, from, state.optString("version"), state.optString("zipSha256"), state.optString("source"), ComponentLog.OK,
            notInUse(layout, host))
        status(layout, host)
    }

    /** Back to the PanVK the APK bundles, in our slot only (the .so before is kept for a roll back). */
    fun reset(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
        val bundled = host.bundled ?: throw BlobferryException("NO_BUNDLED", "this app has no bundled PanVK")
        val prepared = host.openBundled()?.use { prepare(layout, it, bundled.sha256) }
            ?: throw BlobferryException("NO_BUNDLED", "this app has no bundled PanVK")
        install(layout, host, prepared, SOURCE_BUNDLED, ComponentLog.RESET)
    }

    /**
     * D10 (c), only on an explicit user command (droiddeckctl panvk use): our slot becomes the session's PanVK. Exactly one
     * stored choice changes: an explicit PanVK Linux driver choice is swapped for ours; otherwise (automatic, or the bridge
     * for the games, which stays) the session's PanVK pick [SessionPrefs.SESSION_PANVK]. Its value before goes into use.json
     * for [unuse]. No driver slot is written. Refused while a session runs and when the session's driver is not a PanVK.
     *
     * use.json holds a list of records (key, value before), one per key (review M2 a): a second use after the user chose
     * another PanVK explicitly keeps the first record (sessionPanvk) next to the new one (linuxDriver), and [unuse] undoes
     * both. A record whose key no longer holds our id is dead (the later choice wins, review L3) and is dropped here.
     */
    fun use(context: Context, mali: Boolean = com.droiddeck.launcher.core.DeviceSupport.mali()): Choice = synchronized(BlobferryPackage.LOCK) {
        val layout = layout(context)
        val host = host(context, mali)
        val manager = LinuxVulkanDriverManager(context)
        val before = LinuxVulkanDriver.sessionDriver(context, mali)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            requireOurs(layout)
            if (!manager.isOurPanvk(SLOT_ID) || !manager.isPanvk(SLOT_ID)) {
                throw BlobferryException("NOT_INSTALLED", "$SLOT_ID is not installed yet (the next session start creates it)")
            }
            if (before == SLOT_ID) return@synchronized Choice(status(layout, host), before, before, false, "$SLOT_ID is already the session's PanVK; nothing changed")
            val chosen = SessionPrefs.linuxDriver(context, mali)
            val explicit = chosen.isNotEmpty() && !manager.isBridge(chosen)
            val key = when {
                explicit && manager.isPanvk(chosen) -> SessionPrefs.LINUX_DRIVER
                !explicit && manager.isPanvk(before) -> SessionPrefs.SESSION_PANVK
                else -> throw BlobferryException("NOT_PANVK_SESSION", "the session uses ${label(before)}, not a PanVK; choose the Linux driver on the driver page")
            }
            val prior = SessionPrefs.storedChoice(context, key)
            val recordFile = File(layout.store, USE_RECORD)
            val oldText = recordFile.takeIf { it.isFile }?.readText()
            // records still in force (their key holds our id) stay; the new one is added for its key
            val kept = useRecords(layout).filter { it.key != key && SessionPrefs.storedChoice(context, it.key) == SLOT_ID }
            val record = UseRecord(key, prior, before, System.currentTimeMillis(), applied = false)
            writeAtomic(recordFile, useJson(kept + record))
            if (!SessionPrefs.setStoredChoice(context, key, SLOT_ID)) {
                restoreRecord(recordFile, oldText)
                throw BlobferryException("APPLY_FAILED", "the driver choice was not saved")
            }
            // applied: a record without it is of a use a crash stopped before the pref was saved (review L3)
            runCatching { writeAtomic(recordFile, useJson(kept + record.copy(applied = true))) }
            val after = LinuxVulkanDriver.sessionDriver(context, mali)
            if (after != SLOT_ID) {
                // the record stays when the choice could not be put back, so unuse can still undo it (review L3)
                if (SessionPrefs.setStoredChoice(context, key, prior)) restoreRecord(recordFile, oldText)
                else throw BlobferryException("APPLY_FAILED", "the session would still use ${label(after)}, and the choice before could not be put back; run `droiddeckctl panvk unuse`")
                throw BlobferryException("APPLY_FAILED", "the session would still use ${label(after)}; the choice is back as it was")
            }
            log(layout, ComponentLog.USE, before, SLOT_ID, null, key, ComponentLog.OK)
            Choice(status(layout, host), before, SLOT_ID, true, "the next session start uses $SLOT_ID; `droiddeckctl panvk unuse` goes back to ${label(before)}")
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, before, SLOT_ID, null, null, e.code, ComponentLog.USE)
            throw e
        }
    }

    /**
     * Undoes [use]: every stored choice it changed that still holds our id gets back its exact value before ("not stored"
     * included), the newest record first. A record whose key was changed since is dead: that later choice stays and the
     * record is removed (CHANGED_SINCE when nothing is left to undo, review L3); a record of a use that never took effect
     * (not marked applied: a crash before the pref was saved, the key still holds the value before) is removed (NO_PRIOR). Refused with PRIOR_GONE,
     * nothing changed, when the driver the session would go back to is no longer installed (review M1). No slot is written.
     */
    fun unuse(context: Context, mali: Boolean = com.droiddeck.launcher.core.DeviceSupport.mali()): Choice = synchronized(BlobferryPackage.LOCK) {
        val layout = layout(context)
        val host = host(context, mali)
        val before = LinuxVulkanDriver.sessionDriver(context, mali)
        val recordFile = File(layout.store, USE_RECORD)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            if (!recordFile.isFile) throw BlobferryException("NO_PRIOR", "nothing to undo: no `droiddeckctl panvk use` is in force")
            val records = useRecords(layout)
            if (records.isEmpty()) {
                recordFile.delete()
                throw BlobferryException("NO_PRIOR", "the record of the last panvk use is not readable; it was removed")
            }
            val live = records.filter { SessionPrefs.storedChoice(context, it.key) == SLOT_ID }
            if (live.isEmpty()) {
                recordFile.delete()
                val changed = records.firstOrNull { it.applied || SessionPrefs.storedChoice(context, it.key) != it.value }
                    ?: throw BlobferryException("NO_PRIOR", "nothing to undo: the last panvk use never took effect")
                throw BlobferryException("CHANGED_SINCE",
                    "the driver choice changed since panvk use (now ${SessionPrefs.storedChoice(context, changed.key) ?: "not stored"}); it stays")
            }
            val undone = mutableListOf<UseRecord>()
            fun putBack(): Boolean = undone.all { SessionPrefs.setStoredChoice(context, it.key, SLOT_ID) }
            for (r in live.asReversed()) {
                if (!SessionPrefs.setStoredChoice(context, r.key, r.value)) {
                    putBack()
                    throw BlobferryException("APPLY_FAILED", "the driver choice was not saved")
                }
                undone += r
            }
            val after = LinuxVulkanDriver.sessionDriver(context, mali)
            if (after.isNotEmpty() && LinuxVulkanDriverManager(context).getIcdPath(after) == null) {
                if (!putBack()) throw BlobferryException("APPLY_FAILED", "the choice before ($after) is no longer installed and $SLOT_ID could not be put back; choose a driver on the driver page")
                throw BlobferryException("PRIOR_GONE", "the choice before ($after) is no longer installed; choose a driver on the driver page")
            }
            recordFile.delete()
            log(layout, ComponentLog.UNUSE, before, after, null, live.joinToString("+") { it.key }, ComponentLog.OK)
            Choice(status(layout, host), before, after, true, "the next session start uses ${label(after)} again")
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, before, null, null, null, e.code, ComponentLog.UNUSE)
            throw e
        }
    }

    /** One stored choice [use] changed: [key] held [value] (null = not stored) before. */
    data class UseRecord(val key: String, val value: String?, val session: String, val at: Long, val applied: Boolean = true)

    /** The records in use.json, oldest first; the single-object form of the first 0.6.9-mali.2 build is read too. */
    internal fun useRecords(layout: Layout): List<UseRecord> {
        val json = readJson(File(layout.store, USE_RECORD)) ?: return emptyList()
        val list = json.optJSONArray("records") ?: org.json.JSONArray().put(json)
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key").takeIf { it == SessionPrefs.LINUX_DRIVER || it == SessionPrefs.SESSION_PANVK } ?: return@mapNotNull null
            UseRecord(key, if (o.isNull("value")) null else o.optString("value"), o.optString("session"), o.optLong("at"), o.optBoolean("applied", true))
        }
    }

    private fun useJson(records: List<UseRecord>): String = JSONObject().put("records", org.json.JSONArray(records.map {
        JSONObject().put("key", it.key).put("value", it.value ?: JSONObject.NULL).put("session", it.session).put("at", it.at).put("applied", it.applied)
    })).toString(2)

    private fun restoreRecord(file: File, text: String?) {
        if (text == null) file.delete() else writeAtomic(file, text)
    }

    /** Settings reset: the undo record goes with the sessionPanvk / linuxDriver prefs it describes (review M2 b). */
    fun forgetUse(context: Context): Boolean = File(File(context.filesDir, STORE), USE_RECORD).let { !it.exists() || it.delete() }

    /**
     * At the session start's stage(), after ensureCurrent: when our slot is not installed (APK without a bundled PanVK, the
     * slot removed by hand), no stored choice may point at it (review M2): sessionPanvk == our id is removed, linuxDriver ==
     * our id becomes "" (automatic, as the driver page does on a delete), use.json goes. The session then takes the normal
     * pick (LinuxVulkanDriver.pickSessionPanvk). Returns the keys it cleared.
     */
    @JvmStatic fun dropChoicesOfMissingSlot(context: Context): List<String> = synchronized(BlobferryPackage.LOCK) {
        if (LinuxVulkanDriverManager(context).getIcdPath(SLOT_ID) != null) return@synchronized emptyList()
        val cleared = mutableListOf<String>()
        if (SessionPrefs.storedChoice(context, SessionPrefs.SESSION_PANVK) == SLOT_ID &&
            SessionPrefs.setStoredChoice(context, SessionPrefs.SESSION_PANVK, null)) cleared += SessionPrefs.SESSION_PANVK
        if (SessionPrefs.storedChoice(context, SessionPrefs.LINUX_DRIVER) == SLOT_ID &&
            SessionPrefs.setStoredChoice(context, SessionPrefs.LINUX_DRIVER, "")) cleared += SessionPrefs.LINUX_DRIVER
        if (cleared.isNotEmpty()) {
            forgetUse(context)
            Log.w(TAG, "$SLOT_ID is not installed: cleared ${cleared.joinToString()} (the session takes the normal pick)")
            log(layout(context), ComponentLog.UNUSE, SLOT_ID, null, null, cleared.joinToString("+"), ComponentLog.OK, "slot missing")
        }
        cleared
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
        // our slot always says who owns it and which version it holds (a roll back restores the rest of its meta)
        val newMeta = JSONObject((meta ?: slotMeta(state)).toString()).put("owner", OWNER)
            .put("name", "${layout.slotName} ${state.optString("version")}".trim())
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

    private fun slotMeta(state: JSONObject): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("kind", "linux-vulkan-icd").put("owner", OWNER)
        .put("driverVersion", state.optString("driverVersion")).put("libc", "glibc").put("minGlibc", state.optString("minGlibc"))
        .put("sourceLibraryName", LIB).put("importedAt", System.currentTimeMillis())
        .put("source", state.optString("source")).put("packageVersion", state.optString("version")).put("commit", state.optString("commit"))

    /** At every stage(): finishes the bookkeeping of a switch a crash interrupted. The live .so is always one whole file. */
    fun recover(layout: Layout): Boolean = synchronized(BlobferryPackage.LOCK) {
        File(layout.store, "previous.new").let { next ->
            if (File(next, PACKAGE).isFile && File(next, LIB).isFile) {
                File(layout.store, "previous").deleteRecursively()
                next.renameTo(File(layout.store, "previous"))
            } else next.deleteRecursively()
        }
        layout.store.listFiles()?.filter { it.name.startsWith("staging-") || it.name.startsWith("incoming-") }
            ?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000L }?.forEach { it.deleteRecursively() }
        // the slot is touched only while it is ours, or our own journal says a switch into it was interrupted (no meta yet)
        val journal = readJson(File(layout.store, JOURNAL))
        if (journal == null) {
            if (ownedOrAbsent(layout)) File(layout.slot, "$LIB.staged").delete()
            return@synchronized false
        }
        File(layout.slot, "$LIB.staged").delete()
        val live = File(layout.slot, LIB)
        if (live.isFile && md5(live) == journal.optString("soMd5")) {
            Log.w(TAG, "an interrupted PanVK switch: finishing it")
            finish(layout, journal.getJSONObject("state"), journal.getJSONObject("meta"))
            log(layout, ComponentLog.RESTAGE, null, journal.getJSONObject("state").optString("version"), null, null, ComponentLog.OK)
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
        val notOurs = !ownedOrAbsent(layout)
        val version = if (managed) state!!.optString("version") else meta?.optString("driverVersion").orEmpty()
        val foreign = foreign(layout)
        val session = host.sessionDriver?.let { id ->
            val ours = id == layout.slot.name && live.isFile && !notOurs
            val panvk = if (ours) JSONObject().put("id", id).put("name", meta?.optString("name").orEmpty()).put("version", version)
                .put("driverVersion", meta?.optString("driverVersion").orEmpty()).put("soMd5", md5).put("ours", true)
            else foreign.firstOrNull { it.id == id }?.let { f ->
                JSONObject().put("id", f.id).put("name", f.name).put("version", f.driverVersion).put("driverVersion", f.driverVersion)
                    .put("soMd5", f.soMd5).put("ours", false)
            }
            SessionUse(id, ours, panvk, host.sessionRunning)
        }
        return Status(
            source = when {
                !live.isFile -> "none"
                managed -> state!!.optString("source")
                state != null -> "changed on disk"
                else -> SOURCE_HAND
            },
            version = version,
            driverVersion = meta?.optString("driverVersion").orEmpty(),
            commit = if (managed) state!!.optString("commit") else meta?.optString("commit").orEmpty(),
            soMd5 = md5, previous = previous?.optString("version")?.ifEmpty { "?" }, bundledVersion = host.bundled?.version,
            installed = live.isFile, canSwitch = !host.sessionRunning, slot = layout.slot.name,
            date = if (managed) state!!.optString("date") else "", changelog = if (managed) state!!.optString("changelog") else "",
            notOurs = notOurs, foreign = foreign, session = session,
            useBack = runCatching { useRecords(layout).firstOrNull()?.session }.getOrNull(),
        )
    }

    /** The other PanVK slots next to ours, read only (name, version, .so md5) for the status and the components list. */
    fun foreign(layout: Layout): List<Foreign> =
        layout.slot.absoluteFile.parentFile?.listFiles().orEmpty().filter { it.isDirectory && it.name != layout.slot.name }
            .filter { File(it, LIB).isFile && File(it, LinuxVulkanDriverManager.ICD_NAME).isFile }.sortedBy { it.name }
            .map { dir ->
                val meta = readJson(File(dir, LinuxVulkanDriverManager.META_NAME))
                Foreign(dir.name, meta?.optString("name").orEmpty().ifEmpty { dir.name }, meta?.optString("driverVersion").orEmpty(),
                    runCatching { md5(File(dir, LIB)) }.getOrDefault(""))
            }

    // ---- helpers ----

    const val COMPONENT = "panvk"

    /** The slot's version without hashing: the package record's, else the slot meta's driver version. */
    private fun currentVersion(layout: Layout): String = when {
        !File(layout.slot, LIB).isFile -> "none"
        else -> readJson(File(layout.store, STATE))?.optString("version")?.ifEmpty { null }
            ?: readJson(File(layout.slot, LinuxVulkanDriverManager.META_NAME))?.optString("driverVersion")?.ifEmpty { null } ?: "hand-imported"
    }

    private fun log(layout: Layout, op: String, from: String?, to: String?, sha256: String?, source: String?, result: String, note: String? = null) =
        ComponentLog.append(ComponentLog.forStore(layout.store), COMPONENT, op, from, to, sha256, source, result, note)

    /** The install-log note of a change to our slot while the session uses another driver (review M1). */
    private fun notInUse(layout: Layout, host: Host): String? =
        host.sessionDriver?.takeIf { it != layout.slot.name }?.let { "not in use: session uses ${it.ifEmpty { "runtime-default" }}" }

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
