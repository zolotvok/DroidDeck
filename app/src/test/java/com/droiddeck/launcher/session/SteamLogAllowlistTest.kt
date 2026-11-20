package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLogAllowlistTest {
    @Test fun diagnosisLogsPass() {
        for (name in listOf("console_log.txt", "console_log.previous.txt", "gameprocess_log.txt", "compat_log.txt", "shader_log.txt",
            "bootstrap_log.txt", "cef_log.txt", "webhelper.txt", "webhelper_js.txt", "steamwebhelper.log", "controller.txt",
            "vrcompositor.txt", "vrserver.txt", "vrstartup.txt", "vrmonitor.txt", "vrclient_steam.txt", "vrclient_vrcompositor.txt",
            "droiddeck-vrstartup.log")) assertTrue(name, SteamLogAllowlist.allowed(name))
    }

    @Test fun accountLogsAndAnythingUnknownStayOut() {
        for (name in listOf("cloud_log.txt", "connection_log.txt", "content_log.txt", "transaction_log.txt", "parental_log.txt",
            "configstore_log.txt", "librarysharing_log.txt", "stats_log.txt", "steamui_login.txt", "workshop_log.txt",
            "console_log.txt.bak", "deep/console_log.txt", "", "console_log", "vrclient_.txt")) assertFalse(name, SteamLogAllowlist.allowed(name))
    }
}
