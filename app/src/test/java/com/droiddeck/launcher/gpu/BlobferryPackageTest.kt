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
class BlobferryPackageTest {
    private lateinit var tmp: File
    private lateinit var layout: BlobferryPackage.Layout
    private lateinit var host: FakeHost

    private class FakeHost(var running: Boolean = false, var bundledPackage: BlobferryPackage.Bundled? = BlobferryPackage.Bundled("1.0.0-rc1")) :
        BlobferryPackage.Host {
        var restores = 0
        override val sessionRunning get() = running
        override val bundled get() = bundledPackage
        override fun restoreBundled() { restores++ }
    }

    @Before fun setUp() {
        tmp = Files.createTempDirectory("blobferry").toFile()
        layout = BlobferryPackage.Layout(File(tmp, "store"), File(tmp, "linuxfs"), File(tmp, "slot"))
        host = FakeHost()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun elf(tag: String, machine: Int = 183) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = (machine and 0xff).toByte(); it[19] = (machine shr 8).toByte()
    } + tag.toByteArray()

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun innerZip(shim: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("libvulkan_mali_bridge.so")); z.write(shim); z.closeEntry()
            z.putNextEntry(ZipEntry("meta.json")); z.write("{}".toByteArray()); z.closeEntry()
        }
    }.toByteArray()

    /** The files of a package.sh package (relative to blobferry-<v>/), MD5SUMS left out. */
    private fun files(version: String, tag: String = version, hybrisLink: Boolean = true): MutableMap<String, ByteArray> {
        val shim = elf("shim-$tag")
        val manifest = """{"file_format_version":"1.0.0","layer":{"name":"$tag"}}""".toByteArray()
        val m = linkedMapOf(
            "rootfs/usr/lib/mali_bridge/libvulkan_mali_bridge.so" to shim,
            "rootfs/usr/lib/mali_bridge/libVkLayer_droiddeck_mali_compat.so" to elf("compat-$tag"),
            "rootfs/usr/lib/mali_bridge/libVkLayer_window_system_integration.so" to elf("wsi-$tag"),
            "rootfs/usr/lib/mali_bridge/mali_bridge_icd.json" to """{"ICD":{"library_path":"/usr/lib/mali_bridge/libvulkan_mali_bridge.so"}}""".toByteArray(),
            "rootfs/usr/lib/mali_bridge/VkLayer_droiddeck_mali_compat.json" to manifest,
            "rootfs/usr/lib/mali_bridge/VkLayer_window_system_integration.json" to manifest,
            "rootfs/usr/share/vulkan/implicit_layer.d/VkLayer_droiddeck_mali_compat.json" to manifest,
            "rootfs/usr/share/vulkan/implicit_layer.d/VkLayer_window_system_integration.json" to manifest,
            "rootfs/usr/lib/hybris/libhybris/linker/q.so" to elf("linker-$tag"),
            "Mali_Bridge.zip" to innerZip(shim),
            "LICENSE" to "GPL-3.0-or-later".toByteArray(),
            "NOTICE.md" to "notice".toByteArray(),
            "SOURCE.txt" to "Blobferry $version\nSource: https://github.com/zolotvok/blobferry commit ${"a".repeat(40)}\nSubmodules:\n".toByteArray(),
            "install-adb.sh" to "#!/bin/sh".toByteArray(),
        )
        if (hybrisLink) m["rootfs/usr/lib/hybris/libhybris-common.so.1.0.0"] = elf("hybris-$tag")
        else m["rootfs/usr/lib/hybris/libhybris-common.so.1"] = elf("hybris-$tag")
        return m
    }

    private fun md5sums(files: Map<String, ByteArray>) =
        files.keys.sorted().joinToString("") { "${md5(files.getValue(it))}  ./$it\n" }.toByteArray()

    /** Writes the zip; [links] become unix symbolic-link entries (package.sh's zip -y), patched into the central directory. */
    private fun zip(
        version: String,
        files: Map<String, ByteArray>,
        sums: ByteArray? = md5sums(files),
        links: Map<String, String> = mapOf(
            "rootfs/usr/lib/hybris/libhybris-common.so.1" to "libhybris-common.so.1.0.0",
            "rootfs/usr/lib/hybris/libhybris-common.so" to "libhybris-common.so.1.0.0",
        ),
        top: String = "blobferry-$version",
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): File {
        val file = File(tmp, "in-${System.nanoTime()}.zip")
        val all = LinkedHashMap<String, ByteArray>()
        files.forEach { (k, v) -> all["$top/$k"] = v }
        if (sums != null) all["$top/MD5SUMS"] = sums
        links.forEach { (k, v) -> all["$top/$k"] = v.toByteArray() }
        all.putAll(extraEntries)
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("$top/")); z.closeEntry()
            for ((name, data) in all) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        if (links.isNotEmpty()) markLinks(file, links.keys.map { "$top/$it" }.toSet())
        return file
    }

    private fun markLinks(file: File, names: Set<String>) {
        val b = file.readBytes()
        var p = 0
        while (p + 46 <= b.size) {
            if (b[p] == 0x50.toByte() && b[p + 1] == 0x4b.toByte() && b[p + 2] == 1.toByte() && b[p + 3] == 2.toByte()) {
                val n = (b[p + 28].toInt() and 0xff) or ((b[p + 29].toInt() and 0xff) shl 8)
                val name = String(b, p + 46, n)
                if (name in names) {
                    b[p + 5] = 3 // made by unix
                    val mode = 0xa1ff // lrwxrwxrwx
                    b[p + 38] = 0; b[p + 39] = 0; b[p + 40] = (mode and 0xff).toByte(); b[p + 41] = (mode shr 8).toByte()
                }
                p += 46 + n
            } else p++
        }
        file.writeBytes(b)
    }

    private fun prepare(zip: File, sha: String? = null) = BlobferryPackage.prepare(layout, zip.inputStream(), sha)

    private fun rejected(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: BlobferryException) {
            assertEquals(e.message, code, e.code)
        }
        assertTrue("staging left behind", layout.store.listFiles().orEmpty().none { it.name.startsWith("staging-") || it.name.startsWith("incoming-") })
    }

    private fun live(rel: String) = File(layout.rootfs, rel)

    @Test fun goodPackageVerifiesAndInstalls() {
        val f = files("1.0.0")
        val zip = zip("1.0.0", f)
        val p = prepare(zip, sha256(zip.readBytes()))
        assertEquals("1.0.0", p.version)
        assertEquals("a".repeat(40), p.commit)
        assertFalse(p.dirty)
        assertEquals(md5(zip.readBytes()), p.zipMd5)
        val s = BlobferryPackage.install(layout, host, p, BlobferryPackage.SOURCE_FILE)
        assertEquals("file", s.source)
        assertEquals("1.0.0", s.version)
        assertEquals("bundled", s.previous)
        assertTrue(s.userMarker)
        assertEquals(emptyList<String>(), s.changed)
        for (rel in BlobferryPackage.REQUIRED) assertTrue(rel, live(rel).isFile)
        // the libhybris-common.so.1 link became a copy of its target, the stray .so link is not installed
        assertTrue(live("usr/lib/hybris/libhybris-common.so.1").readBytes().contentEquals(f.getValue("rootfs/usr/lib/hybris/libhybris-common.so.1.0.0")))
        assertFalse(live("usr/lib/hybris/libhybris-common.so").exists())
        assertEquals("1.0.0", live(BlobferryPackage.VERSION_FILE).readText().trim())
        assertTrue(BlobferryPackage.handsOff(layout.rootfs))
        assertTrue(File(layout.slot, ".user").isFile)
        assertTrue(File(layout.slot, "libvulkan_mali_bridge.so").readBytes().contentEquals(f.getValue("rootfs/usr/lib/mali_bridge/libvulkan_mali_bridge.so")))
        val meta = JSONObject(File(layout.slot, "meta.json").readText())
        assertEquals("Blobferry 1.0.0", meta.getString("driverVersion"))
        assertEquals("file", meta.getString("source"))
        assertFalse(meta.getBoolean("bundled"))
        assertTrue(JSONObject(File(layout.slot, "icd.json").readText()).getJSONObject("ICD").getString("library_path").endsWith("slot/libvulkan_mali_bridge.so"))
        assertFalse(File(layout.store, "applying").exists())
        assertTrue(File(layout.store, "previous/BUNDLED").isFile)
    }

    @Test fun plainHybrisFileAndDirtyBuild() {
        val f = files("abc1234-dirty", hybrisLink = false)
        f["SOURCE.txt"] = "Blobferry abc1234-dirty\nSource: https://github.com/zolotvok/blobferry commit ${"b".repeat(40)} (built from a dirty tree)\n".toByteArray()
        val p = prepare(zip("abc1234-dirty", f, links = emptyMap()))
        assertTrue(p.dirty)
        assertEquals("b".repeat(40), p.commit)
    }

    @Test fun wrongMd5IsRejected() {
        val f = files("1.0.0")
        val sums = md5sums(f)
        f["rootfs/usr/lib/mali_bridge/libVkLayer_droiddeck_mali_compat.so"] = elf("tampered")
        rejected("BAD_CHECKSUM") { prepare(zip("1.0.0", f, sums)) }
    }

    @Test fun fileOutsideMd5sumsIsRejected() {
        val f = files("1.0.0")
        val sums = md5sums(f)
        f["rootfs/usr/lib/mali_bridge/extra.so"] = elf("smuggled")
        rejected("BAD_CHECKSUM") { prepare(zip("1.0.0", f, sums)) }
    }

    @Test fun missingMd5sumsOrRequiredFileIsRejected() {
        rejected("BAD_STRUCTURE") { prepare(zip("1.0.0", files("1.0.0"), sums = null)) }
        val f = files("1.0.0").apply { remove("rootfs/usr/lib/hybris/libhybris/linker/q.so") }
        rejected("BAD_STRUCTURE") { prepare(zip("1.0.0", f)) }
    }

    @Test fun zipSlipAndSecondTopFolderAreRejected() {
        rejected("UNSAFE_PATH") { prepare(zip("1.0.0", files("1.0.0"), extraEntries = mapOf("blobferry-1.0.0/../../evil" to "x".toByteArray()))) }
        rejected("NOT_A_PACKAGE") { prepare(zip("1.0.0", files("1.0.0"), extraEntries = mapOf("other/file" to "x".toByteArray()))) }
        rejected("NOT_A_PACKAGE") { prepare(zip("1.0.0", files("1.0.0"), top = "Mali_Bridge")) }
    }

    @Test fun writingOutsideTheBridgeFoldersIsRejected() {
        val f = files("1.0.0").apply { put("rootfs/usr/local/bin/droiddeck-session", "#!/bin/sh".toByteArray()) }
        rejected("UNSAFE_PATH") { prepare(zip("1.0.0", f)) }
    }

    @Test fun otherSymlinksAreRejected() {
        val f = files("1.0.0")
        rejected("UNSAFE_PATH") {
            prepare(zip("1.0.0", f, links = mapOf(
                "rootfs/usr/lib/hybris/libhybris-common.so.1" to "libhybris-common.so.1.0.0",
                "rootfs/usr/lib/mali_bridge/evil.so" to "../../../../etc/passwd",
            )))
        }
        rejected("UNSAFE_PATH") {
            prepare(zip("1.0.0", f, links = mapOf("rootfs/usr/lib/hybris/libhybris-common.so.1" to "../../../etc/passwd")))
        }
    }

    @Test fun wrongArchitectureIsRejected() {
        val f = files("1.0.0").apply { put("rootfs/usr/lib/mali_bridge/libVkLayer_window_system_integration.so", elf("x86", machine = 62)) }
        rejected("BAD_ARCH") { prepare(zip("1.0.0", f)) }
    }

    @Test fun badSourceIsRejected() {
        val f = files("1.0.0").apply { put("SOURCE.txt", "Blobferry 1.0.1\nSource: https://github.com/zolotvok/blobferry commit ${"a".repeat(40)}\n".toByteArray()) }
        rejected("BAD_SOURCE") { prepare(zip("1.0.0", f)) }
        val g = files("1.0.0").apply { put("SOURCE.txt", "Blobferry 1.0.0\nSource: https://github.com/someone/else commit ${"a".repeat(40)}\n".toByteArray()) }
        rejected("BAD_SOURCE") { prepare(zip("1.0.0", g)) }
    }

    @Test fun innerShimMustMatch() {
        val f = files("1.0.0").apply { put("Mali_Bridge.zip", innerZip(elf("other shim"))) }
        rejected("BAD_CHECKSUM") { prepare(zip("1.0.0", f)) }
    }

    @Test fun sha256MismatchIsRejected() {
        rejected("BAD_CHECKSUM") { prepare(zip("1.0.0", files("1.0.0")), "0".repeat(64)) }
    }

    @Test fun notAZipIsRejected() {
        val junk = File(tmp, "junk.zip").apply { writeText("not a zip at all, just text") }
        rejected("NOT_A_PACKAGE") { prepare(junk) }
    }

    @Test fun runningSessionBlocksTheSwitch() {
        host.running = true
        rejected("SESSION_RUNNING") { BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file") }
        assertFalse(live(BlobferryPackage.SHIM).exists())
        assertFalse(File(layout.store, "active").exists())
        try { BlobferryPackage.reset(layout, host); fail() } catch (e: BlobferryException) { assertEquals("SESSION_RUNNING", e.code) }
    }

    @Test fun rollbackSwapsAndGoesForwardAgain() {
        BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file")
        val b = files("1.1.0")
        BlobferryPackage.install(layout, host, prepare(zip("1.1.0", b)), "adb")
        assertEquals("1.0.0", BlobferryPackage.status(layout, host).previous)
        val back = BlobferryPackage.rollback(layout, host)
        assertEquals("1.0.0", back.version)
        assertEquals("1.1.0", back.previous)
        assertEquals("1.0.0", live(BlobferryPackage.VERSION_FILE).readText().trim())
        assertEquals(emptyList<String>(), back.changed)
        val forward = BlobferryPackage.rollback(layout, host)
        assertEquals("1.1.0", forward.version)
        assertEquals("adb", forward.source)
        assertTrue(live(BlobferryPackage.SHIM).readBytes().contentEquals(b.getValue("rootfs/usr/lib/mali_bridge/libvulkan_mali_bridge.so")))
    }

    @Test fun rollbackToBundledAndForward() {
        BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file")
        val s = BlobferryPackage.rollback(layout, host)
        assertEquals("bundled", s.source)
        assertEquals("1.0.0-rc1", s.version)
        assertEquals("1.0.0", s.previous)
        assertEquals(1, host.restores)
        assertFalse(BlobferryPackage.handsOff(layout.rootfs))
        assertFalse(File(layout.slot, ".user").exists())
        // the regular libhybris-common.so.1.0.0 of the package is not a bundled file: it goes
        assertFalse(live("usr/lib/hybris/libhybris-common.so.1.0.0").exists())
        assertEquals("1.0.0", BlobferryPackage.rollback(layout, host).version)
        assertTrue(BlobferryPackage.handsOff(layout.rootfs))
    }

    @Test fun noPreviousWithoutAnInstall() {
        try { BlobferryPackage.rollback(layout, host); fail() } catch (e: BlobferryException) { assertEquals("NO_PREVIOUS", e.code) }
    }

    @Test fun resetDropsEverythingAndTheDevMarker() {
        BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file")
        BlobferryPackage.install(layout, host, prepare(zip("1.1.0", files("1.1.0"))), "file")
        File(layout.slot, ".dev").writeText("")
        val s = BlobferryPackage.reset(layout, host)
        assertEquals("bundled", s.source)
        assertNull(s.previous)
        assertFalse(s.devMarker)
        assertFalse(File(layout.store, "active").exists())
        assertFalse(File(layout.store, "previous").exists())
        assertFalse(File(layout.slot, ".user").exists())
        assertEquals(1, host.restores)
    }

    @Test fun resetNeedsABundledPackage() {
        host.bundledPackage = null
        try { BlobferryPackage.reset(layout, host); fail() } catch (e: BlobferryException) { assertEquals("NO_BUNDLED", e.code) }
    }

    @Test fun installReplacesAHandDeployedBuild() {
        live("usr/lib/mali_bridge").mkdirs()
        live("usr/lib/mali_bridge/.dev").writeText("")
        layout.slot.mkdirs()
        File(layout.slot, ".dev").writeText("")
        live(BlobferryPackage.VERSION_FILE).writeText("hand\n")
        assertEquals("dev", BlobferryPackage.status(layout, host).source)
        val s = BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "adb")
        assertFalse(s.devMarker)
        assertTrue(s.userMarker)
        assertFalse(live("usr/lib/mali_bridge/.dev").exists())
        assertFalse(File(layout.slot, ".dev").exists())
    }

    @Test fun aHandDeployedTreeNamesItsFilesAndHasNoCommit() {
        // rc2 smoke B1: compat de6e975d hand-deployed with .dev over the bundled 1.0.0-rc1 (a28a42d)
        val bundledFiles = BlobferryPackage.BUNDLED_FILES.map { it.second }.associateWith { "bundled-${it.substringAfterLast('/')}".toByteArray() }
        bundledFiles.forEach { (rel, bytes) -> live(rel).apply { parentFile!!.mkdirs() }.writeBytes(bytes) }
        host.bundledPackage = BlobferryPackage.Bundled("1.0.0-rc1", "a28a42d6" + "0".repeat(32), "d1fd242b", bundledFiles.mapValues { md5(it.value) })
        live("usr/lib/mali_bridge/.dev").writeText("")
        val same = BlobferryPackage.status(layout, host)
        assertEquals("dev", same.source)
        assertEquals(emptyList<String>(), same.devFiles)
        assertEquals("a28a42d6" + "0".repeat(32), same.commit)  // byte for byte the bundled build
        assertTrue(same.commitLabel, same.commitLabel.startsWith("a28a42d (dev marker"))
        live("usr/lib/mali_bridge/libVkLayer_droiddeck_mali_compat.so").writeBytes("hand".toByteArray())
        val s = BlobferryPackage.status(layout, host)
        assertEquals("", s.commit)
        assertEquals(BlobferryPackage.DEV_NO_COMMIT, s.commitLabel)
        assertEquals(listOf("usr/lib/mali_bridge/libVkLayer_droiddeck_mali_compat.so"), s.devFiles)
        assertEquals("a28a42d6" + "0".repeat(32), s.baseCommit)
        assertEquals(md5("hand".toByteArray()), s.files["usr/lib/mali_bridge/libVkLayer_droiddeck_mali_compat.so"])
        val json = s.json()
        assertEquals("dev build (no commit)", json.getString("commitLabel"))
        assertEquals(1, json.getJSONArray("devFiles").length())
        assertEquals(emptyList<String>(), s.changed)
        // without BUNDLE.json nothing can be compared
        host.bundledPackage = BlobferryPackage.Bundled("1.0.0-rc1")
        assertNull(BlobferryPackage.status(layout, host).devFiles)
        assertEquals(BlobferryPackage.DEV_NO_COMMIT, BlobferryPackage.status(layout, host).commitLabel)
    }

    @Test fun bundledAndImportedLabelsAreTheShortCommit() {
        host.bundledPackage = BlobferryPackage.Bundled("1.0.0-rc1", "a28a42d6" + "0".repeat(32))
        assertEquals("a28a42d", BlobferryPackage.status(layout, host).commitLabel)
        val s = BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "adb")
        assertEquals("aaaaaaa", s.commitLabel)
        assertNull(s.devFiles)
    }

    @Test fun recoverFinishesAnInterruptedSwitch() {
        BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file")
        // a crash in the middle: journal written, one live file still from something else
        File(layout.store, "applying").writeText("sync\n")
        live(BlobferryPackage.SHIM).writeBytes(elf("half-written"))
        assertEquals(listOf(BlobferryPackage.SHIM), BlobferryPackage.status(layout, host).changed)
        assertTrue(BlobferryPackage.recover(layout, host))
        assertFalse(File(layout.store, "applying").exists())
        assertEquals(emptyList<String>(), BlobferryPackage.status(layout, host).changed)
        assertFalse(BlobferryPackage.recover(layout, host))
    }

    @Test fun aReplacedSlotShimShowsAsChanged() {
        BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file")
        File(layout.slot, "libvulkan_mali_bridge.so").writeBytes(elf("bundled shim"))
        assertEquals(listOf("slot/libvulkan_mali_bridge.so"), BlobferryPackage.status(layout, host).changed)
    }

    @Test fun recoverWithoutActiveGoesBundled() {
        BlobferryPackage.install(layout, host, prepare(zip("1.0.0", files("1.0.0"))), "file")
        File(layout.store, "active").deleteRecursively()
        File(layout.store, "applying").writeText("sync\n")
        assertTrue(BlobferryPackage.recover(layout, host))
        assertEquals(1, host.restores)
        assertFalse(BlobferryPackage.handsOff(layout.rootfs))
    }

    @Test fun bundledListMatchesWhatStageUsedToStage() {
        assertEquals(11, BlobferryPackage.BUNDLED_FILES.size)
        assertEquals(10, BlobferryPackage.REQUIRED.size)
        assertTrue(BlobferryPackage.BUNDLED_FILES.contains("usr/lib/mali_bridge/VkLayer_window_system_integration.json" to
            "usr/share/vulkan/implicit_layer.d/VkLayer_window_system_integration.json"))
    }

    @Test fun sha256SidecarParsing() {
        val h = "AB".repeat(32)
        assertEquals(h.lowercase(), BlobferryPackage.parseSha256File("$h  blobferry-1.0.0.zip\n"))
        assertNull(BlobferryPackage.parseSha256File("not a hash"))
    }

    /** The real package.sh output, when one is given: BLOBFERRY_ZIP=../bridge/build/release/blobferry-1.0.0-rc1.zip */
    @Test fun realPackageVerifies() {
        val path = System.getenv("BLOBFERRY_ZIP")
        assumeTrue(path != null && File(path).isFile)
        val zip = File(path!!)
        val p = prepare(zip)
        assertTrue(p.version.isNotEmpty())
        assertEquals(40, p.commit.length)
        val s = BlobferryPackage.install(layout, host, p, "file")
        assertEquals(emptyList<String>(), s.changed)
        for (rel in BlobferryPackage.REQUIRED) assertTrue(rel, live(rel).isFile)
    }

    @Test
    fun dxrCapabilityReadsThePackageFileAndIsUnknownWithoutIt() {
        val root = Files.createTempDirectory("caps").toFile()
        try {
            assertEquals(BlobferryPackage.Capability.UNKNOWN, BlobferryPackage.dxrCapability(root))
            val file = File(root, BlobferryPackage.CAPABILITIES).apply { parentFile!!.mkdirs() }
            file.writeText("""{"rt_pipeline": "not_implemented", "dxr": "not_implemented"}""")
            assertEquals(BlobferryPackage.Capability.NOT_IMPLEMENTED, BlobferryPackage.dxrCapability(root))
            file.writeText("""{"capabilities": {"dxr": "supported"}}""")
            assertEquals(BlobferryPackage.Capability.SUPPORTED, BlobferryPackage.dxrCapability(root))
            file.writeText("""{"rt_pipeline": "supported"}""")
            assertEquals(BlobferryPackage.Capability.SUPPORTED, BlobferryPackage.dxrCapability(root))
            file.writeText("not json")
            assertEquals(BlobferryPackage.Capability.UNKNOWN, BlobferryPackage.dxrCapability(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
