package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreGameSidecarTest {
    private val sample = StoreGameSidecar(
        Store.EPIC, "abc123", "Celeste", "Celeste.exe", launcher = ".droiddeck-launch.bat",
        args = listOf("-EpicPortal", "-epicusername=\"Some One\""), env = mapOf("FOO" to "bar"),
        installVersion = "1.4.0", installedAt = 1700000000L, cover = "https://x/c.jpg",
        extra = mapOf("namespace" to "ns"),
    )

    @Test fun roundTripsThroughJson() {
        val back = StoreGameSidecar.parse(sample.toJson().toString())!!
        assertEquals(Store.EPIC, back.store)
        assertEquals("abc123", back.id)
        assertEquals("Celeste", back.title)
        assertEquals("Celeste.exe", back.exe)
        assertEquals(".droiddeck-launch.bat", back.launcher)
        assertEquals(sample.args, back.args)
        assertEquals(sample.env, back.env)
        assertEquals("1.4.0", back.installVersion)
        assertEquals(1700000000L, back.installedAt)
        assertEquals("https://x/c.jpg", back.cover)
        assertEquals("ns", back.extra["namespace"])
    }

    @Test fun defaultsWhenFieldsAreAbsent() {
        val back = StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"bin\\game.exe","addToSteam":false}""")!!
        assertEquals("bin/game.exe", back.exe)
        assertNull(back.launcher)
        assertTrue(back.args.isEmpty())
        assertTrue(back.isInstalled)
    }

    @Test fun epicLaunchChoicesRoundTripAndDefaultOn() {
        val plain = StoreGameSidecar.parse(StoreGameSidecar(Store.EPIC, "m", "Metalstorm", "M.exe").toJson().toString())!!
        assertEquals(EpicOptions(eos = true, offline = false), plain.epic)
        assertTrue(plain.epic.wantsCode)
        val set = StoreGameSidecar(Store.EPIC, "m", "Metalstorm", "M.exe", epic = EpicOptions(eos = true, offline = true))
        val back = StoreGameSidecar.parse(set.toJson().toString())!!
        assertEquals(EpicOptions(eos = true, offline = true), back.epic)
        assertFalse(back.epic.wantsCode)
        assertEquals(EpicOptions(), StoreGameSidecar.parse("""{"store":"epic","id":"m","title":"M","exe":"M.exe"}""")!!.epic)
        assertEquals(EpicOptions(), StoreGameSidecar.parse("""{"store":"epic","id":"m","title":"M","exe":"M.exe","epic":{"v":2,"eos":true,"offline":false,"overlay":true}}""")!!.epic)
    }

    @Test fun aSwitchOnTheEpicCardChangesTheSidecarOnDisk() {
        val folder = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().filesDir, "Games/Stores/Epic/Metalstorm").apply { mkdirs() }
        StoreGameSidecar(Store.EPIC, "m", "Metalstorm", "Metalstorm.exe", launcher = ".droiddeck-launch.bat").write(folder)
        val written = StoreGameSidecar.updateEpic(folder) { it.copy(offline = true) }!!
        assertTrue(written.epic.offline)
        val onDisk = org.json.JSONObject(java.io.File(folder, StoreGameSidecar.FILE_NAME).readText())
        assertTrue(onDisk.getJSONObject("epic").getBoolean("offline"))
        assertTrue(onDisk.getJSONObject("epic").getBoolean("eos"))
        assertFalse(onDisk.getJSONObject("epic").has("overlay"))
        assertEquals(".droiddeck-launch.bat", StoreGameSidecar.read(folder)!!.launcher)
        assertEquals(null, StoreGameSidecar.updateEpic(java.io.File(folder, "missing")) { it })
    }

    @Test fun gogAchievementsAreIndependentOfCloudAndSurviveEdits() {
        val folder = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().filesDir, "gog-achievements").apply { mkdirs() }
        StoreGameSidecar(Store.GOG, "1", "Game", "game.exe", cloud = false).write(folder)
        assertTrue(StoreGameSidecar.read(folder)!!.achievements)
        StoreGameSidecar.updateAchievements(folder, false)
        StoreGameSidecar.updateCloud(folder, true)
        assertFalse(StoreGameSidecar.read(folder)!!.achievements)
        assertTrue(StoreGameSidecar.read(folder)!!.cloud)
    }

    @Test fun anInstallUnderWayHasNoExeYetAndIsNotInstalled() {
        val started = StoreGameSidecar(Store.EPIC, "Metalstorm", "Metalstorm", exe = "", state = StoreGameSidecar.STATE_INSTALLING)
        val back = StoreGameSidecar.parse(started.toJson().toString())!!
        assertFalse(back.isInstalled)
        assertEquals("", back.exe)
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":""}"""))
        assertTrue(StoreGameSidecar.parse(back.copy(exe = "Metalstorm.exe", state = StoreGameSidecar.STATE_INSTALLED).toJson().toString())!!.isInstalled)
    }

    @Test fun refusesWhatIsNotASidecar() {
        assertNull(StoreGameSidecar.parse("not json"))
        assertNull(StoreGameSidecar.parse("""{"store":"steam","id":"1","title":"T","exe":"a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"","title":"T","exe":"a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"../other/a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"/abs/a.exe"}"""))
        assertNull(StoreGameSidecar.parse("""{"store":"gog","id":"1","title":"T","exe":"C:\\a.exe"}"""))
    }

    @Test fun folderNamesAreSafeAndStable() {
        assertEquals("Heroes of Might and Magic 3 Complete", StoreInstallRoot.folderName("Heroes of Might and Magic 3: Complete", "1"))
        assertEquals("What Remains of Edith Finch", StoreInstallRoot.folderName("What  Remains of Edith Finch...", "1"))
        assertEquals("1207658924", StoreInstallRoot.folderName("日本語のみ", "1207658924"))
        assertTrue(StoreInstallRoot.folderName("x".repeat(200), "1").length <= 60)
    }

    @Test fun launcherStartsTheExeFromItsFolderWithArgsAndEnv() {
        val text = StoreLaunch.launcherText(sample.copy(exe = "Binaries/Win64/Celeste.exe"))
        assertTrue(text.contains("cd /d \"%~dp0Binaries\\Win64\"\r\n"))
        assertTrue(text.contains("set \"FOO=bar\"\r\n"))
        assertTrue(text.contains("\r\n\"%~dp0Binaries\\Win64\\Celeste.exe\" -EpicPortal -epicusername=\"Some One\"\r\n"))
        assertTrue(text.contains("set /p DD_X=<\"%~dp0.droiddeck-epic-code\""))
        assertTrue(text.contains("set \"DD_X=\" & \"%~dp0Binaries\\Win64\\Celeste.exe\" -EpicPortal -epicusername=\"Some One\" -AUTH_LOGIN=unused -AUTH_PASSWORD=%DD_X% -AUTH_TYPE=exchangecode %DD_OVT%\r\n"))
        assertTrue(text.contains("if exist \"%~dp0.droiddeck-epic.ovt\" set \"DD_OVT=-epicovt=\"%~dp0.droiddeck-epic.ovt\"\"\r\n"))
        assertFalse(text.contains("AUTH="))
        assertFalse(text.contains("DD_AUTH"))
    }

    @Test fun epicLaunchCommandGoesRightAfterTheExe() {
        val ubi = sample.copy(exe = "UplayLaunch.exe", env = emptyMap(),
            extra = sample.extra + (com.droiddeck.launcher.frontend.WinCompSources.EPIC_LAUNCH_COMMAND to "-UplayId=2688 & del x"))
        val text = StoreLaunch.launcherText(ubi)
        assertTrue(text.contains("\r\n\"%~dp0UplayLaunch.exe\" -UplayId=2688  del x -EpicPortal -epicusername=\"Some One\"\r\n"))
        val gog = StoreLaunch.launcherText(StoreGameSidecar(Store.GOG, "1", "T", "game.exe",
            extra = mapOf(com.droiddeck.launcher.frontend.WinCompSources.EPIC_LAUNCH_COMMAND to "-x")))
        assertFalse(gog.contains("-x"))
    }

    private val wd2 = StoreGameSidecar(
        Store.EPIC, "Angelonia", "Watch Dogs 2", "UplayLaunch.exe", launcher = ".droiddeck-launch.bat",
        args = listOf("-EpicPortal", "-epicuserid=acc"),
        extra = mapOf(
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_NAME to "Uplay",
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_PATH to "Uplay/UplayInstaller.exe",
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_LAUNCH_COMMAND to "-UplayId=2688",
        ),
    )

    @Test fun ubisoftConnectIsInstalledInThePrefixBeforeUplayLaunchWhileItIsMissing() {
        val text = StoreLaunch.launcherText(wd2)
        val block = "if not exist \"%~dp0Uplay\\UplayInstaller.exe\" goto dd_prereq_done\r\n" +
            "if exist \"%ProgramFiles(x86)%\\Ubisoft\\Ubisoft Game Launcher\\upc.exe\" goto dd_prereq_done\r\n" +
            "start \"\" /wait \"%~dp0Uplay\\UplayInstaller.exe\" /S\r\n:dd_prereq_done\r\n"
        assertTrue(text, text.contains(block))
        val game = "set \"DD_X=\" & \"%~dp0UplayLaunch.exe\" -UplayId=2688 -EpicPortal -epicuserid=acc -AUTH_LOGIN=unused -AUTH_PASSWORD=%DD_X% -AUTH_TYPE=exchangecode %DD_OVT%\r\n"
        assertTrue(text, text.contains(game))
        assertTrue(text.indexOf(block) < text.indexOf(game))
        assertFalse(text.contains("epic-prereq-"))
        // the manifest's own arguments win over the /S default
        val withArgs = StoreLaunch.launcherText(wd2.copy(extra = wd2.extra + (com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_ARGS to "/S /D=x")))
        assertTrue(withArgs.contains("start \"\" /wait \"%~dp0Uplay\\UplayInstaller.exe\" /S /D=x\r\n"))
    }

    @Test fun anUnknownEpicPrerequisiteRunsOncePerPrefixAndOnlyWithItsArguments() {
        val eac = StoreGameSidecar(Store.EPIC, "Sugar", "Rocket League", "Binaries/Win64/RocketLeague.exe", extra = mapOf(
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_NAME to "EAC_Service",
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_PATH to "Binaries/Win64/EasyAntiCheat/EasyAntiCheat_EOS_Setup.exe",
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_ARGS to "install 1234 & del x",
        ))
        val lines = StoreLaunch.prereqLines(eac)
        val exe = "\"%~dp0Binaries\\Win64\\EasyAntiCheat\\EasyAntiCheat_EOS_Setup.exe\""
        assertEquals("if not exist $exe goto dd_prereq_done", lines[1])
        assertTrue(lines[2], lines[2].matches(Regex("if exist \"%ProgramData%\\\\DroidDeck\\\\epic-prereq-Sugar-[0-9a-f]{8}\\.done\" goto dd_prereq_done")))
        assertEquals("start \"\" /wait $exe install 1234  del x", lines[3])
        assertEquals("set \"DD_RC=%errorlevel%\"", lines[4])
        assertTrue(lines[6], lines[6].startsWith(">\"%ProgramData%\\DroidDeck\\epic-prereq-Sugar-") && lines[6].endsWith(".done\" echo rc=%DD_RC%"))
        assertEquals(":dd_prereq_done", lines.last())
        // without its arguments (an install read before they were kept) it does not run: it might wait for clicks
        assertTrue(StoreLaunch.prereqLines(eac.copy(extra = eac.extra - com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_ARGS)).isEmpty())
        // a known runtime is the Windows components' job; paths out of the game folder and other stores never run
        val ue = mapOf(
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_NAME to "UE4 Prerequisites (x64)",
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_PATH to "Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe",
            com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_ARGS to "/quiet",
        )
        assertTrue(StoreLaunch.prereqLines(eac.copy(extra = ue)).isEmpty())
        for (bad in listOf("../x/setup.exe", "C:/x/setup.exe", "/x/setup.exe", "a//setup.exe", "a/\"b.exe"))
            assertTrue(bad, StoreLaunch.prereqLines(eac.copy(extra = eac.extra + (com.droiddeck.launcher.frontend.WinCompSources.EPIC_PREREQ_PATH to bad))).isEmpty())
        assertTrue(StoreLaunch.prereqLines(StoreGameSidecar(Store.GOG, "Sugar", "Rocket League", "RocketLeague.exe", extra = eac.extra)).isEmpty())
        assertTrue(StoreLaunch.prereqLines(sample).isEmpty())
    }

    @Test fun gogLauncherHasNoEpicBlockAndIsOnlyWrittenWhenNeeded() {
        val plain = StoreGameSidecar(Store.GOG, "1", "T", "game.exe")
        val text = StoreLaunch.launcherText(plain.copy(args = listOf("-windowed")))
        assertFalse(text.contains("exchangecode"))
        assertTrue(text.contains("\"%~dp0game.exe\" -windowed\r\n"))
        assertFalse(text.contains("DD_X"))
        assertEquals("\"two words\"", StoreLaunch.quoteArg("two words"))
        assertEquals("-x=\"a b\"", StoreLaunch.quoteArg("-x=\"a b\""))
    }
}
