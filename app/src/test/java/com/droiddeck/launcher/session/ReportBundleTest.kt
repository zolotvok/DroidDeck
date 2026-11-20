package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReportBundleTest {
    private lateinit var app: Context
    private lateinit var logs: File
    private lateinit var root: File

    private val exchange = "8f3a1c2b9d4e4f5a8b7c6d5e4f3a2b1c"
    private val refresh = "eg1~eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abcDEF123456"
    private val steamId = "76561198012345678"

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        logs = LinuxRuntime.logDir(app).apply { deleteRecursively(); mkdirs() }
        root = LinuxRuntime.rootDir(app)
        File(root, "root").mkdirs()
        app.getSharedPreferences("report", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().remove("nextLaunchLogAt").commit()
    }

    @After fun tearDown() {
        logs.deleteRecursively()
        File(root, "root").deleteRecursively()
        ReportBundle.shareDir(app).deleteRecursively()
        File(app.filesDir, "game-environment.json").delete()
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().remove("nextLaunchLogAt").commit()
    }

    private fun session(name: String, log: String): File = File(logs, name).apply {
        mkdirs()
        File(this, "session.log").writeText(log)
    }

    private fun zipText(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() }
    }

    @Test fun theReportHoldsTheAllowedPartsAndNoSecretsOrSaves() {
        val previous = session("2026-11-04-01-steam", "old session refresh_token=$refresh\n")
        File(previous, "steam").mkdirs()
        File(previous, "steam/content_log.txt").writeText("previous steam log\n")
        val latest = session("2026-11-05-01-steam",
            "== droiddeck-session start\nlaunch -AUTH_PASSWORD=$exchange -AUTH_TYPE=exchangecode\nuser $steamId someone@example.com\n" +
                "path /data/user/0/com.droiddeck.launcher.mali/files/linuxfs\n")
        File(latest, "device.txt").writeText("Model vivo X200\n")
        File(latest, "saves").mkdirs()
        File(latest, "saves/slot1.sav").writeText("SAVE DATA")
        File(latest, "loginusers.vdf").writeText("\"AccountName\" \"zolo\"")
        File(root, "root/steam-447040.log").writeText(
            "Proton: 10.0\nmali-compat: wls-clamp: module 1234abcd chains 3\nmali-wsi: swapchain 1600x720 present mode 2\n" +
                "err: VK_ERROR_DEVICE_LOST\n{\"exchange_code\": \"$exchange\"}\n")
        GameEnvironmentStore.save(app, GameEnvironment.Config(games = mapOf("447040" to mapOf("MALI_COMPAT_WLS_CLAMP" to "all", "EOS_TOKEN" to "abc123"))))

        val plan = ReportBundle.collect(app)
        val paths = plan.parts.map { it.path }
        assertTrue(paths.contains("session/2026-11-05-01-steam/session.log"))
        assertTrue(paths.contains("session/2026-11-05-01-steam/device.txt"))
        assertTrue(paths.contains("previous-session/2026-11-04-01-steam/session.log"))
        assertFalse("previous Steam logs stay out", paths.any { it.startsWith("previous-session/") && it.contains("/steam/") })
        assertFalse(paths.any { it.contains("slot1.sav") || it.contains("loginusers.vdf") })
        assertTrue(paths.containsAll(listOf("proton/steam-447040.log", "layers/compat.log", "layers/wsi.log", "logcat.txt", "env/game-env.txt", "summary.md")))
        assertTrue(plan.info.vulkanError)
        assertEquals("app (447040)", plan.info.game)

        val result = ReportBundle.build(app, plan)
        val files = zipText(result.zip)
        assertTrue(result.zip.name.matches(Regex("DroidDeck-report-\\d{8}-\\d{4}\\.zip")))
        assertTrue(files.containsKey("manifest.txt"))
        val all = files.values.joinToString("\n")
        for (secret in listOf(exchange, "abcDEF123456", steamId, "someone@example.com", "SAVE DATA", "com.droiddeck.launcher.mali/files", "abc123")) {
            assertFalse("'$secret' leaked", all.contains(secret))
        }
        assertTrue(files.getValue("layers/compat.log").contains("mali-compat: wls-clamp: module 1234abcd chains 3"))
        assertTrue(files.getValue("layers/wsi.log").contains("mali-wsi: swapchain 1600x720"))
        assertFalse(files.getValue("layers/compat.log").contains("mali-wsi"))
        assertTrue(files.getValue("env/game-env.txt").contains("MALI_COMPAT_WLS_CLAMP=all"))
        assertTrue(files.getValue("env/game-env.txt").contains("EOS_TOKEN=<redacted:value>"))
        assertTrue(files.getValue("summary.md").contains("| Game | app (447040) |"))
        assertTrue(files.getValue("summary.md").contains("## What went wrong\n\n- Vulkan device lost: `err: VK_ERROR_DEVICE_LOST`\n"))
        assertTrue(result.entries.first { it.path == "session/2026-11-05-01-steam/session.log" }.hits >= 3)
        assertTrue(result.entries.all { it.redacted })
    }

    @Test fun aBigLogIsTrimmedToHeadAndTailWithAMarker() {
        val big = File(app.cacheDir, "big.log")
        big.bufferedWriter().use { w ->
            w.write("FIRST LINE refresh_token=abcdef123456\n")
            repeat(60_000) { w.write("filler line number $it with some padding text\n") }
            w.write("LAST LINE\n")
        }
        val zip = File(app.cacheDir, "t.zip")
        val result = ReportBundle.write(listOf(ReportBundle.Part("session/big.log", ReportBundle.Kind.SESSION, big.length(), file = big)),
            emptyList(), zip, ReportBundle.Options(maxBytes = 512L * 1024))
        val text = zipText(zip).getValue("session/big.log")
        assertTrue(text.startsWith("FIRST LINE refresh_token=<redacted:token>\n"))
        assertTrue(text.trimEnd().endsWith("LAST LINE"))
        assertTrue(text.contains(Regex("\\.\\.\\. \\d+ bytes trimmed \\(report size cap\\) \\.\\.\\.")))
        assertTrue(text.length < 600 * 1024)
        assertTrue(result.entries.single().trimmed)
        assertTrue(text.lines().filter { it.startsWith("filler") }.all { it.matches(Regex("filler line number \\d+ with some padding text")) })
        assertTrue(zipText(zip).getValue("manifest.txt").contains("session/big.log"))
        big.delete(); zip.delete()
    }

    @Test fun rawModeKeepsTheValues() {
        val zip = File(app.cacheDir, "raw.zip")
        ReportBundle.write(listOf(ReportBundle.Part("a.txt", ReportBundle.Kind.ENV, 10, text = { "refresh_token=abcdef123456\n" })),
            emptyList(), zip, ReportBundle.Options(raw = true))
        val files = zipText(zip)
        assertEquals("refresh_token=abcdef123456\n", files.getValue("a.txt"))
        assertTrue(files.getValue("manifest.txt").contains("RAW"))
        zip.delete()
    }

    @Test fun budgetsFitTheCapAndKeepSmallParts() {
        val b = ReportBundle.budgets(mapOf("a" to (50L shl 20), "b" to (30L shl 20), "c" to 1000L), 20L shl 20)
        assertTrue(b.values.sum() <= (20L shl 20))
        assertEquals(1000L, b["c"])
        assertEquals(ReportBundle.HEAD_BYTES + ReportBundle.TAIL_BYTES, ReportBundle.budgets(mapOf("x" to (50L shl 20)), 200L shl 20)["x"])
    }

    @Test fun logcatKeepsOurPidsAndTagsOnly() {
        val lines = sequenceOf(
            "11-05 12:00:00.000  1234  1240 I SessionService: started",
            "11-05 12:00:00.001  9999  9999 I SomeOtherApp: hello",
            "11-05 12:00:00.002  9999  9999 E AndroidRuntime: FATAL EXCEPTION",
            "11-05 12:00:00.003  1000  1000 I ActivityManager: Start proc for com.other",
            "11-05 12:00:00.004  1000  1000 I ActivityManager: Process com.droiddeck.launcher.mali (pid 1234) has died",
            "--------- beginning of crash",
        )
        val kept = ReportBundle.filterLogcat(lines, setOf(1234), "com.droiddeck.launcher.mali", 10)
        assertEquals(3, kept.size)
        assertTrue(kept[1].contains("AndroidRuntime"))
        assertTrue(kept[2].contains("has died"))
        assertEquals(1, ReportBundle.filterLogcat(lines, setOf(1234), "x", 1).size)
    }

    @Test fun logcatSpamIsCappedAndCounted() {
        val spam = (0 until 100).map { "11-20 11:28:23.%03d  1234  1240 D SurfaceComposerClient: Transaction::setDataspace: 142671872".format(it) }
        val stats = "11-20 11:28:31.123  1234  1250 I DroidDeckWayland: [stats] last 10 s: 474 frames on screen (47.4 fps) | 0 GPU frames from games |"
        val kept = ReportBundle.filterLogcat((spam + stats).asSequence(), setOf(1234), "x", 1000)
        assertEquals(ReportBundle.LOGCAT_SPAM_KEPT + 2, kept.size)
        assertEquals(stats, kept[kept.size - 2])
        assertEquals("# 80 more Transaction::setDataspace lines left out", kept.last())
    }

    @Test fun theWsiLayerPartIsLeftOutWhenNoLogHasItsLines() {
        val log = File(app.cacheDir, "steam-1.log").apply { writeText("info: nothing\n") }
        assertEquals("", ReportBundle.layerLines(listOf(log), ReportBundle.WSI_LINE, emptyIfNone = true))
        log.writeText("[Gamescope WSI] Destroying swapchain: 0x7ad40b40b0\n")
        assertTrue(ReportBundle.layerLines(listOf(log), ReportBundle.WSI_LINE, emptyIfNone = true).contains("(1 lines)\n[Gamescope WSI] Destroying"))
        log.delete()
    }

    @Test fun savesAndCredentialsAreNeverAllowed() {
        for (p in listOf("loginusers.vdf", "steam/config.vdf", "ssfn123456", "Saved Games/x.txt", "pfx/drive_c/users/steamuser/Documents/a.log",
                "AppData/Roaming/x.log", "cookies.sqlite", "slot.sav")) assertFalse(p, ReportBundle.allowed(p))
        for (p in listOf("session.log", "steam/content_log.txt", "crash-app.txt")) assertTrue(p, ReportBundle.allowed(p))
    }

    @Test fun onlyTheLastGameExitCountsAndAStopIsNoCrash() {
        assertTrue(ReportBundle.failedExitIn(sequenceOf("droiddeck-game-env: game exited with status 5 after 12 s")))
        assertTrue(ReportBundle.failedExitIn(sequenceOf("droiddeck-game-env: game ended by SIGSEGV (signal 11) after 3 s")))
        assertFalse(ReportBundle.failedExitIn(sequenceOf("droiddeck-game-env: game ended by SIGTERM (signal 15) after 3 s")))
        assertFalse(ReportBundle.failedExitIn(sequenceOf("droiddeck-game-env: game exited with status 5 after 12 s",
            "droiddeck-game-env: game exited with status 0 after 40 s")))
        assertFalse(ReportBundle.failedExitIn(sequenceOf("droiddeck-game-env: game logs: PROTON_LOG=1")))
    }

    @Test fun gameCrashMarkersAreFound() {
        assertTrue(ReportBundle.crashIn(sequenceOf("ok", "wine: Unhandled page fault on read access to 0000000000000000")))
        assertFalse(ReportBundle.crashIn(sequenceOf("info: DXVK: v2.4", "fixme:ntdll:NtQuerySystemInformation")))
    }

    @Test fun recentGameCrashNeedsANewCrashingProtonLog() {
        val log = File(root, "root/steam-447040.log")
        log.writeText("wine: Unhandled exception 0xc0000005\n")
        val s = session("2026-11-20-01-steam", "droiddeck-game-env: game exited with status 0 after 30 s\n")
        assertFalse(ReportBundle.recentGameCrash(app))
        File(s, "session.log").appendText("droiddeck-game-env: game exited with status 5 after 12 s\n")
        assertTrue(ReportBundle.recentGameCrash(app))
        ReportBundle.markReported(app, log.lastModified() + 1)
        assertFalse(ReportBundle.recentGameCrash(app))
    }

    @Test fun anAppCrashIsOfferedUntilReportedOrDismissed() {
        val s = session("2026-11-05-02-steam", "x\n")
        File(s, "crash-app.txt").writeText("The app itself crashed")
        val at = ReportBundle.unreportedAppCrash(app)
        assertNotNull(at)
        ReportBundle.markCrashSeen(app, at!! + 1)
        assertNull(ReportBundle.unreportedAppCrash(app))
    }

    @Test fun theLaunchsProtonLogIsTheOneItsSummaryNamesOrANewOne() {
        val old = File(app.cacheDir, "steam-782330.log").apply { writeText("x\n"); setLastModified(1_000_000L) }
        val new = File(app.cacheDir, "steam-447040.log").apply { writeText("x\n"); setLastModified(9_000_000L) }
        val logs = listOf(new, old)
        val named = listOf("droiddeck-game-env: game driver: x", "droiddeck-game-env: failure summary from steam-782330.log (1 bridge HAL lines):")
        assertEquals(old, ReportBundle.launchProtonLog(logs, named, 5_000_000L))
        assertEquals(new, ReportBundle.launchProtonLog(logs, listOf("droiddeck-game-env: game driver: x"), 5_000_000L))
        assertNull(ReportBundle.launchProtonLog(logs, listOf("droiddeck-game-env: game driver: x"), 10_000_000L))
        assertEquals(new, ReportBundle.launchProtonLog(logs, emptyList(), 10_000_000L))
        old.delete(); new.delete()
    }

    @Test fun aShortcutsEnvIsReadUnderItsAppId() {
        session("2026-11-20-14-steam", "droiddeck-game-env: game driver: Mali system driver (bridge), /b/icd.json; x\n" +
            "droiddeck-game-env: Mali defaults for Yooka-Laylee and the Impossible Lair (epic:Duckbill), env defaults 2026.11.19.1 (bundled)\n" +
            "droiddeck-game-env: Yooka-Laylee and the Impossible Lair: your own MALI_COMPAT_XFB_VS kept over the defaults\n" +
            "droiddeck-game-env: game ended by SIGINT (signal 2) after 114 s\n")
        File(root, "root/steam-13575092407967416320.log").writeText("[Gamescope WSI] Destroying swapchain: 0x7ad40b40b0\n")
        GameEnvironmentStore.save(app, GameEnvironment.Config(games = mapOf("3160697503" to mapOf("MALI_COMPAT_XFB_VS" to "0"))))
        val plan = ReportBundle.collect(app)
        assertEquals("Yooka-Laylee and the Impossible Lair (epic:Duckbill, shortcut 3160697503)", plan.info.game)
        assertTrue(plan.info.envCell!!, plan.info.envCell!!.startsWith("yours: MALI_COMPAT_XFB_VS=0"))
        assertTrue(plan.info.lastExit, plan.info.lastExit.startsWith("game ended by SIGINT (signal 2) after 114 s"))
        val env = plan.parts.first { it.path == "env/game-env.txt" }.text!!.invoke()
        assertTrue(env, env.contains("[game 3160697503]\nMALI_COMPAT_XFB_VS=0\n"))
        assertTrue(plan.info.cause.any { it.startsWith("A swapchain destroy never returned") })
    }

    @Test fun nextLaunchLogIsPublishedOnlyAndClearedAfterAGameLog() {
        val guest = File(root, "root/.config/droiddeck/game-environment.json")
        GameEnvironmentStore.save(app, GameEnvironment.Config(games = mapOf("447040" to mapOf("PROTON_LOG" to "0"))))
        SessionPrefs.setNextLaunchLog(app, true)
        val published = JSONObject(guest.readText())
        assertEquals("1", published.getJSONObject("shared").getString("PROTON_LOG"))
        assertEquals("1", published.getJSONObject("games").getJSONObject("447040").getString("PROTON_LOG"))
        assertEquals("0", GameEnvironmentStore.read(app).games.getValue("447040")["PROTON_LOG"])

        ReportBundle.finishNextLaunchLog(app, null)
        assertTrue("no game ran yet", SessionPrefs.nextLaunchLog(app))
        val log = File(root, "root/steam-447040.log")
        log.writeText("Proton log\n")
        log.setLastModified(SessionPrefs.nextLaunchLogSince(app) + 5_000)
        ReportBundle.finishNextLaunchLog(app, null)
        assertFalse(SessionPrefs.nextLaunchLog(app))
        assertFalse(JSONObject(guest.readText()).getJSONObject("shared").has("PROTON_LOG"))
    }
}
