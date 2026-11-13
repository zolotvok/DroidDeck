package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Our PanVK in its own reserved slot Blobferry_PanVK (release-perf-1111.md § 3.3): nothing else is ever touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PanvkOwnSlotTest {
    private lateinit var tmp: File
    private lateinit var drivers: File
    private lateinit var layout: PanvkPackage.Layout
    private lateinit var legacy: PanvkPackage.Legacy
    private val commit = "10a845b24cce8278838951dbc603fd9c6d8d9f8f"

    private class FakeHost(
        var bundledZip: ByteArray? = null, var bundledVersion: String = "",
        override val sessionDriver: String? = null, override val sessionRunning: Boolean = false,
    ) : PanvkPackage.Host {
        override val bundled get() = bundledZip?.let { PanvkPackage.Bundled(bundledVersion, sha256(it)) }
        override fun openBundled(): InputStream? = bundledZip?.inputStream()
    }

    @Before fun setUp() {
        tmp = Files.createTempDirectory("panvk-own").toFile()
        drivers = File(tmp, "linux_vulkan_drivers")
        layout = PanvkPackage.Layout(File(tmp, PanvkPackage.STORE), File(drivers, PanvkPackage.SLOT_ID))
        legacy = PanvkPackage.Legacy(File(tmp, PanvkPackage.LEGACY_STORE), File(drivers, PanvkPackage.LEGACY_SLOT_ID))
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun so(tag: String) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = 0xB7.toByte()
    } + "libc.so.6\u0000".toByteArray() + tag.toByteArray()

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun zip(version: String, lib: ByteArray = so(version)): ByteArray {
        val content = linkedMapOf(
            "libvulkan_panfrost.so" to lib,
            "meta.json" to JSONObject().put("kind", "linux-vulkan-icd").put("backend", "panvk-kbase").put("driverVersion", version).toString().toByteArray(),
            "SOURCE.txt" to "PanVK\nTree commit $commit\n".toByteArray(),
            "LICENSE" to "MIT".toByteArray(),
        )
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((n, d) in content) { z.putNextEntry(ZipEntry("panvk-kbase-$version/$n")); z.write(d); z.closeEntry() }
                z.putNextEntry(ZipEntry("panvk-kbase-$version/SHA256SUMS"))
                z.write(content.entries.joinToString("") { (n, d) -> "${sha256(d)}  ./$n\n" }.toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
    }

    /** A foreign slot as the user (or an older app version) left it: a PanVK named PanVK_Kbase, no owner. */
    private fun foreignSlot(dir: File, tag: String = "users-own"): ByteArray {
        val lib = so(tag)
        dir.mkdirs()
        File(dir, PanvkPackage.LIB).writeBytes(lib)
        File(dir, "meta.json").writeText(JSONObject().put("name", "PanVK Kbase").put("driverVersion", "v26.3.1+$tag").toString())
        File(dir, "icd.json").writeText(JSONObject().put("ICD", JSONObject().put("library_path", "x").put("api_version", "1.4.363")).toString())
        return lib
    }

    private fun snapshot(dir: File): Map<String, List<Any>> = dir.walkTopDown().filter { it != dir }.associate {
        it.relativeTo(dir).path to if (it.isFile) listOf(it.readBytes().toList(), it.lastModified()) else listOf("dir")
    }

    private fun install(version: String, source: String = "adb", lib: ByteArray = so(version), host: PanvkPackage.Host = FakeHost()) =
        PanvkPackage.install(layout, host, PanvkPackage.prepare(layout, zip(version, lib).inputStream()), source)

    private fun events() = ComponentLog.tail(ComponentLog.forStore(layout.store), 100).map { it.split('\t') }

    private fun code(block: () -> Unit): String = try {
        block()
        fail("expected a refusal")
        ""
    } catch (e: BlobferryException) {
        e.code
    }

    @Test fun aForeignPanvkKbaseSlotIsByteIdenticalAfterInstallUpgradeRollbackReset() {
        val foreign = File(drivers, "PanVK_Kbase")
        foreignSlot(foreign)
        File(tmp, "panvk").mkdirs()
        File(tmp, "panvk/state.json").writeText(JSONObject().put("source", "download").put("soMd5", "0".repeat(32)).toString())
        val before = snapshot(foreign)
        val legacyStore = snapshot(File(tmp, "panvk"))
        val host = FakeHost(zip("v2"), "v2")
        install("v1", source = PanvkPackage.SOURCE_BUNDLED, host = host)
        assertEquals("upgraded", PanvkPackage.ensureCurrent(layout, host, legacy))
        PanvkPackage.rollback(layout, host)
        PanvkPackage.reset(layout, host)
        assertEquals(before, snapshot(foreign))
        assertEquals(legacyStore, snapshot(File(tmp, "panvk")))
        assertEquals("v2", PanvkPackage.status(layout, host).version)
        val status = PanvkPackage.status(layout, host).json()
        assertEquals("blobferry", status.getString("owner"))
        val listed = status.getJSONArray("foreign").getJSONObject(0)
        assertEquals("PanVK_Kbase", listed.getString("id"))
        assertEquals(md5(so("users-own")), listed.getString("soMd5"))
        assertEquals("user-installed, not managed", listed.getString("note"))
        assertEquals(listOf("install", "upgrade-at-start", "rollback", "reset"), events().map { it[2] })
    }

    @Test fun anImportCannotTakeOrClobberTheReservedId() {
        val context = RuntimeEnvironment.getApplication() as Context
        val m = LinuxVulkanDriverManager(context)
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        val ours = PanvkPackage.Layout(File(context.filesDir, PanvkPackage.STORE), m.getDriverDir(PanvkPackage.SLOT_ID))
        PanvkPackage.install(ours, FakeHost(), PanvkPackage.prepare(ours, zip("v2").inputStream()), "adb")
        val before = snapshot(ours.slot)
        for (name in listOf("Blobferry PanVK", "blobferry_panvk", "BLOBFERRY_PANVK", ".Blobferry_PanVK", "Blobferry_PanVK")) {
            val zipFile = File.createTempFile("driver", ".zip", context.cacheDir)
            ZipOutputStream(zipFile.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("libvulkan_panfrost.so")); z.write(so("evil")); z.closeEntry()
                z.putNextEntry(ZipEntry("meta.json")); z.write(JSONObject().put("name", name).toString().toByteArray()); z.closeEntry()
            }
            val id = m.installDriver(Uri.fromFile(zipFile), "x.zip")
            assertFalse(id, LinuxVulkanDriverManager.isReservedId(id))
            assertTrue(m.isPanvk(id))
        }
        // the file name path too (no meta name)
        assertFalse(LinuxVulkanDriverManager.isReservedId(m.uniqueId(LinuxVulkanDriverManager.sanitizeId("blobferry panvk"))))
        assertFalse(m.removeDriver("Blobferry_PanVK"))
        m.removeDriver("blobferry_panvk")
        assertEquals(before, snapshot(ours.slot))
        assertTrue(m.isPanvk(PanvkPackage.SLOT_ID))
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        File(context.filesDir, PanvkPackage.STORE).deleteRecursively()
    }

    @Test fun ensureCurrentCreatesUpgradesStaleAndLeavesACurrentSlotAlone() {
        val host = FakeHost(zip("v26.3.1-234-237"), "v26.3.1-234-237")
        assertEquals("created", PanvkPackage.ensureCurrent(layout, host, legacy))
        val meta = JSONObject(File(layout.slot, "meta.json").readText())
        assertEquals("blobferry", meta.getString("owner"))
        assertEquals("PanVK (Blobferry) v26.3.1-234-237", meta.getString("name"))
        val created = snapshot(layout.slot)
        assertEquals("current", PanvkPackage.ensureCurrent(layout, host, legacy))
        assertEquals(created, snapshot(layout.slot))
        assertEquals(listOf("install"), events().map { it[2] })

        // an unknown .so hand-deployed into our slot is left alone; one on the stale list is replaced, kept as previous
        val live = File(layout.slot, PanvkPackage.LIB)
        val staleBytes = so("stale")
        live.writeBytes(staleBytes)
        assertEquals("current", PanvkPackage.ensureCurrent(layout, host, legacy))
        assertArrayEquals(staleBytes, live.readBytes())
        assertEquals("upgraded", PanvkPackage.ensureCurrent(layout, host, legacy, staleMd5 = setOf(md5(staleBytes))))
        assertArrayEquals(so("v26.3.1-234-237"), live.readBytes())
        assertArrayEquals(staleBytes, File(layout.store, "previous/${PanvkPackage.LIB}").readBytes())
        assertEquals("upgrade-at-start", events().last()[2])
        assertTrue("a3ead17274c7ab3d7eda5ade964a176e" in PanvkPackage.STALE_MD5)
    }

    @Test fun ensureCurrentUpgradesAnOlderManagedSlotButNeverDowngrades() {
        val host = FakeHost(zip("v26.3.1-234-237"), "v26.3.1-234-237")
        // a chosen (adb) older install is not replaced by the APK's newer bundle (review M2); a slot the APK filled is
        install("v26.3.1-234-236")
        assertEquals("current", PanvkPackage.ensureCurrent(layout, host, legacy))
        install("v26.3.1-234-236", source = PanvkPackage.SOURCE_BUNDLED)
        assertEquals("upgraded", PanvkPackage.ensureCurrent(layout, host, legacy))
        val status = PanvkPackage.status(layout, host)
        assertEquals("v26.3.1-234-237", status.version)
        assertEquals("bundled", status.source)
        assertEquals("v26.3.1-234-236", status.previous)
        assertEquals("upgrade-at-start", events().last()[2])
        install("v26.3.1-234-240")
        assertEquals("current", PanvkPackage.ensureCurrent(layout, host, legacy))
        assertEquals("v26.3.1-234-240", PanvkPackage.status(layout, host).version)
        assertTrue(PanvkPackage.compareVersions("v26.3.1-234-237", "v26.3.1+234-236") > 0)
        assertTrue(PanvkPackage.compareVersions("v26.10.0", "v26.9.9") > 0)
        assertEquals(0, PanvkPackage.compareVersions("v1.02", "v1.2"))
    }

    @Test fun ensureCurrentNeverTouchesTheDriverChoice() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        SessionPrefs.setLinuxDriver(context, "PanVK_Kbase")
        val before = prefs.all.toMap()
        foreignSlot(legacy.slot)
        assertEquals("created", PanvkPackage.ensureCurrent(layout, FakeHost(zip("v2"), "v2"), legacy))
        assertEquals(before, prefs.all.toMap())
        assertEquals("PanVK_Kbase", SessionPrefs.linuxDriver(context, mali = true))
        prefs.edit().clear().commit()
    }

    @Test fun theSessionKeepsAnExistingPanvkAndUsesOursOnlyWhenItIsTheOnlyOne() {
        val ours = setOf("Blobferry_PanVK")
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK", "PanVK_Kbase"), ours, null))
        assertEquals("My-PanVK", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK", "My-PanVK"), ours, null))
        assertEquals("Blobferry_PanVK", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK"), ours, null))
        assertEquals("", LinuxVulkanDriver.pickSessionPanvk(emptyList(), ours, null))
        // panvk use stored ours; a stored id that is gone falls back to the automatic pick
        assertEquals("Blobferry_PanVK", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK", "PanVK_Kbase"), ours, "Blobferry_PanVK"))
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.pickSessionPanvk(listOf("PanVK_Kbase"), ours, "Blobferry_PanVK"))
        // a slot of that name without owner blobferry is no different from any other PanVK (review L2)
        assertEquals("Blobferry_PanVK", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK", "PanVK_Kbase"), emptySet(), null))
    }

    @Test fun migrationCopiesABundledPanvkKbaseAndLeavesTheSource() {
        val lib = foreignSlot(legacy.slot, "bda2be8e")
        legacy.store.mkdirs()
        File(legacy.store, "state.json").writeText(JSONObject().put("source", "bundled").put("version", "v26.3.1-234-237")
            .put("soMd5", md5(lib)).put("commit", commit).put("driverVersion", "v26.3.1+237").toString())
        val slotBefore = snapshot(legacy.slot)
        val storeBefore = snapshot(legacy.store)
        assertEquals("migrated", PanvkPackage.ensureCurrent(layout, FakeHost(), legacy))
        assertArrayEquals(lib, File(layout.slot, PanvkPackage.LIB).readBytes())
        assertEquals(slotBefore, snapshot(legacy.slot))
        assertEquals(storeBefore, snapshot(legacy.store))
        val status = PanvkPackage.status(layout, FakeHost())
        assertEquals("bundled", status.source)
        assertEquals("v26.3.1-234-237", status.version)
        assertEquals(listOf("migrate", "PanVK_Kbase", "v26.3.1-234-237"), events().single().let { listOf(it[2], it[3], it[4]) })
        // once ours exists, no second copy
        assertEquals("current", PanvkPackage.ensureCurrent(layout, FakeHost(), legacy))
        assertEquals(1, events().size)
    }

    @Test fun noMigrationOfAPanvkKbaseThatWasNotBundled() {
        val lib = foreignSlot(legacy.slot)
        legacy.store.mkdirs()
        File(legacy.store, "state.json").writeText(JSONObject().put("source", "adb").put("soMd5", md5(lib)).toString())
        assertEquals("none", PanvkPackage.ensureCurrent(layout, FakeHost(), legacy))
        assertFalse(layout.slot.exists())
        // a record that no longer matches the .so (hand-replaced since) is not ours either
        File(legacy.store, "state.json").writeText(JSONObject().put("source", "bundled").put("soMd5", "0".repeat(32)).toString())
        assertEquals("none", PanvkPackage.ensureCurrent(layout, FakeHost(), legacy))
        assertFalse(layout.slot.exists())
    }

    @Test fun aReservedSlotWithoutOwnerBlobferryIsRefusedAndLogged() {
        foreignSlot(layout.slot, "squatter")
        val before = snapshot(layout.slot)
        val host = FakeHost(zip("v2"), "v2")
        assertEquals("NOT_OURS", code { install("v3") })
        assertEquals("NOT_OURS", code { PanvkPackage.rollback(layout, host) })
        assertEquals("NOT_OURS", code { PanvkPackage.reset(layout, host) })
        assertEquals("not-ours", PanvkPackage.ensureCurrent(layout, host, legacy))
        assertEquals(before, snapshot(layout.slot))
        assertTrue(PanvkPackage.status(layout, host).notOurs)
        val log = events()
        assertEquals(4, log.size)
        assertTrue(log.all { it[2] == "refused" && it[7] == "NOT_OURS" })
    }

    // ---- review M1: which PanVK the session uses; L1: no write during a session; L2: owner meta, not the name ----

    @Test fun statusAndTheInstallLogSayWhenTheSessionUsesAnotherPanvk() {
        foreignSlot(File(drivers, "PanVK_Kbase"))
        val elsewhere = FakeHost(sessionDriver = "PanVK_Kbase")
        val status = install("v1", host = elsewhere)
        assertEquals(false, status.inUse)
        val json = status.json()
        assertEquals(false, json.getBoolean("inUse"))
        assertEquals("PanVK_Kbase", json.getString("sessionDriver"))
        assertEquals("next session start", json.getString("sessionFrom"))
        val session = json.getJSONObject("sessionPanvk")
        assertEquals("PanVK_Kbase", session.getString("id"))
        assertEquals(md5(so("users-own")), session.getString("soMd5"))
        assertEquals("v26.3.1+users-own", session.getString("version"))
        assertFalse(session.getBoolean("ours"))
        assertEquals("the session uses PanVK_Kbase; this changed Blobferry_PanVK, which is not in use. Run `droiddeckctl panvk use` to switch",
            status.note("install"))
        assertEquals("the session uses PanVK_Kbase, not Blobferry_PanVK; run `droiddeckctl panvk use` to switch", status.note("status"))
        install("v2", host = elsewhere)
        PanvkPackage.rollback(layout, elsewhere)
        for (line in events()) {
            assertEquals(9, line.size)
            assertEquals("not in use: session uses PanVK_Kbase", line[8])
            assertEquals("ok", line[7])
        }
        // review L1: no `panvk use` hint where use would be refused (the session's driver is not a PanVK)
        assertEquals("the session uses the runtime's own driver, not Blobferry_PanVK; the session does not use a PanVK",
            PanvkPackage.status(layout, FakeHost(sessionDriver = "")).note("status"))
        assertEquals("the session uses Turnip; this changed Blobferry_PanVK, which is not in use. The session does not use a PanVK",
            PanvkPackage.status(layout, FakeHost(sessionDriver = "Turnip")).note("install"))

        val here = PanvkPackage.status(layout, FakeHost(sessionDriver = PanvkPackage.SLOT_ID))
        assertEquals(true, here.inUse)
        assertEquals(null, here.note("install"))
        val ours = here.json().getJSONObject("sessionPanvk")
        assertTrue(ours.getBoolean("ours"))
        assertEquals("v1", ours.getString("version"))
        assertEquals(md5(so("v1")), ours.getString("soMd5"))
        install("v3", host = FakeHost(sessionDriver = PanvkPackage.SLOT_ID))
        assertEquals(8, events().last().size)
        // unknown session driver (the pure entry points): no claim either way
        assertTrue(PanvkPackage.status(layout, FakeHost()).json().isNull("inUse"))
    }

    @Test fun ensureCurrentDefersEveryWriteWhileASessionRuns() {
        val busy = FakeHost(zip("v2"), "v2", sessionRunning = true)
        assertEquals("deferred", PanvkPackage.ensureCurrent(layout, busy, legacy))
        assertFalse(layout.slot.exists())
        install("v1", source = PanvkPackage.SOURCE_BUNDLED)
        val before = snapshot(layout.slot)
        assertEquals("deferred", PanvkPackage.ensureCurrent(layout, busy, legacy))
        assertEquals(before, snapshot(layout.slot))
        assertEquals(listOf("install"), events().map { it[2] })
        assertEquals("upgraded", PanvkPackage.ensureCurrent(layout, FakeHost(zip("v2"), "v2"), legacy))
    }

    @Test fun aSlotNamedLikeOursWithoutOwnerIsAnOrdinaryDriver() {
        val context = RuntimeEnvironment.getApplication() as Context
        val m = LinuxVulkanDriverManager(context)
        val root = File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME)
        root.deleteRecursively()
        try {
            foreignSlot(m.getDriverDir("Blobferry_PanVK"), "old-import")
            foreignSlot(m.getDriverDir("blobferry_panvk"), "lower")
            assertTrue(m.isPanvk("Blobferry_PanVK"))
            assertFalse(m.isOurPanvk("Blobferry_PanVK"))
            assertFalse(m.isOurPanvk("blobferry_panvk"))
            assertTrue(m.removeDriver("blobferry_panvk"))
            assertTrue(m.removeDriver("Blobferry_PanVK"))
            assertFalse(m.getDriverDir("Blobferry_PanVK").exists())
            // the import path still never takes the reserved id
            assertFalse(LinuxVulkanDriverManager.isReservedId(m.uniqueId("Blobferry_PanVK")))
            val ours = PanvkPackage.layout(context)
            PanvkPackage.install(ours, FakeHost(), PanvkPackage.prepare(ours, zip("v2").inputStream()), "adb")
            assertTrue(m.isOurPanvk("Blobferry_PanVK"))
            assertFalse(m.removeDriver("Blobferry_PanVK"))
            assertTrue(m.isPanvk("Blobferry_PanVK"))
        } finally {
            root.deleteRecursively()
            File(context.filesDir, PanvkPackage.STORE).deleteRecursively()
            File(context.filesDir, ComponentLog.DIR).deleteRecursively()
        }
    }

    /** The phone's state: PanVK_Kbase (foreign) + our slot, a Robolectric context with the real driver dir and prefs. */
    private fun withPhone(block: (Context, LinuxVulkanDriverManager) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val m = LinuxVulkanDriverManager(context)
        fun clean() {
            prefs.edit().clear().commit()
            File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
            File(context.filesDir, PanvkPackage.STORE).deleteRecursively()
            File(context.filesDir, ComponentLog.DIR).deleteRecursively()
            com.droiddeck.launcher.session.SessionState.running = false
        }
        clean()
        try {
            foreignSlot(m.getDriverDir("PanVK_Kbase"))
            block(context, m)
        } finally {
            clean()
        }
    }

    private fun installOurs(context: Context) = PanvkPackage.layout(context).let { ours ->
        PanvkPackage.install(ours, FakeHost(), PanvkPackage.prepare(ours, zip("v26.3.1-234-237").inputStream()), "adb")
    }

    private fun prefsOf(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE).all.toMap()

    private fun phoneEvents(context: Context) = ComponentLog.tail(ComponentLog.file(context.filesDir), 100).map { it.split('\t') }

    @Test fun useMakesOursTheAutomaticSessionPanvkAndUnuseRestoresExactly() = withPhone { context, m ->
        installOurs(context)
        val kbase = snapshot(m.getDriverDir("PanVK_Kbase"))
        val prefsBefore = prefsOf(context)
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.sessionDriver(context, true))
        val notInUse = PanvkPackage.status(PanvkPackage.layout(context), PanvkPackage.host(context, mali = true))
        assertEquals(false, notInUse.inUse)
        val used = PanvkPackage.use(context, mali = true)
        assertTrue(used.changed)
        assertEquals("PanVK_Kbase", used.from)
        assertEquals(PanvkPackage.SLOT_ID, used.to)
        assertEquals(true, used.status.inUse)
        assertEquals(PanvkPackage.SLOT_ID, LinuxVulkanDriver.sessionDriver(context, true))
        assertEquals(PanvkPackage.SLOT_ID, SessionPrefs.sessionPanvk(context))
        assertFalse(prefsOf(context).containsKey("linuxDriver"))
        assertEquals(false, PanvkPackage.use(context, mali = true).changed)
        assertEquals(kbase, snapshot(m.getDriverDir("PanVK_Kbase")))

        val back = PanvkPackage.unuse(context, mali = true)
        assertEquals("PanVK_Kbase", back.to)
        assertEquals(prefsBefore, prefsOf(context))
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.sessionDriver(context, true))
        assertEquals("NO_PRIOR", code { PanvkPackage.unuse(context, mali = true) })
        assertEquals(kbase, snapshot(m.getDriverDir("PanVK_Kbase")))
        assertEquals(listOf("use", "PanVK_Kbase", "Blobferry_PanVK", "sessionPanvk", "ok"),
            phoneEvents(context).first { it[2] == "use" }.let { listOf(it[2], it[3], it[4], it[6], it[7]) })
        assertEquals(listOf("unuse", "Blobferry_PanVK", "PanVK_Kbase"), phoneEvents(context).first { it[2] == "unuse" }.let { listOf(it[2], it[3], it[4]) })
    }

    @Test fun useKeepsTheBridgeForTheGames() = withPhone { context, m ->
        installOurs(context)
        val bridge = m.getDriverDir(LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID).apply { mkdirs() }
        File(bridge, LinuxVulkanDriverManager.BRIDGE_LIB_NAME).writeBytes(so("bridge"))
        File(bridge, "icd.json").writeText("{}")
        assertEquals(LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID, SessionPrefs.linuxDriver(context, mali = true))
        val games = LinuxVulkanDriver.gameDriver(context, SessionPrefs.linuxDriver(context, mali = true))
        val prefsBefore = prefsOf(context)
        PanvkPackage.use(context, mali = true)
        assertEquals(LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID, SessionPrefs.linuxDriver(context, mali = true))
        assertEquals(games, LinuxVulkanDriver.gameDriver(context, SessionPrefs.linuxDriver(context, mali = true)))
        assertEquals(PanvkPackage.SLOT_ID, LinuxVulkanDriver.sessionDriver(context, true))
        PanvkPackage.unuse(context, mali = true)
        assertEquals(prefsBefore, prefsOf(context))
    }

    @Test fun useSwapsAnExplicitPanvkChoiceAndUnuseRestoresTheKeyAsItWas() = withPhone { context, _ ->
        installOurs(context)
        // an older app version stored the choice under linuxDriver.steam only
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("linuxDriver.steam", "PanVK_Kbase").commit()
        val prefsBefore = prefsOf(context)
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.sessionDriver(context, true))
        PanvkPackage.use(context, mali = true)
        assertEquals(PanvkPackage.SLOT_ID, SessionPrefs.storedChoice(context, SessionPrefs.LINUX_DRIVER))
        assertEquals(null, SessionPrefs.sessionPanvk(context))
        assertEquals(PanvkPackage.SLOT_ID, LinuxVulkanDriver.sessionDriver(context, true))
        PanvkPackage.unuse(context, mali = true)
        assertEquals(prefsBefore, prefsOf(context))

        // a choice made on the driver page after use stays
        SessionPrefs.setLinuxDriver(context, "PanVK_Kbase")
        PanvkPackage.use(context, mali = true)
        SessionPrefs.setLinuxDriver(context, "PanVK_Kbase")
        assertEquals("CHANGED_SINCE", code { PanvkPackage.unuse(context, mali = true) })
        assertEquals("PanVK_Kbase", SessionPrefs.storedChoice(context, SessionPrefs.LINUX_DRIVER))
        // review L3: the dead record is gone, so a newer explicit choice of ours is never "restored" over
        assertFalse(File(PanvkPackage.layout(context).store, "use.json").exists())
        SessionPrefs.setLinuxDriver(context, PanvkPackage.SLOT_ID)
        assertEquals("NO_PRIOR", code { PanvkPackage.unuse(context, mali = true) })
        assertEquals(PanvkPackage.SLOT_ID, SessionPrefs.storedChoice(context, SessionPrefs.LINUX_DRIVER))
    }

    @Test fun unuseIsRefusedWhenThePriorDriverIsGone() = withPhone { context, m ->
        installOurs(context)
        SessionPrefs.setStoredChoice(context, SessionPrefs.LINUX_DRIVER, "PanVK_Kbase")
        PanvkPackage.use(context, mali = true)
        // the user deletes PanVK_Kbase on the driver page while ours is chosen (the choice is not reset there)
        assertTrue(m.removeDriver("PanVK_Kbase"))
        val prefs = prefsOf(context)
        val record = File(PanvkPackage.layout(context).store, "use.json").readText()
        assertEquals("PRIOR_GONE", code { PanvkPackage.unuse(context, mali = true) })
        assertEquals(prefs, prefsOf(context))
        assertEquals(record, File(PanvkPackage.layout(context).store, "use.json").readText())
        assertEquals(PanvkPackage.SLOT_ID, LinuxVulkanDriver.sessionDriver(context, true))
        assertEquals(listOf("refused", "PRIOR_GONE", "unuse"), phoneEvents(context).last().let { listOf(it[2], it[7], it[8]) })
    }

    @Test fun aSecondUseKeepsTheFirstRecordAndUnuseUndoesBoth() = withPhone { context, _ ->
        installOurs(context)
        val prefsBefore = prefsOf(context)
        PanvkPackage.use(context, mali = true)
        assertEquals(PanvkPackage.SLOT_ID, SessionPrefs.sessionPanvk(context))
        // the user then picks PanVK_Kbase explicitly on the driver page, and runs use again
        SessionPrefs.setLinuxDriver(context, "PanVK_Kbase")
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.sessionDriver(context, true))
        PanvkPackage.use(context, mali = true)
        assertEquals(listOf(SessionPrefs.SESSION_PANVK, SessionPrefs.LINUX_DRIVER), PanvkPackage.useRecords(PanvkPackage.layout(context)).map { it.key })
        val back = PanvkPackage.unuse(context, mali = true)
        assertEquals("PanVK_Kbase", back.to)
        // both keys are back: the explicit choice the user made, and no sessionPanvk left without a record
        assertEquals(prefsBefore + ("linuxDriver" to "PanVK_Kbase"), prefsOf(context))
        assertEquals(null, SessionPrefs.sessionPanvk(context))
        assertEquals(listOf("unuse", "sessionPanvk+linuxDriver"), phoneEvents(context).last().let { listOf(it[2], it[6]) })
    }

    @Test fun aRecordOfAUseThatNeverTookEffectIsDropped() = withPhone { context, _ ->
        installOurs(context)
        val record = File(PanvkPackage.layout(context).store, "use.json")
        // the single-record form of the first 0.6.9-mali.2 build is read (as applied)
        record.parentFile!!.mkdirs()
        record.writeText("""{"key":"sessionPanvk","value":null,"session":"PanVK_Kbase","at":1}""")
        assertEquals(listOf(PanvkPackage.UseRecord("sessionPanvk", null, "PanVK_Kbase", 1, applied = true)),
            PanvkPackage.useRecords(PanvkPackage.layout(context)))
        // crash between the use.json write and the pref commit: the record is not marked applied
        record.writeText("""{"records":[{"key":"sessionPanvk","value":null,"session":"PanVK_Kbase","at":1,"applied":false}]}""")
        val prefs = prefsOf(context)
        assertEquals("NO_PRIOR", code { PanvkPackage.unuse(context, mali = true) })
        assertFalse(record.exists())
        assertEquals(prefs, prefsOf(context))
        // a use after it writes a fresh record only for its own key
        PanvkPackage.use(context, mali = true)
        assertEquals(listOf(SessionPrefs.SESSION_PANVK to true), PanvkPackage.useRecords(PanvkPackage.layout(context)).map { it.key to it.applied })
    }

    @Test fun noStoredChoicePointsAtOurSlotWhileItIsMissing() = withPhone { context, m ->
        installOurs(context)
        PanvkPackage.use(context, mali = true)
        assertEquals(emptyList<String>(), PanvkPackage.dropChoicesOfMissingSlot(context))
        assertEquals(PanvkPackage.SLOT_ID, SessionPrefs.sessionPanvk(context))
        // the slot disappears (removed by hand; an APK without a bundled PanVK cannot recreate it)
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME + "/" + PanvkPackage.SLOT_ID).deleteRecursively()
        SessionPrefs.setStoredChoice(context, SessionPrefs.LINUX_DRIVER, PanvkPackage.SLOT_ID)
        assertEquals(listOf(SessionPrefs.SESSION_PANVK, SessionPrefs.LINUX_DRIVER), PanvkPackage.dropChoicesOfMissingSlot(context))
        assertEquals(null, SessionPrefs.sessionPanvk(context))
        assertEquals("", SessionPrefs.storedChoice(context, SessionPrefs.LINUX_DRIVER))
        assertFalse(File(PanvkPackage.layout(context).store, "use.json").exists())
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.sessionDriver(context, true))
        assertEquals(emptyList<String>(), PanvkPackage.dropChoicesOfMissingSlot(context))
        assertTrue(m.isPanvk("PanVK_Kbase"))
    }

    @Test fun useIsRefusedWithoutTouchingAnything() = withPhone { context, m ->
        val prefsBefore = prefsOf(context)
        assertEquals("NOT_INSTALLED", code { PanvkPackage.use(context, mali = true) })
        installOurs(context)
        val turnip = m.getDriverDir("Turnip").apply { mkdirs() }
        File(turnip, LinuxVulkanDriverManager.LIB_NAME).writeBytes(so("turnip"))
        File(turnip, "icd.json").writeText("{}")
        SessionPrefs.setStoredChoice(context, SessionPrefs.LINUX_DRIVER, "Turnip")
        val withTurnip = prefsOf(context)
        assertEquals("NOT_PANVK_SESSION", code { PanvkPackage.use(context, mali = true) })
        assertEquals(withTurnip, prefsOf(context))
        SessionPrefs.setStoredChoice(context, SessionPrefs.LINUX_DRIVER, null)
        assertEquals(prefsBefore, prefsOf(context))
        com.droiddeck.launcher.session.SessionState.running = true
        assertEquals("SESSION_RUNNING", code { PanvkPackage.use(context, mali = true) })
        assertEquals(prefsBefore, prefsOf(context))
        assertFalse(File(PanvkPackage.layout(context).store, "use.json").exists())
        assertEquals(listOf("NOT_INSTALLED", "NOT_PANVK_SESSION", "SESSION_RUNNING"),
            phoneEvents(context).filter { it[2] == "refused" }.map { it[7] })
        assertTrue(phoneEvents(context).filter { it[2] == "refused" }.all { it[8] == "use" })
    }

    companion object {
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
