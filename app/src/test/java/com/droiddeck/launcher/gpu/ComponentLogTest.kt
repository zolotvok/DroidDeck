package com.droiddeck.launcher.gpu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentLogTest {
    private lateinit var tmp: File
    private lateinit var log: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("complog").toFile()
        log = ComponentLog.forStore(File(tmp, "panvk"))
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test fun oneTsvLinePerEventNextToTheStores() {
        assertEquals(File(tmp, "components/install.log").absolutePath, log.absolutePath)
        ComponentLog.append(log, "panvk", ComponentLog.INSTALL, "v1", "v2", "a".repeat(64), "adb", ComponentLog.OK, now = 0)
        ComponentLog.append(log, "blobferry", ComponentLog.REFUSED, null, "", null, null, "BAD_CHECKSUM", now = 1000)
        val lines = log.readLines()
        assertEquals(2, lines.size)
        assertEquals("1970-01-01T00:00:00Z\tpanvk\tinstall\tv1\tv2\t${"a".repeat(64)}\tadb\tok", lines[0])
        assertEquals(listOf("1970-01-01T00:00:01Z", "blobferry", "refused", "-", "-", "-", "-", "BAD_CHECKSUM"), lines[1].split('\t'))
        val j = ComponentLog.json(ComponentLog.tail(log, 1).single())
        assertEquals("BAD_CHECKSUM", j.getString("result"))
        assertEquals("refused", j.getString("op"))
    }

    @Test fun tabsAndNewlinesCannotForgeFieldsOrLines() {
        ComponentLog.append(log, "panvk", ComponentLog.INSTALL, "v1\tforged", "v2\nfake\tline", null, "adb\r", "ok")
        val lines = log.readLines()
        assertEquals(1, lines.size)
        assertEquals(8, lines[0].split('\t').size)
        assertEquals("v2 fake line", lines[0].split('\t')[4])
        assertEquals(300, ComponentLog.clean("x".repeat(1000)).length)
    }

    @Test fun rotatesIntoOneOlderFileAndTailReadsAcrossIt() {
        val big = "y".repeat(250)
        repeat(1500) { ComponentLog.append(log, "panvk", ComponentLog.INSTALL, "v$it", big, null, "adb", "ok") }
        val rotated = File(log.parentFile, "install.log.1")
        assertTrue(rotated.isFile)
        assertTrue(log.length() <= ComponentLog.MAX_BYTES)
        assertTrue(rotated.length() <= ComponentLog.MAX_BYTES)
        assertFalse(File(log.parentFile, "install.log.1.1").exists())
        val tail = ComponentLog.tail(log, 2000)
        assertTrue(tail.size > log.readLines().size)
        assertEquals("v1499", tail.last().split('\t')[3])
    }

    @Test fun neverThrows() {
        File(tmp, "components").writeText("a file where the folder should be")
        ComponentLog.append(log, "panvk", ComponentLog.INSTALL, null, null, null, null, "ok")
        assertEquals(emptyList<String>(), ComponentLog.tail(log, 5))
    }
}
