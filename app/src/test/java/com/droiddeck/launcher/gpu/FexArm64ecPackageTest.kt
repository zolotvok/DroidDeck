package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONArray
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FexArm64ecPackageTest {
    private lateinit var tmp: File
    private lateinit var layout: FexArm64ecPackage.Layout
    private val bundledRows = listOf(FexArm64ecPackage.Row("1".repeat(32), "FEX-2610-101-g1c4f04b", null, "dll/a.dll", "a".repeat(32), "patches/a.patch", "b".repeat(32), null))
    private val host = object : FexArm64ecPackage.Host {
        var running = false
        var hasBundled = true
        var bundledVersion = "2026.11.13.1"
        override val sessionRunning get() = running
        override val bundled get() = if (hasBundled) FexArm64ecPackage.Bundled(bundledVersion, bundledRows, "c".repeat(32)) else null
    }
    private val commit = "2d90092006bf8e48a792aab94515ed01af5af685"

    @Before fun setUp() {
        tmp = Files.createTempDirectory("fex").toFile()
        layout = FexArm64ecPackage.Layout(File(tmp, "files/fex-arm64ec"), File(tmp, "rootfs"))
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun md5(b: ByteArray) = hex(MessageDigest.getInstance("MD5").digest(b))

    /** A minimal PE32+ DLL, machine AMD64, whose load config has a CHPE metadata pointer (ARM64EC), with [tag] appended. */
    private fun pe(tag: String, chpe: Boolean = true, machine: Int = 0x8664, dll: Boolean = true): ByteArray {
        val b = ByteBuffer.allocate(0x600).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte()).putInt(0x3c, 0x40)
        b.put(0x40, 'P'.code.toByte()).put(0x41, 'E'.code.toByte())
        b.putShort(0x44, machine.toShort()).putShort(0x46, 1).putShort(0x54, 0xf0).putShort(0x56, (if (dll) 0x2022 else 0x22).toShort())
        val opt = 0x58
        b.putShort(opt, 0x20b).putLong(opt + 24, 0x180000000L).putInt(opt + 112 + 80, 0x1000).putInt(opt + 112 + 84, 0x138)
        val section = opt + 0xf0
        ".rdata".forEachIndexed { i, c -> b.put(section + i, c.code.toByte()) }
        b.putInt(section + 8, 0x400).putInt(section + 12, 0x1000).putInt(section + 16, 0x400).putInt(section + 20, 0x200)
        b.putInt(0x200, 0x138)
        if (chpe) b.putLong(0x200 + 0xc8, 0x180000000L + 0x1100)
        return b.array() + tag.toByteArray()
    }

    private val stock101 = "1".repeat(32)
    private val stock175 = "2".repeat(32)

    /** The files of a package: two rows (101, 175) plus LICENSE, NOTICE, patches, table.json; [change] edits them before meta.json. */
    private fun files(tag: String = "x", change: (MutableMap<String, ByteArray>, JSONArray) -> Unit = { _, _ -> }): MutableMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        val table = JSONArray()
        for ((version, stock) in listOf("FEX-2610-101-g1c4f04b" to stock101, "FEX-2610-175-geff9f45" to stock175)) {
            val dll = pe("$version $tag")
            val patch = "diff $version".toByteArray()
            out["dll/$version.dll"] = dll
            out["patches/$version.patch"] = patch
            table.put(JSONObject().put("stock_md5", stock).put("fex_version", version).put("patched", "dll/$version.dll")
                .put("patched_md5", md5(dll)).put("patch", "patches/$version.patch").put("patch_md5", md5(patch)))
        }
        out["LICENSE"] = "MIT".toByteArray()
        out["NOTICE"] = "FEX".toByteArray()
        change(out, table)
        out["table.json"] = table.toString(2).toByteArray()
        return out
    }

    private fun meta(files: Map<String, ByteArray>, version: String, change: (JSONObject) -> Unit = {}): ByteArray {
        val list = JSONObject()
        for ((rel, data) in files) list.put(rel, JSONObject().put("sha256", sha256(data)).put("md5", md5(data)))
        return JSONObject().put("component", "fex-arm64ec").put("version", version).put("date", "2026-11-13").put("changelog", "fex table")
            .put("source", JSONObject().put("repo", "https://github.com/zolotvok/blobferry").put("commit", commit))
            .put("files", list).put("minApp", 20).also(change).toString(2).toByteArray()
    }

    private fun zip(version: String, content: Map<String, ByteArray>, top: String = "fex-arm64ec-$version/", metaChange: (JSONObject) -> Unit = {},
                    extra: Map<String, ByteArray> = emptyMap()): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((name, data) in content + mapOf("meta.json" to meta(content, version, metaChange)) + extra) {
                    z.putNextEntry(ZipEntry(top + name)); z.write(data); z.closeEntry()
                }
            }
        }.toByteArray()

    private fun pkg(version: String = "2026.11.13.1", tag: String = version) = zip(version, files(tag))

    private fun prepare(bytes: ByteArray, sha: String? = null) = FexArm64ecPackage.prepare(layout, bytes.inputStream(), sha)

    private fun install(bytes: ByteArray) = FexArm64ecPackage.install(layout, host, prepare(bytes), BlobferryPackage.SOURCE_ADB)

    private fun rejected(code: String, bytes: ByteArray, sha: String? = null) {
        try {
            prepare(bytes, sha)
            fail("expected $code")
        } catch (e: BlobferryException) {
            assertEquals(e.message, code, e.code)
        }
        assertTrue(layout.store.listFiles().orEmpty().none { it.name.startsWith("staging-") || it.name.startsWith("incoming-") })
    }

    private fun refused(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: BlobferryException) {
            assertEquals(e.message, code, e.code)
        }
    }

    private val live get() = File(layout.rootfs, FexArm64ecPackage.ACTIVE_DIR)
    private fun logLines() = ComponentLog.tail(ComponentLog.forStore(layout.store), 100).map { it.split('\t') }

    @Test fun installsAVerifiedTableAndStagesItForTheGuest() {
        val status = install(pkg())
        assertEquals("adb", status.source)
        assertEquals("2026.11.13.1", status.version)
        assertEquals(listOf(stock101, stock175), status.rows.map { it.stockMd5 })
        assertEquals("bundled", status.previous)
        assertEquals("active", status.guestTable)
        val rows = FexArm64ecPackage.parseTable(File(live, "table.json").readText())
        for (row in rows) assertEquals(row.patchedMd5, FexArm64ecPackage.md5(File(live, row.patched)))
        assertTrue(File(live, "NOTICE").isFile && File(live, "patches/FEX-2610-175-geff9f45.patch").isFile)
        val line = logLines().last()
        assertEquals(listOf("fex-arm64ec", "install", "2026.11.13.1 (bundled)", "2026.11.13.1"), line.subList(1, 5))
        assertEquals(listOf("adb", "ok"), line.subList(6, 8))
        val row = InstalledComponents.fexArm64ec(status).json()
        assertEquals("table.json", row.getString("keyFile"))
        assertEquals(2, row.getJSONArray("rows").length())
        assertTrue(row.getBoolean("canRollback"))
    }

    @Test fun refusesWhatTheTableOrMetaDoNotCover() {
        rejected("BAD_META", zip("1", files(), extra = mapOf("dll/extra.txt" to "x".toByteArray())))
        rejected("BAD_TABLE", zip("1", files { _, t -> t.getJSONObject(1).put("stock_md5", stock101) }))
        rejected("BAD_TABLE", zip("1", files { _, t -> t.getJSONObject(1).put("patched", "dll/FEX-2610-101-g1c4f04b.dll") }))
        rejected("BAD_TABLE", zip("1", files { _, t -> t.getJSONObject(0).put("patched", "../x.dll") }))
        rejected("BAD_TABLE", zip("1", files { _, t -> t.getJSONObject(0).put("unknown", "x") }))
        rejected("BAD_TABLE", zip("1", files { _, t -> t.getJSONObject(1).put("stock_md5", t.getJSONObject(0).getString("patched_md5")) }))
        rejected("BAD_TABLE", zip("1", files { f, _ -> f["dll/stray.dll"] = pe("stray") }))
        rejected("BAD_TABLE", zip("1", files { _, t -> t.remove(1); t.remove(0) }))
        rejected("BAD_CHECKSUM", zip("1", files { _, t -> t.getJSONObject(0).put("patched_md5", "f".repeat(32)) }))
        rejected("BAD_CHECKSUM", zip("1", files { _, t -> t.getJSONObject(0).put("patch_md5", "f".repeat(32)) }))
        rejected("BAD_ARCH", zip("1", files { f, t -> f["dll/FEX-2610-101-g1c4f04b.dll"] = pe("FEX-2610-101-g1c4f04b", chpe = false).also { t.getJSONObject(0).put("patched_md5", md5(it)) } }))
        rejected("BAD_ARCH", zip("1", files { f, t -> f["dll/FEX-2610-101-g1c4f04b.dll"] = pe("FEX-2610-101-g1c4f04b", machine = 0xaa64).also { t.getJSONObject(0).put("patched_md5", md5(it)) } }))
        rejected("BAD_ARCH", zip("1", files { f, t -> f["dll/FEX-2610-101-g1c4f04b.dll"] = pe("FEX-2610-101-g1c4f04b", dll = false).also { t.getJSONObject(0).put("patched_md5", md5(it)) } }))
        rejected("BAD_TABLE", zip("1", files { f, t -> f["dll/FEX-2610-101-g1c4f04b.dll"] = pe("FEX-2610-175-geff9f45").also { t.getJSONObject(0).put("patched_md5", md5(it)) } }))
        rejected("BAD_META", zip("1", files(), metaChange = { it.put("component", "panvk") }))
        rejected("BAD_META", zip("1", files(), metaChange = { it.put("version", "2") }))
        rejected("APP_TOO_OLD", zip("1", files(), metaChange = { it.put("minApp", 99) }))
        rejected("BAD_STRUCTURE", zip("1", files().apply { remove("NOTICE") }))
        rejected("NOT_A_PACKAGE", zip("1", files(), top = "panvk-kbase-1/"))
        rejected("UNSAFE_PATH", zip("1", files(), extra = mapOf("dll/../../x" to "x".toByteArray())))
        rejected("BAD_CHECKSUM", pkg(), "0".repeat(64))
        assertTrue(logLines().all { it[2] == "refused" })
        assertFalse(layout.store.resolve("active").exists())
    }

    @Test fun noSwitchWhileASessionRuns() {
        host.running = true
        val prepared = prepare(pkg())
        refused("SESSION_RUNNING") { FexArm64ecPackage.install(layout, host, prepared, "adb") }
        refused("SESSION_RUNNING") { FexArm64ecPackage.rollback(layout, host) }
        refused("SESSION_RUNNING") { FexArm64ecPackage.reset(layout, host) }
        assertFalse(live.exists())
        assertEquals(listOf("refused", "refused", "refused"), logLines().map { it[2] })
        assertEquals("SESSION_RUNNING", logLines().first()[7])
    }

    @Test fun rollbackSwapsAndResetKeepsTheInstalledOneForARollback() {
        install(pkg("2026.11.13.1"))
        install(pkg("2026.11.20.1"))
        assertEquals("2026.11.13.1", FexArm64ecPackage.status(layout, host).previous)
        assertEquals("2026.11.13.1", FexArm64ecPackage.rollback(layout, host).version)
        assertTrue(File(live, "package.json").readText().contains("2026.11.13.1"))
        assertEquals("2026.11.20.1", FexArm64ecPackage.rollback(layout, host).version)
        val reset = FexArm64ecPackage.reset(layout, host)
        assertEquals("bundled", reset.source)
        assertEquals("2026.11.20.1", reset.previous)
        assertFalse(live.exists())
        assertEquals("2026.11.20.1", FexArm64ecPackage.rollback(layout, host).version)
        assertTrue(live.isDirectory)
        assertEquals(listOf("install", "install", "rollback", "rollback", "reset", "rollback"), logLines().map { it[2] })
        host.hasBundled = false
        refused("NO_BUNDLED") { FexArm64ecPackage.reset(layout, host) }
    }

    @Test fun rollbackToTheBundledTableRemovesTheGuestCopy() {
        install(pkg())
        val status = FexArm64ecPackage.rollback(layout, host)
        assertEquals("bundled", status.source)
        assertEquals("2026.11.13.1", status.previous)
        assertFalse(live.exists())
        assertEquals("none", status.guestTable)
        val empty = FexArm64ecPackage.Layout(File(tmp, "other/fex-arm64ec"), File(tmp, "other/rootfs"))
        refused("NO_PREVIOUS") { FexArm64ecPackage.rollback(empty, host) }
    }

    @Test fun restageFinishesAnInterruptedSwitchAndFollowsTheStore() {
        install(pkg())
        // a crash after the store switched but before the guest copy was written
        live.deleteRecursively()
        File(layout.store, ComponentStore.JOURNAL).writeText("sync\n")
        File(layout.store, "new/leftover").apply { parentFile?.mkdirs() }.writeText("x")
        assertEquals("recovered", FexArm64ecPackage.restage(layout, host, emptyList()))
        assertTrue(File(live, "table.json").isFile)
        assertFalse(File(layout.store, ComponentStore.JOURNAL).exists() || File(layout.store, "new").exists())
        assertEquals("restage", logLines().last()[2])
        assertEquals("current", FexArm64ecPackage.restage(layout, host, emptyList()))
        // a guest copy someone changed is put back
        File(live, "table.json").writeText("[]")
        assertEquals("restaged", FexArm64ecPackage.restage(layout, host, emptyList()))
        assertEquals(2, FexArm64ecPackage.parseTable(File(live, "table.json").readText()).size)
    }

    @Test fun restageRemovesTheOldSingleDllAndStaleBundledFiles() {
        val bundled = File(layout.rootfs, FexArm64ecPackage.BUNDLED_DIR)
        val keep = listOf("table.json", "meta.json", "dll/a.dll").map { "${FexArm64ecPackage.BUNDLED_DIR}/$it" }
        for (rel in listOf("libarm64ecfex.dll", "LICENSE", "table.json", "meta.json", "dll/a.dll", "dll/old.dll")) {
            File(bundled, rel).apply { parentFile?.mkdirs() }.writeText(rel)
        }
        FexArm64ecPackage.restage(layout, host, keep)
        assertEquals(listOf("dll/a.dll", "meta.json", "table.json"), bundled.walkTopDown().filter { it.isFile }.map { it.relativeTo(bundled).invariantSeparatorsPath }.sorted().toList())
    }

    @Test fun theGuestsFexEventsMoveIntoTheInstallLog() {
        val guest = File(layout.rootfs, FexArm64ecPackage.GUEST_LOG).apply { parentFile?.mkdirs() }
        guest.writeText(listOf(
            "2026-11-13T12:00:00Z\tfex-arm64ec\tfex-unmatched\tFEX-2611-3-gabcdef0\t-\t-\tguest\tunmatched\tfex-unmatched FEX-2611-3-gabcdef0 ${"9".repeat(32)} (Proton)",
            "2026-11-13T12:00:01Z\tblobferry\tinstall\ta\tb\t-\tguest\tok",
            "2026-11-13T12:00:02Z\tfex-arm64ec\trollback\ta\tb\t-\tguest\tok",
            "not a line",
            "2026-11-13T12:00:03Z\tfex-arm64ec\tfex-apply\t$stock175\t${"a".repeat(32)}\t-\tguest\tok\tProton FEX-2610-175-geff9f45 (bundled table 2026.11.13.1)",
        ).joinToString("\n", postfix = "\n"))
        assertEquals(2, FexArm64ecPackage.ingestGuestLog(layout))
        val lines = logLines()
        assertEquals(listOf("fex-unmatched", "fex-apply"), lines.map { it[2] })
        assertEquals("2026-11-13T12:00:00Z", lines[0][0])
        assertTrue(lines[0][8].startsWith("fex-unmatched FEX-2611-3-gabcdef0 "))
        assertFalse(guest.exists())
        assertEquals(0, FexArm64ecPackage.ingestGuestLog(layout))
    }

    private fun tableText(vararg rows: JSONObject) = JSONArray(rows.toList()).toString()
    private fun row(stock: String, patched: String, dll: String, supersedes: List<String>? = null) = JSONObject()
        .put("stock_md5", stock).put("fex_version", "FEX-2610-101-g1c4f04b").put("patched", "dll/$dll.dll").put("patched_md5", patched)
        .put("patch", "patches/p.patch").put("patch_md5", "b".repeat(32)).also { r -> supersedes?.let { r.put("supersedes", JSONArray(it)) } }

    @Test fun supersedesIsParsedAndEveryMd5HasOneRoleInOneRow() {
        val rows = FexArm64ecPackage.parseTable(tableText(row(stock101, "a".repeat(32), "a", listOf("3".repeat(32), "4".repeat(32))), row(stock175, "5".repeat(32), "b")))
        assertEquals(listOf("3".repeat(32), "4".repeat(32)), rows[0].supersedes)
        assertEquals(emptyList<String>(), rows[1].supersedes)
        assertEquals(2, rows[0].json().getJSONArray("supersedes").length())
        for (bad in listOf(
            tableText(row(stock101, "a".repeat(32), "a", listOf("5".repeat(32))), row(stock175, "5".repeat(32), "b")),
            tableText(row(stock101, "a".repeat(32), "a", listOf(stock175)), row(stock175, "5".repeat(32), "b")),
            tableText(row(stock101, "a".repeat(32), "a", listOf("a".repeat(32)))),
            tableText(row(stock101, "a".repeat(32), "a", listOf(FexArm64ecPackage.EMPTY_MD5))),
            tableText(row(stock101, FexArm64ecPackage.EMPTY_MD5, "a")),
            tableText(row(stock101, "a".repeat(32), "a", emptyList())),
            tableText(row(stock101, "a".repeat(32), "a", listOf("XYZ"))),
            tableText(row(stock101, "a".repeat(32), "a"), row(stock101, "5".repeat(32), "b")),
        )) refused("BAD_TABLE") { FexArm64ecPackage.parseTable(bad) }
    }

    @Test fun aPackageWithSupersedesInstalls() {
        val old = "3".repeat(32)
        val status = install(zip("2026.11.20.1", files { _, table -> table.getJSONObject(1).put("supersedes", JSONArray(listOf(old))) }))
        assertEquals(listOf(emptyList(), listOf(old)), status.rows.map { it.supersedes })
        assertTrue(File(live, "table.json").readText().contains(old))
    }

    @Test fun aNewerBundledTableReplacesAnOlderInstalledOneAtTheStart() {
        install(pkg("2026.11.13.1"))
        assertEquals("current", FexArm64ecPackage.restage(layout, host, emptyList()))
        host.bundledVersion = "2026.11.20.1"
        assertEquals("upgrade-at-start", FexArm64ecPackage.restage(layout, host, emptyList()))
        assertFalse(live.exists())
        val status = FexArm64ecPackage.status(layout, host)
        assertEquals("bundled", status.source)
        assertEquals("2026.11.20.1", status.version)
        assertEquals("2026.11.13.1", status.previous)
        val event = logLines().last()
        assertEquals(listOf("upgrade-at-start", "2026.11.13.1", "2026.11.20.1 (bundled)", "bundled", "ok"), listOf(event[2], event[3], event[4], event[6], event[7]))
        assertEquals("current", FexArm64ecPackage.restage(layout, host, emptyList()))
        // an older table would be hidden again at the next start: install and roll back refuse it
        refused("OLDER_THAN_BUNDLED") { FexArm64ecPackage.rollback(layout, host) }
        refused("OLDER_THAN_BUNDLED") { install(pkg("2026.11.14.1")) }
        assertEquals("refused", logLines().last()[2])
        // equal versions: the installed table wins
        install(pkg("2026.11.20.1", tag = "same version"))
        assertEquals("current", FexArm64ecPackage.restage(layout, host, emptyList()))
        assertTrue(live.isDirectory)
    }

    @Test fun restageRepairsASameLengthChangeOfAGuestDll() {
        install(pkg())
        val dll = File(live, "dll/FEX-2610-101-g1c4f04b.dll")
        val bytes = dll.readBytes()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        dll.writeBytes(bytes)
        assertEquals("restaged", FexArm64ecPackage.restage(layout, host, emptyList()))
        assertEquals(FexArm64ecPackage.parseTable(File(live, "table.json").readText())[0].patchedMd5, md5(dll.readBytes()))
        File(live, "stray").writeText("x")
        assertEquals("restaged", FexArm64ecPackage.restage(layout, host, emptyList()))
        assertFalse(File(live, "stray").exists())
        assertEquals("current", FexArm64ecPackage.restage(layout, host, emptyList()))
    }

    @Test fun theGuestLogIsMergedAsGuestWithCapsAndMalformedLinesDropped() {
        val guest = File(layout.rootfs, FexArm64ecPackage.GUEST_LOG).apply { parentFile?.mkdirs() }
        val ok = "2026-11-13T12:00:00Z\tfex-arm64ec\tfex-apply\ta\tb\t-\tadb\tok\tnote"
        guest.writeText(listOf(
            ok,
            "2026-11-13T12:00:01Z\tfex-arm64ec\tfex-apply\ta\tb\t-\tdownload\tok\t" + "x".repeat(ComponentLog.MAX_INGEST_LINE),
            "2026-11-13T12:00:02Z\tfex-arm64ec\tfex-apply\ta\tb\t-\tguest\tok\tn\textra",
            "12:00\tfex-arm64ec\tfex-apply\ta\tb\t-\tguest\tok",
        ).joinToString("\n", postfix = "\n") + "y".repeat(ComponentLog.MAX_INGEST_BYTES * 2) + "\n" + ok + "\n")
        assertEquals(1, FexArm64ecPackage.ingestGuestLog(layout))
        assertEquals(listOf("guest"), logLines().map { it[6] })
        assertFalse(guest.exists())
        assertFalse(File(guest.parentFile, guest.name + ".ingest").exists())
        // a file the merge cannot read is not kept around for every later start
        File(guest.parentFile, guest.name + ".ingest").mkdirs()
        guest.writeText(ok + "\n")
        assertEquals(0, FexArm64ecPackage.ingestGuestLog(layout))
        assertFalse(File(guest.parentFile, guest.name + ".ingest").exists())
        assertEquals(1, FexArm64ecPackage.ingestGuestLog(layout))
    }

    @Test fun statusWithoutAnInstallIsTheBundledTable() {
        val status = FexArm64ecPackage.status(layout, host)
        assertEquals("bundled", status.source)
        assertEquals("2026.11.13.1", status.version)
        assertNull(status.previous)
        host.hasBundled = false
        assertEquals("none", FexArm64ecPackage.status(layout, host).source)
    }

    @Test fun theRealDllsAreArm64ecWithTheirFexVersions() {
        val release = File("../../release/fex-arm64ec/fex-arm64ec-2026.11.13.1")
        assumeTrue(release.isDirectory)
        val rows = FexArm64ecPackage.parseTable(File(release, "table.json").readText())
        assertEquals(listOf("863695500852d6df6a7b6b971893b4c1" to "a2448b03b9ca32b6a641d687e9773074",
            "cdab6135bbbcd10e284c5ca332583fc0" to "ae8473da3f0976911ef82c0ca6abd96b"), rows.map { it.stockMd5 to it.patchedMd5 })
        for (row in rows) {
            val dll = File(release, row.patched)
            assertTrue(row.patched, FexArm64ecPackage.arm64ecDll(dll))
            assertEquals(row.fexVersion, FexArm64ecPackage.fexVersion(dll))
        }
        val stock = File("../../fex-175-1111/S0/stock-phone.dll")
        if (stock.isFile) assertTrue(FexArm64ecPackage.arm64ecDll(stock))
        val zip = File("../../release/fex-arm64ec/fex-arm64ec-2026.11.13.1.zip")
        if (zip.isFile) assertEquals(2, FexArm64ecPackage.install(layout, host, FexArm64ecPackage.prepare(layout, zip.inputStream()), "adb").rows.size)
    }
}
