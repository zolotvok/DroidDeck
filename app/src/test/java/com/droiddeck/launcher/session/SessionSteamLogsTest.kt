package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.Writer

class SessionSteamLogsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(files: List<File>) = files.map { it.name }.sorted()

    @Test fun onlyWhatTheSessionWroteIsCollected() {
        val logs = tmp.newFolder("logs")
        val session = tmp.newFolder("session")
        val out = tmp.newFolder("out")
        File(logs, "console_log.txt").writeText("old line\n")
        File(logs, "cef_log.txt").writeText("old cef\n")
        File(logs, "cef_log.previous.txt").writeText("older cef\n")
        File(logs, "gameprocess_log.txt").writeText("a long old line\n")
        File(logs, "shader_log.txt").writeText("nothing new\n")
        SessionArtifacts.markSteamLogs(session, logs)

        File(logs, "console_log.txt").appendText("new line\n")
        File(logs, "cef_log.previous.txt").delete()
        File(logs, "cef_log.txt").renameTo(File(logs, "cef_log.previous.txt"))
        File(logs, "cef_log.txt").writeText("this session's cef\n")
        File(logs, "gameprocess_log.txt").writeText("short\n")
        File(logs, "vrserver.txt").writeText("created this session\n")
        File(logs, "cloud_log.txt").writeText("off the allowlist\n")

        val copied = SessionArtifacts.copySessionLogs(logs, session, out)
        assertEquals(listOf("cef_log.txt", "console_log.txt", "gameprocess_log.txt", "vrserver.txt"), names(copied))
        assertEquals("new line\n", File(out, "console_log.txt").readText())
        assertEquals("this session's cef\n", File(out, "cef_log.txt").readText())
        assertEquals("short\n", File(out, "gameprocess_log.txt").readText())
        assertFalse(File(out, "cef_log.previous.txt").exists())
        assertFalse(File(out, "shader_log.txt").exists())
        assertFalse(File(out, "cloud_log.txt").exists())
    }

    @Test fun aLineCutByTheMarkIsLeftOut() {
        val logs = tmp.newFolder("logs")
        val session = tmp.newFolder("session")
        val out = tmp.newFolder("out")
        File(logs, "webhelper.txt").writeText("old\nGET /x?sessionid=")
        SessionArtifacts.markSteamLogs(session, logs)
        File(logs, "webhelper.txt").appendText("abcdef123456 200\nnext line\n")
        SessionArtifacts.copySessionLogs(logs, session, out)
        assertEquals("next line\n", File(out, "webhelper.txt").readText())
    }

    @Test fun withoutMarksEveryLogUnderTheCapIsCollectedAndRedacted() {
        val logs = tmp.newFolder("logs")
        val out = tmp.newFolder("out")
        File(logs, "webhelper.txt").writeText("GET /x?sessionid=abcdef123456 200\n")
        File(logs, "cef_log.txt").outputStream().use { it.write(ByteArray(8 * 1024 * 1024)) }
        val copied = SessionArtifacts.copySessionLogs(logs, tmp.newFolder("session"), out)
        assertEquals(listOf("webhelper.txt"), names(copied))
        assertFalse(File(out, "webhelper.txt").readText().contains("abcdef123456"))
    }

    @Test fun aCopyFailurePreservesThePreviousSnapshotAndRemovesPartialStaging() {
        val logs = tmp.newFolder("logs")
        val session = tmp.newFolder("session")
        val out = tmp.newFolder("out")
        File(logs, "console_log.txt").writeText("new complete line\n")
        val snapshot = File(out, "console_log.txt").apply { writeText("previous valid snapshot\n") }
        lateinit var staging: File

        val copied = SessionArtifacts.copySessionLogs(logs, session, out) { target ->
            staging = target
            val delegate = target.bufferedWriter()
            object : Writer() {
                private var failed = false

                override fun write(buffer: CharArray, offset: Int, length: Int) {
                    if (!failed) {
                        delegate.write(buffer, offset, minOf(length, 4))
                        delegate.flush()
                        failed = true
                    }
                    throw IOException("injected writer failure")
                }

                override fun flush() = delegate.flush()
                override fun close() = delegate.close()
            }
        }

        assertTrue(copied.isEmpty())
        assertEquals("previous valid snapshot\n", snapshot.readText())
        assertFalse(staging.exists())
    }
}
