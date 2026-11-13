package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.ComponentLog
import com.droiddeck.launcher.gpu.GuestToolsPackage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SessionFiles.stage() with the real APK assets: an installed guest-tools agent wins over the APK's (restaged after it). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionFilesGuestToolsTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("rootfs").toFile()
        clean()
    }

    @After fun tearDown() {
        clean()
        root.deleteRecursively()
    }

    private fun clean() {
        File(context.filesDir, GuestToolsPackage.STORE).deleteRecursively()
        File(context.filesDir, ComponentLog.DIR).deleteRecursively()
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun bundledAgent() = context.assets.open("linuxfs/${GuestToolsPackage.AGENT}").use { it.readBytes() }

    /** A package whose agent is the APK's own with a newer AGENT_RELEASE. */
    private fun pkg(version: String): Pair<ByteArray, ByteArray> {
        val agent = bundledAgent().toString(Charsets.UTF_8).replace(Regex("""(?m)^AGENT_RELEASE = "[^"]*""""), "AGENT_RELEASE = \"$version\"").toByteArray()
        val files = linkedMapOf("rootfs/${GuestToolsPackage.AGENT}" to agent, "LICENSE" to "GPL-3".toByteArray(), "SOURCE" to "test".toByteArray())
        val list = JSONObject()
        for ((rel, data) in files) list.put(rel, JSONObject().put("sha256", sha256(data)).put("md5", md5(data)))
        val meta = JSONObject().put("component", "guest-tools").put("version", version).put("date", "2026-11-13").put("changelog", "t")
            .put("source", JSONObject().put("repo", "https://github.com/zolotvok/DroidDeck").put("commit", "a".repeat(40)))
            .put("files", list).put("minApp", 20).toString().toByteArray()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((name, data) in files + mapOf("meta.json" to meta)) { z.putNextEntry(ZipEntry("guest-tools-$version/$name")); z.write(data); z.closeEntry() }
            }
        }.toByteArray()
        return agent to zip
    }

    @Test fun stageRestagesTheActiveAgentAfterTheApkAssets() {
        val bundled = bundledAgent()
        SessionFiles.stage(context, root, startsSession = true)
        val live = File(root, GuestToolsPackage.AGENT)
        assertEquals(md5(bundled), md5(live.readBytes()))
        assertEquals(md5(bundled), md5(File(root, "${GuestToolsPackage.BUNDLED_COPY}/${GuestToolsPackage.AGENT}").readBytes()))
        assertTrue(File(root, "usr/local/bin/droiddeck-guest-tools-check").canExecute())
        assertFalse(File(root, GuestToolsPackage.ACTIVE_JSON).exists())

        val (agent, zip) = pkg("2099.01.01.1")
        GuestToolsPackage.install(context, GuestToolsPackage.prepare(context, zip.inputStream()), "adb")
        // two session starts: the APK's agent is staged first each time, the installed one ends up live
        repeat(2) {
            SessionFiles.stage(context, root, startsSession = true)
            assertEquals(md5(agent), md5(live.readBytes()))
            assertEquals("2099.01.01.1", JSONObject(File(root, GuestToolsPackage.ACTIVE_JSON).readText()).getString("version"))
            assertEquals(md5(bundled), md5(File(root, "${GuestToolsPackage.BUNDLED_COPY}/${GuestToolsPackage.AGENT}").readBytes()))
        }
        GuestToolsPackage.reset(context)
        SessionFiles.stage(context, root, startsSession = true)
        assertEquals(md5(bundled), md5(live.readBytes()))
        assertFalse(File(root, GuestToolsPackage.ACTIVE_JSON).exists())
    }
}
