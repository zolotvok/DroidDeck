package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.LinuxRuntime
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
import java.io.File
import java.util.zip.ZipFile

/**
 * Builds whole report and share zips from the sanitized 2026-11-20 18:08 fixture (test resources fixtures/report-1808,
 * synthetic ids only) and fails on any Steam account id, account name, family name or email in any entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReportPrivacyLeakTest {
    private lateinit var app: Context
    private lateinit var logs: File
    private lateinit var root: File
    private lateinit var steamLogs: File

    private val fixture = File(javaClass.classLoader!!.getResource("fixtures/report-1808/README.txt")!!.toURI()).parentFile!!

    private val steamId64 = Regex("7656119\\d{10}")
    private val accountIdShapes = listOf(
        Regex("(?i)userdata/\\d{3,}"),
        Regex("(?i)Steam Controller Configs/\\d{3,}"),
        Regex("(?i)for (?:user|account) \\d{3,}"),
        Regex("(?i)parental settings for \\d{3,}"),
        Regex("(?i)account ?id\\s*[:=]?\\s*\\d{3,}"),
        Regex("(?i)steam_?id\\s*[:=]\\s*\\d{3,}"),
        Regex("\\[U:1:[1-9]\\d*]"),
        Regex("(?<![A-Za-z0-9_/.-])\\d{3,}/config/"),
        Regex("(?i)groupID=\\d{4,}"),
        Regex("STEAM_[0-5]:[01]:\\d"),
        Regex("(?i)0x0*1100001[0-9a-f]{8}"),
        Regex("76561\\*{8}\\d{4}"),
        Regex("\\[U:1:\\*+\\d"),
        Regex("(?i)userdata\\\\?/\\d{3,}"),
        Regex("(?i)accountid\\W{0,3}\\d{3,}"),
    )
    /** An account that stands only in the logs (not in loginusers.vdf): the fixed rules must catch it unlearned. */
    private val oldAccountId = "987654321"
    private val unlisted = listOf("cloud_log", "connection_log", "content_log", "parental_log", "configstore_log", "librarysharing_log")

    /** The account ids behind every SteamID64 of the fixture's loginusers.vdf, and its names. */
    private val accountIds: List<String> by lazy {
        steamId64.findAll(File(fixture, "root/loginusers.vdf").readText()).map { (it.value.toLong() - 76561197960265728L).toString() }
            .toList() + oldAccountId
    }
    private val names: List<String> by lazy {
        Regex("\"(?:AccountName|PersonaName)\"\\s+\"([^\"]+)\"").findAll(File(fixture, "root/loginusers.vdf").readText())
            .map { it.groupValues[1] }.toList() + unlearnedNames
    }
    private val unlearnedNames = listOf("Fixturefamily", "Otherfamily", "fixture.user@example.com")

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        logs = LinuxRuntime.logDir(app).apply { deleteRecursively(); mkdirs() }
        root = LinuxRuntime.rootDir(app)
        File(root, "root").deleteRecursively()
        File(fixture, "root/loginusers.vdf").copyTo(File(SessionPrefs.steamRoot(app, "arm64"), "config/loginusers.vdf"), overwrite = true)
        steamLogs = File(SessionPrefs.steamRoot(app, "arm64"), "logs").apply { mkdirs() }
        File(fixture, "steam-logs").listFiles()!!.forEach { it.copyTo(File(steamLogs, it.name), overwrite = true) }
        File(fixture, "proton/steam-292030.log").copyTo(File(root, "root/steam-292030.log"), overwrite = true)
        app.getSharedPreferences("report", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        LogRedactor.learnAccounts()
        com.droiddeck.launcher.core.ReportRedactor.learn(emptyList())
        logs.deleteRecursively()
        File(root, "root").deleteRecursively()
        ReportBundle.shareDir(app).deleteRecursively()
    }

    private fun session(name: String): File = File(logs, name).apply {
        mkdirs()
        File(fixture, "session").listFiles()!!.forEach { it.copyTo(File(this, it.name), overwrite = true) }
    }

    private fun zipText(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() }
    }

    private fun assertNoLeak(entries: Map<String, String>, names: List<String> = this.names) {
        assertTrue(accountIds.size >= 2 && names.isNotEmpty())
        for ((path, text) in entries) {
            assertFalse("$path: a SteamID64", steamId64.containsMatchIn(text))
            for (id in accountIds) assertFalse("$path: the account id", Regex("(?<![0-9])$id(?![0-9])").containsMatchIn(text))
            for (shape in accountIdShapes) assertFalse("$path: ${shape.pattern}", shape.containsMatchIn(text))
            for (name in names) assertFalse("$path: an account, family or email name", text.contains(name, ignoreCase = true))
            for (u in unlisted) assertFalse("$path is off the Steam log allowlist", path.contains("/steam/$u"))
        }
    }

    @Test fun aFolderFromAnOlderVersionWithEverySteamLogLeaksNothingIntoTheReport() {
        val folder = session("2026-11-20-23-steam")
        File(folder, "steam").mkdirs()
        File(fixture, "steam-logs").listFiles()!!.forEach { it.copyTo(File(folder, "steam/${it.name}")) }

        val plan = ReportBundle.collect(app)
        val result = ReportBundle.build(app, plan)
        val entries = zipText(result.zip)

        assertNoLeak(entries)
        val steam = entries.keys.filter { it.startsWith("session/2026-11-20-23-steam/steam/") }.map { it.substringAfterLast('/') }.toSet()
        assertTrue(steam.containsAll(setOf("console_log.txt", "gameprocess_log.txt", "controller.txt", "cef_log.txt", "vrcompositor.txt",
            "vrserver.txt", "vrstartup.txt", "vrclient_steam.txt", "droiddeck-vrstartup.log")))
        val text = entries.getValue("session/2026-11-20-23-steam/steam/vrcompositor.txt")
        assertTrue(text.contains("Failed to create VkDevice."))
        val hits = result.entries.associate { it.path.substringAfterLast('/') to it.hits }
        assertTrue("controller.txt: the id in the config path counts", hits.getValue("controller.txt") >= 1)
        assertTrue("cef_log.txt: the SteamID64 counts", hits.getValue("cef_log.txt") >= 1)
        assertTrue("console_log.txt: account, persona and email count", hits.getValue("console_log.txt") >= 3)
    }

    @Test fun theSessionEndCopyTakesOnlyAllowlistedLogsAndTheReportLeaksNothing() {
        val folder = session("2026-11-20-23-steam")
        LogRedactor.learnFromRuntime(root)
        val copied = SessionArtifacts.copySessionLogs(steamLogs, folder, File(folder, "steam").apply { mkdirs() }).map { it.name }.toSet()

        assertEquals(setOf("console_log.txt", "gameprocess_log.txt", "controller.txt", "cef_log.txt", "webhelper.txt", "webhelper_js.txt", "vrcompositor.txt",
            "vrserver.txt", "vrstartup.txt", "vrclient_steam.txt", "droiddeck-vrstartup.log"), copied)
        for (f in File(folder, "steam").listFiles()!!) {
            val text = f.readText()
            assertFalse("${f.name} on disk: a SteamID64", steamId64.containsMatchIn(text))
            for (id in accountIds) assertFalse("${f.name} on disk: the account id", text.contains(id))
            for (shape in accountIdShapes) assertFalse("${f.name} on disk: ${shape.pattern}", shape.containsMatchIn(text))
        }
        assertNoLeak(zipText(ReportBundle.build(app, ReportBundle.collect(app)).zip))
    }

    @Test fun withoutLoginusersTheFixedRulesAloneLeakNoId() {
        File(SessionPrefs.steamRoot(app, "arm64"), "config/loginusers.vdf").delete()
        val folder = session("2026-11-20-23-steam")
        File(folder, "steam").mkdirs()
        File(fixture, "steam-logs").listFiles()!!.forEach { it.copyTo(File(folder, "steam/${it.name}")) }

        assertNoLeak(zipText(ReportBundle.build(app, ReportBundle.collect(app)).zip), names = unlearnedNames)
        assertNoLeak(zipText(SessionLogShare.zipFolder(app, folder)!!), names = unlearnedNames)
    }

    @Test fun theSessionShareZipOfAnOlderFolderLeaksNothing() {
        val folder = session("2026-11-20-23-steam")
        File(folder, "steam").mkdirs()
        File(fixture, "steam-logs").listFiles()!!.forEach { it.copyTo(File(folder, "steam/${it.name}")) }

        val entries = zipText(SessionLogShare.zipFolder(app, folder)!!)

        assertNoLeak(entries)
        assertTrue(entries.containsKey("2026-11-20-23-steam/steam/gameprocess_log.txt"))
    }

    @Test fun reScrubbingAnOlderFolderDropsItsUnlistedSteamLogs() {
        val folder = session("2026-11-20-22-steam")
        File(folder, "steam").mkdirs()
        File(fixture, "steam-logs").listFiles()!!.forEach { it.copyTo(File(folder, "steam/${it.name}")) }

        LogRedactor.learnFromRuntime(root)
        SessionArtifacts.scrubAndMark(folder)

        val left = File(folder, "steam").list()!!.toSet()
        for (u in unlisted) assertFalse(u, left.any { it.startsWith(u) })
        assertTrue("console_log.txt" in left)
        for (f in File(folder, "steam").listFiles()!!) for (id in accountIds) assertFalse(f.name, f.readText().contains(id))
    }
}
