package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamProtonReportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(root: File, rel: String, text: String) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun freshRuntimeHasNoProtonAndNoSteam() {
        val root = tmp.newFolder("rt")
        assertEquals(emptyList<String>(), SteamProtonReport.windowsTools(root))
        assertEquals(listOf("Proton Experimental (ARM64)" to "not downloaded", "Proton 11.0 (ARM64)" to "not downloaded"),
            SteamProtonReport.protonApps(root))
        assertEquals(listOf("Steam client (arm64)" to "not installed", "  starts without UI (before this session)" to "0",
            "  ever logged in" to "no", "Steam bootstrapped (before this session)" to "no"), SteamProtonReport.steamState(root, x64 = false))
    }

    @Test fun bootstrappedSteamWithProton() {
        val root = tmp.newFolder("rt")
        val steam = "root/.local/share/Steam"
        put(root, "$steam/package/droiddeck-installed", "1\n")
        put(root, "$steam/steamrtarm64/steam", "")
        put(root, "$steam/config/loginusers.vdf", "\"users\" { \"7656\" { \"AccountName\" \"secret-name\" } }")
        put(root, "$steam/compatibilitytools.d/droiddeck-proton-arm64/compatibilitytool.vdf",
            "\"compatibilitytools\" { \"compat_tools\" { \"droiddeck-proton-arm64\" { \"display_name\" \"DroidDeck Proton (ARM64)\" \"from_oslist\" \"windows\" } } }")
        put(root, "$steam/compatibilitytools.d/droiddeck-proton-arm64/toolmanifest.vdf", "\"manifest\" { \"commandline\" \"/droiddeck-proton %verb%\" }")
        put(root, "$steam/compatibilitytools.d/droiddeck-fex/compatibilitytool.vdf", "\"from_oslist\" \"linux\"")
        put(root, "$steam/steamapps/appmanifest_4427310.acf", "\"AppState\" { \"StateFlags\" \"4\" \"installdir\" \"Proton - Experimental\" }")
        put(root, "$steam/steamapps/common/Proton - Experimental/version", "1763000000 experimental-10.0-20251110\n")
        put(root, "$steam/steamapps/appmanifest_4628740.acf", "\"AppState\" { \"StateFlags\" \"1026\" \"installdir\" \"Proton 11.0\" }")
        assertEquals(listOf("droiddeck-proton-arm64 (DroidDeck Proton (ARM64))"), SteamProtonReport.windowsTools(root))
        assertEquals(listOf("Proton Experimental (ARM64)" to "installed, version experimental-10.0-20251110",
            "Proton 11.0 (ARM64)" to "not complete (StateFlags 1026)"), SteamProtonReport.protonApps(root))
        val state = SteamProtonReport.steamState(root, x64 = false)
        assertEquals("yes", state.last().second)
        assertEquals(false, state.any { "secret-name" in it.second || "7656" in it.second })
        put(root, "$steam/package/droiddeck-unconfirmed", "start\nstart\n")
        assertEquals(listOf("  starts without UI (before this session)" to "2", "Steam bootstrapped (before this session)" to "no"),
            SteamProtonReport.steamState(root, x64 = false).filter { it.first.contains("starts") || it.first.contains("bootstrapped") })
    }
}
