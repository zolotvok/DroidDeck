package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/** The summary of the 2026-11-20 18:08 report (Epic Roller Coasters, SteamVR dying on PanVK), from its sanitized fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReportVrSummaryTest {
    private lateinit var app: Context
    private lateinit var logs: File
    private lateinit var root: File
    private val zone = TimeZone.getDefault()
    private val fixture = File(javaClass.classLoader!!.getResource("fixtures/report-1808/README.txt")!!.toURI()).parentFile!!

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        app = RuntimeEnvironment.getApplication()
        logs = LinuxRuntime.logDir(app).apply { deleteRecursively(); mkdirs() }
        root = LinuxRuntime.rootDir(app)
        File(root, "root").deleteRecursively()
        // the Witcher 3 Proton log of 14:11, four hours before the session
        File(fixture, "proton/steam-292030.log").copyTo(File(root, "root/steam-292030.log"), overwrite = true)
            .setLastModified(1_795_180_278_000L)
        val folder = File(logs, "2026-11-20-23-steam").apply { mkdirs() }
        File(fixture, "session").listFiles()!!.forEach { it.copyTo(File(folder, it.name), overwrite = true) }
        File(folder, "steam").mkdirs()
        File(fixture, "steam-logs").listFiles()!!.forEach { it.copyTo(File(folder, "steam/${it.name}"), overwrite = true) }
        File(fixture, "steamvr-state/start.log").copyTo(File(app.filesDir, ".wayland-rt/droiddeck-steamvr/start.log"), overwrite = true)
        app.getSharedPreferences("report", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        TimeZone.setDefault(zone)
        logs.deleteRecursively()
        File(root, "root").deleteRecursively()
        File(app.filesDir, ".wayland-rt").deleteRecursively()
        ReportBundle.shareDir(app).deleteRecursively()
    }

    @Test fun theSummaryNamesSteamVrAndTheVrGameThatNeverStarted() {
        val plan = ReportBundle.collect(app)
        val summary = plan.summaryText

        assertTrue(summary, summary.contains("- SteamVR failed: its compositor could not create its Vulkan device: " +
            "`Fri Nov 20 2026 18:04:15.434406 - [Error] - Failed to create VkDevice.`"))
        assertTrue(summary, summary.contains("- SteamVR failed: its server quit because the compositor exited"))
        assertTrue(summary, summary.contains("- SteamVR failed: it could not start (VR_Init error)"))
        assertTrue(summary, summary.contains("- SteamVR failed: it exited with an error: `[2026-11-20 18:04:16] AppID 250820 no longer tracking PID 8178, exit code -1`"))
        assertTrue(summary, summary.contains("- The launch of app (787790) was requested, but the game never started: Steam holds a VR-only " +
            "game until SteamVR runs, and SteamVR failed (above)."))
        assertTrue(summary, summary.contains("| Game | app (787790) |"))
        assertTrue(summary, summary.contains("the requested launch of 787790 never started"))
        assertTrue(summary, summary.contains("Proton log none for 787790 in this session"))
        assertFalse("a stale statistics line is no cause", summary.contains("sync-diag"))
        assertFalse(summary.contains("Vulkan/driver error"))
        assertFalse(summary.lineSequence().any { it.startsWith("| Game |") && it.contains("292030") })
        System.getenv("REPORT_SUMMARY_OUT")?.let { File(it).writeText(summary) }
        assertFalse(plan.info.vulkanError)
        assertTrue(plan.parts.any { it.path == "steamvr/start.log" })
    }

    @Test fun aStatisticsLineIsNoVulkanError() {
        assertNull(ReportSummary.vulkanErrorLine(sequenceOf(
            "mali-compat: sync-diag 10.0s 14:11:18 epoch 1795180278.377: fb 3 si 0 k4 0 | submits 1629, idled 0, timeouts 0, fails 0")))
        assertEquals("mali-compat: vkCreateDevice failed", ReportSummary.vulkanErrorLine(sequenceOf("mali-compat: vkCreateDevice failed")))
    }

    @Test fun theLaunchAskedForLastIsTheReportsGame() {
        val vr = listOf("droiddeck-game-env: game driver: x", "droiddeck-steam-compat: preparing SteamVR for library app(s): 611670, 787790")
        assertEquals("787790", ReportCause.requestedLaunch(vr, emptyList())!!.appId)
        assertTrue(ReportCause.requestedLaunch(vr, emptyList())!!.vrHeld)
        assertNull("a game-env launch after it is the summary's launch", ReportCause.requestedLaunch(vr + "droiddeck-game-env: game driver: y", emptyList()))
        val steam = listOf("[2026-11-20 18:04:14] GameAction [AppID 730, ActionID 3] : LaunchApp changed task to ProcessingInstallScript with \"\"")
        assertEquals("730", ReportCause.requestedLaunch(emptyList(), steam)!!.appId)
        assertNull("a session with a game-env launch keeps it", ReportCause.requestedLaunch(listOf("droiddeck-game-env: game driver: y"), steam))
        val launchApp = ReportCause.requestedLaunch(emptyList(), steam)!!
        assertTrue(ReportCause.started(launchApp, emptyList(), steam + "[2026-11-20 18:04:15] Game process added : AppID 730 \"x\", ProcID 1, IP 0.0.0.0:0"))
        assertFalse(ReportCause.started(launchApp, emptyList(), steam + "Game process added : AppID 7300 \"x\""))
        assertEquals("SteamVR's own id is no game", "787790",
            ReportCause.requestedLaunch(listOf("droiddeck-steam-compat: preparing SteamVR for library app(s): 787790, 250820"), emptyList())!!.appId)
        val earlier = listOf("Adding process 1 for gameID 787790", "droiddeck-steam-compat: preparing SteamVR for library app(s): 787790")
        assertFalse("a run before the request does not count", ReportCause.started(ReportCause.requestedLaunch(earlier, emptyList())!!, earlier, emptyList()))
        assertTrue(ReportCause.started(ReportCause.requestedLaunch(earlier, emptyList())!!, earlier + "Adding process 2 for gameID 787790", emptyList()))
        assertNull(ReportCause.requestedLaunch(emptyList(), listOf("[2026-11-20 18:04:14] GameAction [AppID 250820, ActionID 3] : LaunchApp x")))
    }

    @Test fun aSteamVrExitAloneIsNoFailure() {
        val exit = "[2026-11-20 18:30:00] AppID 250820 no longer tracking PID 9000, exit code 15"
        assertEquals(emptyList<String>(), ReportCause.steamVrBullets(listOf(exit)))
        assertEquals(2, ReportCause.steamVrBullets(listOf("Fri Nov 20 2026 18:29:59.000000 - [Error] - Failed to create VkDevice.", exit)).size)
    }

    @Test fun steamLinesOlderThanTheSessionAreLeftOut() {
        val since = ReportCause.steamLineTime("[2026-11-20 17:59:13] x", TimeZone.getDefault())!!
        val lines = listOf("untimed head", "[2026-11-20 14:11:18] old", "old continuation", "[2026-11-20 18:04:02] new", "new continuation",
            "Fri Nov 20 2026 18:04:15.434406 - [Error] - Failed to create VkDevice.")
        assertEquals(lines.drop(3), ReportCause.sinceSession(lines, since, fresh = false, zone = TimeZone.getDefault()))
        assertEquals(listOf("untimed head") + lines.drop(3), ReportCause.sinceSession(lines, since, fresh = true, zone = TimeZone.getDefault()))
    }
}
