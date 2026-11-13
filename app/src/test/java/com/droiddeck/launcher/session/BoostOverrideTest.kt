package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.GameEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    @Test fun `a game's own BL_GAME_BOOST beats the shared one, which beats the switch`() {
        fun config(shared: String?, game: String?) = GameEnvironment.Config(
            shared = if (shared == null) emptyMap() else mapOf("BL_GAME_BOOST" to shared),
            games = if (game == null) emptyMap() else mapOf("292030" to mapOf("BL_GAME_BOOST" to game)),
        )
        assertTrue(SessionPrefs.boostFor(config(null, "1"), "292030", false))
        assertFalse(SessionPrefs.boostFor(config(null, "0"), "292030", true))
        assertTrue(SessionPrefs.boostFor(config("0", "1"), "292030", false))
        assertFalse(SessionPrefs.boostFor(config("1", "0"), "292030", true))
        assertFalse(SessionPrefs.boostFor(config("0", null), "292030", true))
        assertTrue(SessionPrefs.boostFor(config("1", null), "292030", false))
        assertTrue(SessionPrefs.boostFor(config(null, null), "292030", true))
        // garbage falls through, game to shared to switch
        assertFalse(SessionPrefs.boostFor(config("0", "yes"), "292030", true))
        assertTrue(SessionPrefs.boostFor(config("on", "maybe"), "292030", true))
        // another game, or no known game: the game entry does not apply
        assertFalse(SessionPrefs.boostFor(config(null, "1"), "447040", false))
        assertFalse(SessionPrefs.boostFor(config(null, "1"), null, false))
        assertFalse(SessionPrefs.boostFor(config(null, "1"), "", false))
    }

    @Test fun `a bad game key in the store does not hide the other overrides`() {
        val json = JSONObject("""{"version":1,"shared":{"BL_GAME_BOOST":"0"},"games":{
            "epic:Jasper":{"BL_GAME_BOOST":"1"},"292030":{"BL_GAME_BOOST":"1","bad name":"x"}}}""")
        val config = GameEnvironmentStore.decodeLenient(json)!!
        assertEquals(setOf("292030"), config.games.keys)
        assertEquals(mapOf("BL_GAME_BOOST" to "1"), config.games["292030"])
        assertTrue(SessionPrefs.boostFor(config, "292030", false))
        assertFalse(SessionPrefs.boostFor(config, "447040", true))
        assertEquals(null, GameEnvironmentStore.decodeLenient(JSONObject("""{"version":2}""")))
    }
}
