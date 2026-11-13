package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PanvkPackageTest {
    private lateinit var tmp: File
    private lateinit var layout: PanvkPackage.Layout
    private val host = object : PanvkPackage.Host {
        var running = false
        override val sessionRunning get() = running
        override val bundled: PanvkPackage.Bundled? = null
    }
    private val commit = "10a845b24cce8278838951dbc603fd9c6d8d9f8f"

    @Before fun setUp() {
        tmp = Files.createTempDirectory("panvk").toFile()
        layout = PanvkPackage.Layout(File(tmp, "store"), File(tmp, "linux_vulkan_drivers/Blobferry_PanVK"))
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun so(tag: String, machine: Int = 183, glibc: Boolean = true) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = (machine and 0xff).toByte(); it[19] = (machine shr 8).toByte()
    } + (if (glibc) "libc.so.6\u0000" else "libc.so\u0000").toByteArray() + tag.toByteArray()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun md5(b: ByteArray) = hex(MessageDigest.getInstance("MD5").digest(b))

    private fun files(tag: String, backend: String = "panvk-kbase", lib: ByteArray = so(tag)) = linkedMapOf(
        "libvulkan_panfrost.so" to lib,
        "meta.json" to JSONObject().put("kind", "linux-vulkan-icd").put("backend", backend).put("driverVersion", "v26.3.1+$tag")
            .put("minGlibc", "2.34").toString().toByteArray(),
        "SOURCE.txt" to "PanVK $tag\nTree commit $commit\n".toByteArray(),
        "LICENSE" to "MIT".toByteArray(),
        "patches/237.patch" to "diff".toByteArray(),
    )

    private fun zip(
        version: String, content: Map<String, ByteArray>, top: String = "panvk-kbase-$version/",
        sums: String? = null, extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, data) in content + extra) { z.putNextEntry(ZipEntry(top + name)); z.write(data); z.closeEntry() }
            z.putNextEntry(ZipEntry(top + "SHA256SUMS"))
            z.write((sums ?: content.entries.joinToString("") { (n, d) -> "${sha256(d)}  ./$n\n" }).toByteArray())
            z.closeEntry()
        }
    }.toByteArray()

    private fun prepare(bytes: ByteArray, sha: String? = null) = PanvkPackage.prepare(layout, bytes.inputStream(), sha)

    private fun rejected(code: String, bytes: ByteArray, sha: String? = null) {
        try {
            prepare(bytes, sha)
            fail("expected $code")
        } catch (e: BlobferryException) {
            assertEquals(e.message, code, e.code)
        }
        assertTrue(layout.store.listFiles().orEmpty().none { it.name.startsWith("staging-") || it.name.startsWith("incoming-") })
    }

    /** A hand-deployed .so in our slot (owner blobferry, no package record). */
    private fun handImported(tag: String = "81ec6d60"): ByteArray {
        val lib = so(tag)
        layout.slot.mkdirs()
        File(layout.slot, PanvkPackage.LIB).writeBytes(lib)
        File(layout.slot, "meta.json").writeText(JSONObject().put("name", "PanVK (Blobferry)").put("owner", "blobferry")
            .put("driverVersion", "v26.3.1+234-236").toString())
        File(layout.slot, "icd.json").writeText(JSONObject().put("ICD", JSONObject().put("library_path", "x").put("api_version", "1.4.363")).toString())
        return lib
    }

    @Test fun verifiesAGoodPackage() {
        val p = prepare(zip("v26.3.1-234-237", files("237")))
        assertEquals("v26.3.1-234-237", p.version)
        assertEquals("v26.3.1+237", p.driverVersion)
        assertEquals(commit, p.commit)
        assertEquals(md5(so("237")), p.soMd5)
        PanvkPackage.discard(p)
    }

    @Test fun refusesBrokenOrForeignPackages() {
        val good = files("237")
        rejected("BAD_CHECKSUM", zip("v1", good, sums = good.entries.joinToString("") { (n, d) -> "${sha256(d + byteArrayOf(1))}  ./$n\n" }))
        rejected("BAD_CHECKSUM", zip("v1", good, extra = mapOf("smuggled.so" to so("x"))))
        rejected("BAD_ARCH", zip("v1", files("x", lib = so("x", machine = 62))))
        rejected("BAD_ARCH", zip("v1", files("x", lib = so("x", glibc = false))))
        rejected("NOT_A_PACKAGE", zip("v1", files("x", backend = "panvk-drm")))
        rejected("NOT_A_PACKAGE", zip("v1", good, top = "PanVK-Kbase-glibc/"))
        rejected("BAD_STRUCTURE", zip("v1", good - "LICENSE"))
        rejected("UNSAFE_PATH", zip("v1", good, extra = mapOf("../evil" to "x".toByteArray())))
        rejected("BAD_CHECKSUM", zip("v1", good), sha = "0".repeat(64))
        rejected("NOT_A_PACKAGE", "not a zip".toByteArray())
    }

    @Test fun installKeepsTheSlotIdTheNameAndTheHandImportForARollBack() {
        val old = handImported()
        val status = PanvkPackage.install(layout, host, prepare(zip("v26.3.1-234-237", files("237"))), BlobferryPackage.SOURCE_ADB)
        assertEquals("adb", status.source)
        assertEquals("v26.3.1-234-237", status.version)
        assertEquals(commit, status.commit)
        assertEquals(md5(so("237")), status.soMd5)
        assertEquals("Blobferry_PanVK", status.slot)
        val meta = JSONObject(File(layout.slot, "meta.json").readText())
        assertEquals("PanVK (Blobferry) v26.3.1-234-237", meta.getString("name"))
        assertEquals("blobferry", meta.getString("owner"))
        assertEquals("v26.3.1+237", meta.getString("driverVersion"))
        val icd = JSONObject(File(layout.slot, "icd.json").readText()).getJSONObject("ICD")
        assertEquals(File(layout.slot, PanvkPackage.LIB).absolutePath, icd.getString("library_path"))
        assertEquals("1.4.363", icd.getString("api_version"))
        assertEquals(md5(old), md5(File(layout.store, "previous/${PanvkPackage.LIB}").readBytes()))
        assertEquals("v26.3.1+234-236", status.previous)
        assertTrue(layout.store.listFiles().orEmpty().none { it.name.startsWith("staging-") })
        assertFalse(File(layout.slot, "${PanvkPackage.LIB}.staged").exists())

        val back = PanvkPackage.rollback(layout, host)
        assertEquals(PanvkPackage.SOURCE_HAND, back.source)
        assertEquals(md5(old), back.soMd5)
        assertEquals("v26.3.1-234-237", back.previous)
        assertEquals("v26.3.1+234-236", JSONObject(File(layout.slot, "meta.json").readText()).getString("driverVersion"))
        assertEquals("blobferry", JSONObject(File(layout.slot, "meta.json").readText()).getString("owner"))
        val forward = PanvkPackage.rollback(layout, host)
        assertEquals("adb", forward.source)
        assertEquals(md5(so("237")), forward.soMd5)
    }

    @Test fun aFreshPhoneGetsTheSlotAndNoPrevious() {
        val status = PanvkPackage.install(layout, host, prepare(zip("v2", files("2"))), PanvkPackage.SOURCE_BUNDLED)
        assertEquals("bundled", status.source)
        assertTrue(status.installed)
        assertNull(status.previous)
        assertEquals("PanVK (Blobferry) v2", JSONObject(File(layout.slot, "meta.json").readText()).getString("name"))
        try {
            PanvkPackage.rollback(layout, host)
            fail()
        } catch (e: BlobferryException) {
            assertEquals("NO_PREVIOUS", e.code)
        }
    }

    @Test fun noSwitchWhileASessionRuns() {
        handImported()
        val p = prepare(zip("v2", files("2")))
        host.running = true
        try {
            PanvkPackage.install(layout, host, p, "adb")
            fail()
        } catch (e: BlobferryException) {
            assertEquals("SESSION_RUNNING", e.code)
        }
        assertEquals(PanvkPackage.SOURCE_HAND, PanvkPackage.status(layout, host).source)
        assertFalse(p.root.exists())
    }

    @Test fun aHandDeployOverAManagedOneShowsAsChanged() {
        PanvkPackage.install(layout, host, prepare(zip("v2", files("2"))), "adb")
        File(layout.slot, PanvkPackage.LIB).writeBytes(so("hand"))
        assertEquals("changed on disk", PanvkPackage.status(layout, host).source)
    }

    @Test fun recoverFinishesAnInterruptedSwitch() {
        handImported()
        val lib = so("3")
        // the .so was renamed in, then the app died before meta/state: the journal finishes it
        File(layout.slot, PanvkPackage.LIB).writeBytes(lib)
        layout.store.mkdirs()
        val state = JSONObject().put("version", "v3").put("soMd5", md5(lib)).put("source", "download")
        File(layout.store, "applying").writeText(JSONObject().put("soMd5", md5(lib)).put("state", state)
            .put("meta", JSONObject().put("name", "PanVK Kbase").put("driverVersion", "v3")).toString())
        File(layout.slot, "${PanvkPackage.LIB}.staged").writeBytes(lib)
        assertTrue(PanvkPackage.recover(layout))
        assertFalse(File(layout.store, "applying").exists())
        assertFalse(File(layout.slot, "${PanvkPackage.LIB}.staged").exists())
        val status = PanvkPackage.status(layout, host)
        assertEquals("download", status.source)
        assertEquals("v3", status.version)
        assertFalse(PanvkPackage.recover(layout))
    }

    private fun withComponentMeta(content: Map<String, ByteArray>, version: String, minApp: Int = 20, skip: String? = null): Map<String, ByteArray> {
        val list = JSONObject()
        for ((n, d) in content) if (n != "meta.json" && n != "LICENSE" && n != "SOURCE.txt" && n != skip) {
            list.put(n, JSONObject().put("sha256", sha256(d)).put("md5", md5(d)))
        }
        val meta = JSONObject(String(content.getValue("meta.json"))).put("component", "panvk").put("version", version)
            .put("date", "2026-11-13").put("changelog", "MSRTSS fix").put("minApp", minApp).put("files", list)
            .put("source", JSONObject().put("repo", "https://github.com/zolotvok/blobferry").put("commit", commit))
        return content + ("meta.json" to meta.toString().toByteArray())
    }

    @Test fun aComponentMetaIsEnforcedWhenPresentAndInstallsAreLogged() {
        val good = withComponentMeta(files("237"), "v2")
        val status = PanvkPackage.install(layout, host, prepare(zip("v2", good)), BlobferryPackage.SOURCE_ADB)
        assertEquals("2026-11-13", status.date)
        assertEquals("MSRTSS fix", status.changelog)
        rejected("APP_TOO_OLD", zip("v3", withComponentMeta(files("3"), "v3", minApp = 10_000)))
        rejected("BAD_META", zip("v3", withComponentMeta(files("3"), "v4")))
        rejected("BAD_META", zip("v3", withComponentMeta(files("3"), "v3", skip = "patches/237.patch")))
        val log = ComponentLog.tail(ComponentLog.forStore(layout.store), 10).map { it.split('\t') }
        assertEquals(listOf("install", "refused", "refused", "refused"), log.map { it[2] })
        assertEquals(listOf("panvk", "none", "v2", "adb", "ok"), log[0].let { listOf(it[1], it[3], it[4], it[6], it[7]) })
        assertEquals(listOf("APP_TOO_OLD", "BAD_META", "BAD_META"), log.drop(1).map { it[7] })
    }

    /** The release-1 artifact (PanVK 10a845b, .so md5 bda2be8e), when this checkout has it. */
    @Test fun theRelease1ComponentVerifies() {
        val zip = File("../../bridge/build/release/panvk/panvk-kbase-v26.3.1-234-237.zip")
        assumeTrue(zip.isFile)
        val sha = File(zip.path + ".sha256").readText().trim().split(Regex("\\s+")).first()
        val p = PanvkPackage.prepare(layout, zip.inputStream(), sha)
        assertEquals("v26.3.1-234-237", p.version)
        assertEquals("bda2be8e18da9e6eb0e439c7df645e34", p.soMd5)
        assertEquals(commit, p.commit)
        val status = PanvkPackage.install(layout, host, p, "adb")
        assertEquals("bda2be8e18da9e6eb0e439c7df645e34", status.soMd5)
    }
}
