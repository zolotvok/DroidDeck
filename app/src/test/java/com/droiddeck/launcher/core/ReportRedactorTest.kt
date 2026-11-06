package com.droiddeck.launcher.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class ReportRedactorTest {
    @After fun forget() {
        ReportRedactor.learn(emptyList())
        ReportRedactor.allowHashes(emptyList())
        ReportRedactor.learnApps(emptyList(), "com.droiddeck.launcher")
    }

    @Test fun knownComponentHashesStayOtherHexRunsGo() {
        val shim = "caf6c3c0fe8d4317bfaa1a852f049bfd"
        val fex = "a2448b03c0ffee00112233445566778a"
        val zipSha = "d1fd242b" + "0".repeat(56)
        ReportRedactor.allowHashes(listOf(shim, fex.uppercase(), zipSha))
        assertEquals("| Blobferry | 1.0.0-rc1 @ a28a42d (adb), shim md5 $shim |",
            ReportRedactor.redact("| Blobferry | 1.0.0-rc1 @ a28a42d (adb), shim md5 $shim |"))
        assertEquals("| FEX DLL md5 | $fex |", ReportRedactor.redact("| FEX DLL md5 | $fex |"))
        assertEquals("$shim  usr/lib/mali_bridge/libvulkan_mali_bridge.so, $fex x", ReportRedactor.redact("$shim  usr/lib/mali_bridge/libvulkan_mali_bridge.so, $fex x"))
        assertEquals("sha256 $zipSha", ReportRedactor.redact("sha256 $zipSha"))
        // an unknown 32-hex run is still a possible Web API key
        val key = "0123456789abcdef0123456789abcdef"
        clean("steam webapi $key", key)
        // a known hash in a secret slot is still a secret
        clean("access_token=$shim", shim)
        clean("\"password\": \"$fex\"", fex)
    }

    @Test fun manyKnownHashesOnOneLine() {
        val hashes = (0 until 30).map { i -> "%032x".format(i + 1L shl 60 or 0x5a5aL) }
        ReportRedactor.allowHashes(hashes)
        val line = hashes.joinToString(" ")
        assertEquals(line, ReportRedactor.redact(line))
    }

    @Test fun shellPromptsAndSshTargetsLoseUserAndHost() {
        clean("zolotvok@fedora:~/Droid-Deck_Mali_Support$ ls", "zolotvok", "fedora")
        clean("[zolotvok@fedora bridge]$ make", "zolotvok", "fedora")
        clean("jane@laptop ~ % ls", "jane", "laptop")
        clean("ran: ssh -p 2222 jdoe@192.168.1.20 uptime", "jdoe")
        clean("scp build.zip jdoe@buildbox.example.org:/tmp/", "jdoe", "buildbox")
        // the guest's own prompt says nothing about the user
        assertEquals("root@localhost:~# ls", ReportRedactor.redact("root@localhost:~# ls"))
        assertEquals("root@DroidDeck:/# id", ReportRedactor.redact("root@DroidDeck:/# id"))
    }

    @Test fun benignAtSignsSurvive() {
        for (line in listOf(
            "Updating mode for xwayland server #0: 1600x720@120",
            "info:  Vulkan: Found vkGetInstanceProcAddr in winevulkan.dll @ 0x6ffa030cc0",
            "Details: /usr/bin/lsof -P -F upnR -i TCP@127.0.0.1:32843",
            "\"Unknown convar: @bSearchSuggestionNameMatchScoring\", source: https://steamloopback.host/library.js (1)",
            "OnOutputMode: 1600x720: 1600 x 720 @ 60 - 60",
            "trying to find modeid that matches: 1600x720@60.0",
            "| DroidDeck | 0.6.9-mali.1-rc2 (19) (com.droiddeck.launcher), release, debuggable @ aa74271cfa |",
            "memcpy@GLIBC_2.17 and _ZN3foo3barEv@@Base",
        )) assertEquals(line, ReportRedactor.redact(line))
    }

    @Test fun otherAppsArePackageNamesRemoved() {
        ReportRedactor.learnApps(listOf("com.whatsapp", "com.google.android.apps.photos", "com.droiddeck.launcher",
            "com.droiddeck.launcher.mali", "android"), "com.droiddeck.launcher")
        clean("lmkd: Kill 'com.whatsapp' (12345), uid 10234, oom_score_adj 900", "whatsapp")
        clean("libc: Fatal signal 11 in tid 77 (RenderThread), pid 70 (com.google.android.apps.photos:sync)", "photos")
        clean("lmkd: Kill 'oid.apps.photos' (1), uid 1", "apps.photos")  // the kernel keeps the last 15 characters
        clean("ActivityManager: Start proc com.whatsapp/.Main for activity", "whatsapp")
        val ours = "ActivityManager: Start proc 123:com.droiddeck.launcher/u0a361 for top-activity {com.droiddeck.launcher/.MainActivity}"
        assertEquals(ours, ReportRedactor.redact(ours))
        assertEquals("loaded libVkLayer_droiddeck_mali_compat.so and org.freedesktop.DBus", ReportRedactor.redact("loaded libVkLayer_droiddeck_mali_compat.so and org.freedesktop.DBus"))
    }

    private fun clean(line: String, vararg secrets: String): String {
        val out = ReportRedactor.redact(line)
        for (s in secrets) assertFalse("'$s' survived in: $out", out.contains(s))
        return out
    }

    @Test fun epicLaunchArgumentsLoseTheExchangeCodeAndTheAccount() {
        val code = "8f3a1c2b9d4e4f5a8b7c6d5e4f3a2b1c"
        val user = "1a2b3c4d5e6f47a8b9c0d1e2f3a4b5c6"
        val out = clean(
            "\"C:\\Games\\Fortnite\\FortniteLauncher.exe\" -AUTH_LOGIN=unused -AUTH_PASSWORD=$code -AUTH_TYPE=exchangecode " +
                "-epicapp=Fortnite -epicenv=Prod -EpicPortal -epicusername=\"Zolo Gamer\" -epicuserid=$user -epiclocale=en",
            code, user, "Zolo Gamer",
        )
        assertTrue(out.contains("-AUTH_PASSWORD=<redacted:token>"))
        assertTrue(out.contains("-AUTH_TYPE=exchangecode"))
        assertTrue(out.contains("-epicapp=Fortnite"))
    }

    @Test fun epicExchangeCodeJsonAndFormBodyAreRemoved() {
        val code = "5b8d0c7e1f2a4b3c9d8e7f6a5b4c3d2e"
        clean("{\"expiresInSeconds\":300,\"code\":\"$code\",\"creatingClientId\":\"34a02cf8f4414e29b15921876da36f9a\"}", code)
        val form = "0d1c2b3a4f5e6d7c8b9a0f1e2d3c4b5a"
        val out = clean("body grant_type=exchange_code&exchange_code=$form&token_type=eg1", form)
        assertTrue(out.contains("grant_type=exchange_code"))
        clean("exchangeCode: 'q9W8e7R6t5Y4u3I2o1P0aSdF'", "q9W8e7R6t5Y4u3I2o1P0aSdF")
    }

    @Test fun oauthTokenResponseKeepsItsShapeButNoSecrets() {
        val access = "eg1~eyJraWQiOiJ0RkMyVUloRnBUTV9FYTNxY09kX01xUVQxY0JCbTlrRjkxX2NhSXJDcFdNIiwiYWxnIjoiUlMyNTYifQ.eyJhcHAiOiJsYXVuY2hlciJ9.c2lnbmF0dXJlX2hlcmU"
        val refresh = "eg1~eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abcDEF123456"
        val out = clean(
            "{\"access_token\":\"$access\",\"expires_in\":7200,\"refresh_token\":\"$refresh\"," +
                "\"account_id\":\"1a2b3c4d5e6f47a8b9c0d1e2f3a4b5c6\",\"displayName\":\"Zolo Gamer\"}",
            "eyJraWQi", "abcDEF123456", "1a2b3c4d5e6f47a8b9c0d1e2f3a4b5c6", "Zolo Gamer",
        )
        assertTrue(out.contains("\"expires_in\":7200"))
        assertTrue(out.contains("\"displayName\":\"<redacted:account>\""))
    }

    @Test fun bearerAndUbisoftTicketsAreRemoved() {
        val ubi = "ewogICJ2ZXIiOiAiMSIsCiAgImFpZCI6ICJmNjhhNGJiNS1mMzA4In0.c2lnbmF0dXJl"
        clean("upc: GET /v3/profiles/me headers Ubi_v1 t=$ubi", ubi)
        clean("ticket=$ubi&appId=f68a4bb5", ubi)
        val bearer = "8d7f6e5d4c3b2a1908f7e6d5c4b3a291"
        clean("\"Authorization\": \"bearer $bearer\"", bearer)
        clean("retrying with Bearer abcdefghijklmnopqrstuvwx", "abcdefghijklmnopqrstuvwx")
        clean("Ubi-SessionId: 2c7d3f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", "2c7d3f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f")
    }

    @Test fun steamConfigValuesAndCookiesAreRemoved() {
        val jwt = "eyJ0eXAiOiAiSldUIiwgImFsZyI6ICJFZERTQSIgfQ.eyJpc3MiOiJyOkExQjIiLCJzdWIiOiI3NjU2MTE5OCJ9.c2lnbmF0dXJlX2J5dGVz"
        clean("\t\t\"AccessToken\"\t\t\"$jwt\"", "eyJ0eXAi")
        val out = clean("\t\t\"AccountName\"\t\t\"zolotvok_steam\"", "zolotvok_steam")
        assertTrue(out.contains("<redacted:account>"))
        clean("Cookie: steamLoginSecure=76561198012345678%7C%7CeyAidHlwIjogIkpXVCIsICJhbGciOiAiRWREU0EiIH0", "eyAidHlw", "76561198012345678")
        assertEquals("ssfn<redacted:n> written", ReportRedactor.redact("ssfn1234567890123456789 written"))
        clean("Logged in as 'zolotvok_steam' (persona)", "zolotvok_steam")
    }

    @Test fun learnedNamesAreRemovedEverywhere() {
        ReportRedactor.learn(listOf("Zolo Gamer", "Zoltan's S24", "steam"))
        clean("Ubisoft Connect: welcome back, Zolo Gamer!", "Zolo Gamer")
        clean("bluetooth name Zoltan's S24", "Zoltan's S24")
        assertEquals("steam started", ReportRedactor.redact("steam started"))
    }

    @Test fun homeAppAndStoragePathsAreShortened() {
        assertEquals("open <app-data>/files/linuxfs/root/steam-42.log",
            ReportRedactor.redact("open /data/user/0/com.droiddeck.launcher.mali/files/linuxfs/root/steam-42.log"))
        assertEquals("<storage>/Download/x.zip", ReportRedactor.redact("/storage/emulated/0/Download/x.zip"))
        assertEquals("/storage/<card>/Games", ReportRedactor.redact("/storage/1A2B-3C4D/Games"))
        clean("cd /home/zolotvok/games && ls", "zolotvok")
        val win = clean("load C:\\users\\Zoltan\\AppData\\Local\\x.dll", "Zoltan")
        assertTrue(win.contains("C:\\users\\<redacted:user>\\AppData"))
        assertEquals("C:\\users\\steamuser\\Saved Games", ReportRedactor.redact("C:\\users\\steamuser\\Saved Games"))
        clean("pfx/drive_c/users/zolo/Documents", "zolo/")
    }

    @Test fun deviceIdentifiersAreRemoved() {
        clean("[ro.serialno]: [R5CT1234ABC]", "R5CT1234ABC")
        clean("wifi SSID: \"Zolo-Home-5G\" connected", "Zolo-Home-5G")
        clean("imei=356938035643809", "356938035643809")
        clean("user someone@example.com logged on", "someone@example.com")
    }

    @Test fun ordinaryDiagnosticsSurvive() {
        for (line in listOf(
            "mali-compat: 3 uniform BroadcastFirst replaced in a shader (vendor compiler abort)",
            "err:seh:NtRaiseException Unhandled exception code c0000005 flags 0 addr 0x140001000",
            "info:  DXVK: v2.4 (Proton)",
            "VK_ERROR_DEVICE_LOST in vkQueueSubmit",
            "\"code\": \"errors.com.epicgames.account.invalid_grant\"",
        )) assertEquals(line, ReportRedactor.redact(line))
    }

    @Test fun streamScrubCountsHitsAndHandlesBadBytes() {
        val input = ("refresh_token=abcdef123456\n" + "plain line\r\n").toByteArray() + byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte())
        val out = StringWriter()
        val stats = ReportRedactor.scrub(input.inputStream(), out)
        assertEquals(3, stats.lines)
        assertTrue(stats.hits >= 1)
        assertFalse(out.toString().contains("abcdef123456"))
        assertTrue(out.toString().contains("plain line\n"))
    }

    @Test fun utf16FilesAreWithheld() {
        val out = StringWriter()
        ReportRedactor.scrub(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0).inputStream(), out)
        assertEquals("<unsupported encoding; file withheld>\n", out.toString())
    }
}
