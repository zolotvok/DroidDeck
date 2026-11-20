package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Fixtures in resources/report: lines cut from two real report zips (11-20: Yooka-Laylee rc2, DOOM Eternal rc rev 3 F1),
 *  already redacted by the app; the device, kernel and account lines are left out. */
class ReportCauseTest {
    private fun fixture(name: String): List<String> = javaClass.classLoader!!.getResource("report/$name")!!.readText().lines()

    private val zone = TimeZone.getTimeZone("Europe/Berlin")

    private fun at(h: Int, m: Int, s: Int): Long = Calendar.getInstance(zone).apply {
        clear(); set(2026, Calendar.NOVEMBER, 20, h, m, s)
    }.timeInMillis

    private val yookaLaunch get() = ReportSummary.launchLines(fixture("yooka-session.log").asSequence())

    @Test fun yookaKeyLinesPutTheWsiHangFirstAndDropNoiseAndRepeats() {
        val keys = ReportCause.keyLines(fixture("yooka-proton.log"), stopped = true)
        assertEquals(listOf(
            "[Gamescope WSI] Forcing swapchain recreation as frame limiter changed, and we want the app to know the exposed modes changed.",
            "info:  Presenter: Got VK_ERROR_OUT_OF_DATE_KHR, recreating swapchain",
            "[Gamescope WSI] Destroying swapchain: 0x7ad40b40b0",
        ), keys.causes)
        assertEquals(listOf("[Gamescope WSI] Destroying swapchain: 0x7ad40b40b0"), keys.hung)
        val all = keys.causes + keys.others
        for (noise in listOf("gameoverlayrenderer", "OpenXR", "libusb", "winebth", "nested exception", "Bad file descriptor",
                "terminate called", "MakeWindowAssociation", "<redacted:guid>", "Failed to connect to Steam"))
            assertFalse(noise, all.any { it.contains(noise) })
        assertEquals(all.size, all.map { ReportCause.repeatKey(it) }.distinct().size)
    }

    @Test fun teardownLinesCountWhenTheGameWasNotStopped() {
        val keys = ReportCause.keyLines(fixture("yooka-proton.log"), stopped = false)
        assertTrue(keys.causes.any { it.contains("nested exception on signal stack") })
    }

    @Test fun yookaIsAStallAfterASwapchainRecreation() {
        val launch = yookaLaunch
        val exit = ReportCause.exitOf(launch)
        assertNotNull(exit)
        assertEquals("game ended by SIGINT (signal 2) after 114 s", exit!!.text)
        assertTrue(exit.stopped)
        val found = ReportCause.analyse(launch, fixture("yooka-proton.log"), fixture("yooka-logcat.txt"), at(11, 32, 30), zone)!!
        assertEquals("Game stalled (no frames for 90 s before it was stopped).", found.bullets[0])
        assertTrue(found.bullets[1], found.bullets[1].startsWith("A swapchain destroy never returned: `[Gamescope WSI] Destroying swapchain: 0x7ad40b40b0`"))
        assertTrue(found.bullets.any { it.startsWith("Gamescope WSI forced a swapchain recreation: ") })
        assertEquals("Game ended by SIGINT (signal 2) after 114 s (a stop signal: the session or Steam stopped it).", found.bullets.last())
        assertEquals(listOf(
            "[Gamescope WSI] Swapchain received new refresh cycle: 16.67ms",
            "[Gamescope WSI] Forcing swapchain recreation as frame limiter changed, and we want the app to know the exposed modes changed.",
            "info:  Presenter: Got VK_ERROR_OUT_OF_DATE_KHR, recreating swapchain",
            "[Gamescope WSI] Destroying swapchain: 0x7ad40b40b0",
        ), found.wsiLines)
        assertFalse(found.bullets.any { it.contains("Vulkan/driver error") })
    }

    @Test fun yookaLaunchBlockReplacesTheNoisySummaryWithTheKeyLines() {
        val proton = fixture("yooka-proton.log")
        val name = "steam-13575092407967416320.log"
        val block = ReportSummary.lastLaunch(yookaLaunch, proton, name, ReportCause.keyLines(proton, true), stopped = true)
        assertFalse(block.joinToString("\n"), block.any { it.contains("gameoverlayrenderer") || it.contains("last 20 err/warn") })
        assertTrue(block.contains("! [Gamescope WSI] Forcing swapchain recreation as frame limiter changed, and we want the app to know the exposed modes changed."))
        assertTrue(block.any { it.endsWith("mali-bridge: vendor HAL: /vendor/lib64/hw/vulkan.mali.so (via ro.hardware.vulkan=mali)") })
        assertTrue(block.contains("droiddeck-game-env: game ended by SIGINT (signal 2) after 114 s"))
        // a summary of another log stays, without its noise and repeats
        val kept = ReportSummary.lastLaunch(yookaLaunch, proton, "steam-1.log", ReportCause.keyLines(proton, true), stopped = true)
        assertTrue(kept.any { it.contains("failure summary from steam-13575092407967416320.log") })
        assertFalse(kept.any { it.contains("gameoverlayrenderer") || it.contains("nested exception") || it.contains("libusb") })
        assertTrue(kept.any { it.contains("droiddeck-game-env: | warn:  D3D11DXGIDevice") }.not())
    }

    @Test fun yookaGameAndEnvAreResolvedFromTheShortcutAndTheLaunch() {
        assertEquals("3160697503", ReportSummary.scopeOf("13575092407967416320"))
        assertEquals("782330", ReportSummary.scopeOf("782330"))
        assertNull(ReportSummary.scopeOf("app"))
        assertEquals("Yooka-Laylee and the Impossible Lair" to "epic:Duckbill", ReportSummary.launchGame(yookaLaunch))
        assertEquals(setOf("MALI_COMPAT_XFB_VS"), ReportSummary.keptOwnKeys(yookaLaunch))
        assertEquals("yours: MALI_COMPAT_XFB_VS (value not in the logs); Mali defaults epic:Duckbill: all overridden by yours",
            ReportSummary.envCell(emptyMap(), ReportSummary.keptOwnKeys(yookaLaunch), "epic:Duckbill", mapOf("MALI_COMPAT_XFB_VS" to "1")))
        assertEquals("yours: MALI_COMPAT_XFB_VS=0; Mali defaults epic:Duckbill: A=1",
            ReportSummary.envCell(mapOf("MALI_COMPAT_XFB_VS" to "0"), setOf("MALI_COMPAT_XFB_VS"), "epic:Duckbill",
                mapOf("MALI_COMPAT_XFB_VS" to "1", "A" to "1")))
        assertEquals("none", ReportSummary.envCell(emptyMap(), emptySet(), null, null))
    }

    @Test fun doomF1NamesTheGpuFaultAndTheStallBeforeTheReport() {
        val launch = ReportSummary.launchLines(fixture("doom-session.log").asSequence())
        assertNull(ReportCause.exitOf(launch))
        assertEquals("DOOM Eternal" to "steam:782330", ReportSummary.launchGame(launch))
        val proton = fixture("doom-proton.log")
        val keys = ReportCause.keyLines(proton, stopped = false)
        assertTrue(keys.causes.first().contains("GROUP_ERROR_FATAL"))
        assertFalse((keys.causes + keys.others).any { it.contains("gameoverlayrenderer") || it.contains("OpenXR") })
        val found = ReportCause.analyse(launch, proton, fixture("doom-logcat.txt"), at(11, 10, 26), zone)!!
        assertEquals("Game stalled (no frames for 20 s before the report; no game exit was logged).", found.bullets[0])
        assertEquals("GPU fault: `==>[WARN] Received a GROUP_ERROR_FATAL error on group(0)`", found.bullets[1])
        assertEquals("No game exit was logged for this launch (the game may still have been running).", found.bullets.last())
    }

    @Test fun theExitTimeOrTheStopRequestEndsTheLaunch() {
        val logcat = listOf(
            "11-20 10:00:10.000 1 2 I DroidDeckWayland: [stats] last 10 s: 600 frames on screen (60.0 fps) | 300 GPU frames from games | 0 window redraws | 1 windows open",
            "11-20 10:00:20.000 1 2 I DroidDeckWayland: [stats] last 10 s: 600 frames on screen (60.0 fps) | 0 GPU frames from games | 0 window redraws | 1 windows open",
            "11-20 10:00:30.000 1 2 I DroidDeckWayland: [stats] last 10 s: 600 frames on screen (60.0 fps) | 0 GPU frames from games | 0 window redraws | 1 windows open",
            "11-20 10:00:40.000 1 2 I DroidDeckWayland: [stats] last 10 s: 600 frames on screen (60.0 fps) | 0 GPU frames from games | 0 window redraws | 1 windows open",
            "11-20 10:00:50.000 1 2 I DroidDeckWayland: [stats] last 10 s: 600 frames on screen (60.0 fps) | 80 GPU frames from games | 0 window redraws | 1 windows open",
        )
        val now = at(10, 5, 0)
        val windows = ReportCause.windows(logcat, now, zone)
        assertEquals(5, windows.size)
        val exited = ReportCause.exitOf(listOf("droiddeck-game-env: game exited with status 5 after 40 s (if it crashed: low byte of x); " +
            "ended at unix time ${at(10, 0, 41) / 1000}"))!!
        assertEquals(5, exited.status)
        assertEquals(at(10, 0, 41), exited.endedAtMs)
        assertEquals("Game stalled (no frames for 30 s before it exited)", ReportCause.stall(windows, emptyList(), exited, now)!!.text)
        // the stop request, not the later teardown frames, ends a stopped game
        val stopped = ReportCause.exitOf(listOf("droiddeck-game-env: game ended by SIGTERM (signal 15) after 45 s; ended at unix time ${at(10, 0, 52) / 1000}"))!!
        assertEquals("Game stalled (no frames for 30 s before it was stopped)",
            ReportCause.stall(windows, listOf(at(10, 0, 41)), stopped, now)!!.text)
        assertNull(ReportCause.stall(windows, emptyList(), stopped, now))
        // a game that never drew
        val never = ReportCause.exitOf(listOf("droiddeck-game-env: game ended by SIGINT (signal 2) after 25 s"))!!
        assertEquals("Game showed no frames (none in the 30 s before it was stopped)",
            ReportCause.stall(windows, listOf(at(10, 0, 40)), never, now)!!.text)
        // one quiet window is no stall
        assertNull(ReportCause.stall(windows.take(2), emptyList(), null, at(10, 0, 21)))
    }

    @Test fun reviewRules() {
        // a low-memory kill is no stop: teardown lines stay, no "stopped" wording
        val killed = ReportCause.exitOf(listOf("droiddeck-game-env: game ended by SIGKILL (signal 9) after 50 s"))!!
        assertFalse(killed.stopped)
        assertEquals("Game ended by SIGKILL (signal 9) after 50 s.", ReportCause.findings(emptyList(), killed, null).bullets.last())
        // a clean exit is never a stall, and a non-GPU window's redraws count as drawing
        val stats = { frames: Int, redraws: Int, s: Int ->
            "11-20 10:00:%02d.000 1 2 I DroidDeckWayland: [stats] last 10 s: 600 frames on screen (60.0 fps) | $frames GPU frames from games | $redraws window redraws | 1 windows open".format(s)
        }
        val now = at(10, 5, 0)
        val windows = ReportCause.windows(listOf(stats(50, 0, 10), stats(0, 0, 20), stats(0, 0, 30)), now, zone)
        val clean = ReportCause.exitOf(listOf("droiddeck-game-env: game exited with status 0 after 30 s; ended at unix time ${at(10, 0, 31) / 1000}"))!!
        assertNull(ReportCause.stall(windows, emptyList(), clean, now))
        val redrawn = ReportCause.windows(listOf(stats(50, 0, 10), stats(0, 0, 20), stats(0, 4, 30)), now, zone)
        assertNull(ReportCause.stall(redrawn, emptyList(), null, at(10, 0, 31)))
        // a routine recreation is no cause on its own
        val resize = listOf("[Gamescope WSI] Forcing swapchain recreation as frame limiter changed, x.",
            "info:  Presenter: Got VK_ERROR_OUT_OF_DATE_KHR, recreating swapchain",
            "[Gamescope WSI] Destroying swapchain: 0x1", "[Gamescope WSI] Destroyed swapchain: 0x1")
        assertTrue(ReportCause.keyLines(resize, stopped = false).causes.isEmpty())
        assertTrue(ReportCause.findings(resize, null, null).bullets[0].startsWith("No known cause pattern"))
        // a known pattern outranks a flood of late Wine errors
        val flood = listOf("==>[WARN] Received a GROUP_ERROR_FATAL error on group(0)") +
            (0 until 20).map { "100.%03d:0001:0002:err:module:late error %d".format(it, it) }
        val keys = ReportCause.keyLines(flood, stopped = false)
        assertEquals(ReportCause.CAUSE_LINES, keys.causes.size)
        assertEquals("==>[WARN] Received a GROUP_ERROR_FATAL error on group(0)", keys.causes.first())
        // a stopped game's late errors are teardown, not causes
        assertTrue(ReportCause.keyLines(flood.drop(1), stopped = true).causes.isEmpty())
    }

    @Test fun logcatTimeTakesTheYearBeforeANewYearReport() {
        val newYear = Calendar.getInstance(zone).apply { clear(); set(2027, Calendar.JANUARY, 1, 0, 5, 0) }.timeInMillis
        val t = ReportCause.logcatTime("12-31 23:59:50.000 1 2 I X: y", newYear, zone)!!
        assertEquals(2026, Calendar.getInstance(zone).apply { timeInMillis = t }.get(Calendar.YEAR))
    }

    @Test fun nothingKnownSaysSo() {
        val found = ReportCause.findings(listOf("info: all fine"), ReportCause.exitOf(listOf("droiddeck-game-env: game exited with status 1 after 3 s")), null)
        assertTrue(found.bullets[0].startsWith("No known cause pattern was found"))
        assertEquals("Game exited with status 1 after 3 s.", found.bullets[1])
        assertTrue(found.wsiLines.isEmpty())
        assertNull(ReportCause.analyse(emptyList(), emptyList(), emptyList(), 0L, zone))
    }
}
