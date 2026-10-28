package com.droiddeck.launcher.core

import com.droiddeck.launcher.R

/** Ready-made sets of environment entries for a game's profile, next to the ones the user saves (Config.templates). */
object GameEnvironmentTemplates {
    data class Template(val label: Int, val entries: Map<String, String?>)

    val builtIn = listOf(
        Template(R.string.game_env_tpl_bridge, mapOf("DROIDDECK_GAME_DRIVER" to "bridge")),
        Template(R.string.game_env_tpl_session, mapOf("DROIDDECK_GAME_DRIVER" to "session")),
        Template(R.string.game_env_tpl_measure, mapOf("MALI_WSI_FPS" to "1", "MALI_COMPAT_GPU_TIME" to "1")),
    )
}
