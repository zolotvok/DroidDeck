package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BoostOverrideTest {
    @Test fun `BL_GAME_BOOST 1 or 0 beats the switch, anything else leaves it`() {
        assertEquals(true, SessionPrefs.boostOverride("1"))
        assertEquals(false, SessionPrefs.boostOverride("0"))
        assertEquals(true, SessionPrefs.boostOverride(" 1 "))
        assertEquals(null, SessionPrefs.boostOverride(null))
        assertEquals(null, SessionPrefs.boostOverride(""))
        assertEquals(null, SessionPrefs.boostOverride("yes"))
    }

    @Test fun `the boost is off by default off the Odin 3`() {
        // Robolectric/JVM: SDK and SOC_MODEL are not the Odin 3's.
        assertFalse(GameThreadHints.measuredHere)
    }
}
