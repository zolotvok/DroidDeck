package com.droiddeck.launcher.gpu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamVrActionLogTest {
    @Test fun aStartThatDiedIsAFailureWithItsReason() {
        val answer = JSONObject().put("ok", true).put("action", "start").put("exitCode", 1)
            .put("output", "SteamVR stopped during start-up: Fri Nov 20 2026 18:04:15.434406 - [Error] - Failed to create VkDevice.; logs in /x")
        val ok = SteamVr.succeeded("start", answer)
        assertFalse(ok)
        assertEquals("SteamVR start: FAILED, exit 1: SteamVR stopped during start-up: Fri Nov 20 2026 18:04:15.434406 - " +
            "[Error] - Failed to create VkDevice.; logs in /x", SteamVr.describe("start", answer, ok))
    }

    @Test fun aStartThatRunsIsLoggedAsOk() {
        val answer = JSONObject().put("ok", true).put("exitCode", 0).put("output", "SteamVR starting; logs in /x\n")
        assertEquals("SteamVR start: ok, exit 0: SteamVR starting; logs in /x", SteamVr.describe("start", answer, true))
    }
}
