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

    private class FakeHost(var bundledZip: ByteArray? = null, var bundledVersion: String = "") : PanvkPackage.Host {
        override val sessionRunning = false
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
        install("v1", host = host)
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
        assertFalse(m.removeDriver("blobferry_panvk"))
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
        install("v26.3.1-234-236")
        val host = FakeHost(zip("v26.3.1-234-237"), "v26.3.1-234-237")
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
        assertEquals("PanVK_Kbase", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK", "PanVK_Kbase")))
        assertEquals("My-PanVK", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK", "My-PanVK")))
        assertEquals("Blobferry_PanVK", LinuxVulkanDriver.pickSessionPanvk(listOf("Blobferry_PanVK")))
        assertEquals("", LinuxVulkanDriver.pickSessionPanvk(emptyList()))
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

    companion object {
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
