package com.droiddeck.launcher.session

/**
 * The Steam client logs a session folder and a report may carry. Steam's logs/ folder also holds account-bearing logs
 * (cloud_log: per-user cloud paths, parental_log: the account id, librarysharing_log: the family group's name,
 * configstore_log: userdata paths, connection_log, content_log, transaction/purchase logs), so only these names pass.
 * The default is exclude.
 */
object SteamLogAllowlist {
    private val NAMES = setOf(
        // the Steam client console: compat tool start, launch errors, PostVRRuntimeCall (vr-erc-1120)
        "console_log",
        // game process start/stop and exit codes per AppID (vr-erc-1120, grid_2)
        "gameprocess_log",
        // which compat tool an app was given (grid_2)
        "compat_log",
        // shader pre-caching and Vulkan shader processing
        "shader_log",
        // client start-up and self-update (steam-restart-1114)
        "bootstrap_log",
        // the Steam UI's web helper: GPU context loss, hangs, restarts (steam-restart-1114, webhelper-mem test plans)
        "cef_log",
        "webhelper",
        "steamwebhelper",
        // the Steam UI's JS console: login/library state (steam-restart-1114) and the VR launch hook's errors
        "webhelper_js",
        // controller config loads (steam-restart-1114)
        "controller",
        // SteamVR (vr-erc-1120)
        "vrcompositor",
        "vrserver",
        "vrstartup",
        "vrmonitor",
        "droiddeck-vrstartup",
    )

    private val VR_CLIENT = Regex("vrclient_[a-z0-9_]+")
    private val NAME = Regex("([A-Za-z0-9_-]+?)(?:\\.previous)?\\.(?:txt|log)")

    /** A file of a session folder by its path relative to the folder: anything under steam/ only as an allowlisted
     *  plain name (nothing nested); other files are not this list's business. */
    fun sessionFileAllowed(relative: String): Boolean =
        !relative.startsWith("steam/") || allowed(relative.removePrefix("steam/"))

    fun allowed(fileName: String): Boolean {
        val base = NAME.matchEntire(fileName)?.groupValues?.get(1) ?: return false
        return base in NAMES || VR_CLIENT.matches(base)
    }
}
