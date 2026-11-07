package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UnlockFrameLimitPrefTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun onByDefaultAndPassedToTheSession() {
        assertTrue(SessionPrefs.unlockFrameLimit(app))
        assertEquals("BL_UNLOCK_FRAME_LIMIT=1", SessionPrefs.unlockFrameLimitEnv(app))
    }

    @Test fun offKeepsTheUsersSteamLimit() {
        SessionPrefs.setUnlockFrameLimit(app, false)
        assertFalse(SessionPrefs.unlockFrameLimit(app))
        assertEquals("BL_UNLOCK_FRAME_LIMIT=0", SessionPrefs.unlockFrameLimitEnv(app))
    }

    @Test fun resetTurnsItBackOn() {
        SessionPrefs.setUnlockFrameLimit(app, false)
        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))
        assertTrue(SessionPrefs.unlockFrameLimit(app))
    }
}
