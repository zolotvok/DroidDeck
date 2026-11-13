package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameThreadHintsTest {
    @Test fun `a thread joins above 60 percent and leaves under 15`() {
        assertEquals(emptySet<Int>(), GameThreadHints.pick(emptySet(), mapOf(1 to 0.55)))
        assertEquals(setOf(1), GameThreadHints.pick(emptySet(), mapOf(1 to 0.68)))
        assertEquals(setOf(1), GameThreadHints.pick(setOf(1), mapOf(1 to 0.25)))
        assertEquals(emptySet<Int>(), GameThreadHints.pick(setOf(1), mapOf(1 to 0.1)))
    }

    @Test fun `at most two threads, the boosted ones kept before busier newcomers`() {
        val busy = mapOf(1 to 0.2, 2 to 0.9, 3 to 0.8, 4 to 0.7, 5 to 0.95)
        assertEquals(setOf(1, 5), GameThreadHints.pick(setOf(1), busy))
    }

    @Test fun `a thread gone from proc is dropped`() {
        assertEquals(setOf(2), GameThreadHints.pick(setOf(1, 2), mapOf(2 to 0.7)))
    }

    @Test fun `the log is told why no thread is boosted`() {
        assertEquals("too hot", GameThreadHints.offReason("too hot", emptySet(), false, emptySet()))
        assertEquals(null, GameThreadHints.offReason(null, setOf(1), true, setOf(1)))
        assertEquals("game keeps up", GameThreadHints.offReason(null, emptySet(), true, emptySet()))
        assertEquals("no thread busy enough", GameThreadHints.offReason(null, emptySet(), false, emptySet()))
        assertEquals("Android refused the hint", GameThreadHints.offReason(null, emptySet(), false, setOf(1)))
    }

    @Test fun `a game holds pace only at the panel's rate or 60`() {
        assertTrue(GameThreadHints.holdsPace(119.0, 120.0)) // Serious Sam capped at 120
        assertTrue(GameThreadHints.holdsPace(118.0, 120.0))
        assertTrue(GameThreadHints.holdsPace(60.0, 120.0))
        assertTrue(GameThreadHints.holdsPace(60.5, 120.0))
        assertTrue(GameThreadHints.holdsPace(58.0, 60.0))
        assertFalse(GameThreadHints.holdsPace(39.5, 120.0)) // Watch Dogs standing
        assertFalse(GameThreadHints.holdsPace(28.5, 120.0)) // Watch Dogs 2
        assertFalse(GameThreadHints.holdsPace(30.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(15.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(40.0, 60.0))
        assertFalse(GameThreadHints.holdsPace(92.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(105.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(3.0, 120.0))
    }

    @Test fun `a frame limit below the panel is the game's target`() {
        assertTrue(GameThreadHints.holdsPace(30.0, 120.0, 30))
        assertTrue(GameThreadHints.holdsPace(39.0, 120.0, 40))
        assertFalse(GameThreadHints.holdsPace(39.0, 120.0, 60))
        assertFalse(GameThreadHints.holdsPace(25.0, 120.0, 30))
        assertTrue(GameThreadHints.holdsPace(60.0, 120.0, 90)) // 60 still counts under a limit above it
        assertTrue(GameThreadHints.holdsPace(119.0, 120.0, 144)) // a limit above the panel changes nothing
        assertFalse(GameThreadHints.holdsPace(40.0, 120.0, 144))
        assertTrue(GameThreadHints.holdsPace(118.0, 120.0, SessionPrefs.FPS_UNPACED))
        assertFalse(GameThreadHints.holdsPace(30.0, 120.0, SessionPrefs.FPS_UNPACED))
    }

    @Test fun `the log names the target held`() {
        assertEquals(60.0, GameThreadHints.paceTarget(59.0, 120.0, 0))
        assertEquals(120.0, GameThreadHints.paceTarget(119.0, 120.0, 0))
        assertEquals(40.0, GameThreadHints.paceTarget(39.0, 120.0, 40))
        assertEquals(null, GameThreadHints.paceTarget(39.5, 120.0, 0))
    }

    @Test fun `the game id comes from the compat prefix like droiddeck-game-env's`() {
        assertEquals("292030", GameThreadHints.appIdOfCompatPath("/root/.steam/steam/steamapps/compatdata/292030"))
        assertEquals("447040", GameThreadHints.appIdOfCompatPath("/x/compatdata/447040/"))
        assertEquals("4294967295", GameThreadHints.appIdOfCompatPath("/x/compatdata/-1"))
        assertEquals(null, GameThreadHints.appIdOfCompatPath("/x/compatdata/0"))
        assertEquals(null, GameThreadHints.appIdOfCompatPath("/x/compatdata/pfx"))
        assertEquals(null, GameThreadHints.appIdOfCompatPath(""))
        assertEquals(null, GameThreadHints.appIdOfCompatPath("/x/4294967296"))
    }
}
