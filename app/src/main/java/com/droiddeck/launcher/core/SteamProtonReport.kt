package com.droiddeck.launcher.core

import java.io.File

/**
 * Steam and Proton facts for the session report ("Report a problem"), read from the runtime's files only. Privacy: tool
 * names and versions only (sanitized), no account data, no paths. [runtimeRoot] = LinuxRuntime.rootDir.
 */
object SteamProtonReport {
    /** Where droiddeck-proton-run looks for Windows compatibility tools (HOME=/root), relative to the runtime root. */
    private val TOOL_DIRS = listOf(
        "root/.local/share/Steam/compatibilitytools.d", "usr/share/steam/compatibilitytools.d",
        "usr/local/share/steam/compatibilitytools.d",
    )
    /** The ARM64 Protons Steam downloads (appid to name), the sources of droiddeck-proton-arm64 / -11-arm64. */
    private val PROTON_APPS = listOf("4427310" to "Proton Experimental (ARM64)", "4628740" to "Proton 11.0 (ARM64)")
    private val SAFE = Regex("[^A-Za-z0-9 ._()+:-]")
    private fun safe(s: String): String = SAFE.replace(s, "_").trim().take(80)
    private fun read(file: File): String? = runCatching { file.takeIf { it.isFile && it.length() < 1_000_000 }?.readText() }.getOrNull()
    private fun vdf(text: String, key: String): String? = Regex("\"$key\"\\s+\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    /** Proton's own version file: "<timestamp> <name>" -> "<name>". */
    private fun version(dir: File): String? = read(File(dir, "version"))?.lineSequence()?.firstOrNull()?.trim()
        ?.split(Regex("\\s+"))?.let { if (it.size > 1) it[1] else it.firstOrNull() }?.ifEmpty { null }?.let(::safe)

    /** Windows compat tools as droiddeck-proton-run sees them: "<dir> (<display name>)[, no launcher]", or empty. */
    fun windowsTools(runtimeRoot: File): List<String> = TOOL_DIRS.flatMap { base ->
        File(runtimeRoot, base).listFiles().orEmpty().sortedBy { it.name }.mapNotNull { dir ->
            val text = read(File(dir, "compatibilitytool.vdf")) ?: return@mapNotNull null
            if (!Regex("\"from_oslist\"\\s+\"[^\"]*windows", RegexOption.IGNORE_CASE).containsMatchIn(text)) return@mapNotNull null
            val display = vdf(text, "display_name")?.let(::safe)?.takeIf { it.isNotEmpty() }
            val launcher = read(File(dir, "toolmanifest.vdf"))?.let { vdf(it, "commandline") } != null
            safe(dir.name) + (display?.let { " ($it)" } ?: "") + (version(dir)?.let { " $it" } ?: "") +
                (if (launcher) "" else ", no launcher")
        }
    }

    /** The ARM64 Proton Steam apps in the main library: one line each. */
    fun protonApps(runtimeRoot: File): List<Pair<String, String>> {
        val steam = File(runtimeRoot, "root/.local/share/Steam")
        return PROTON_APPS.map { (id, name) ->
            val manifest = read(File(steam, "steamapps/appmanifest_$id.acf"))
            name to if (manifest == null) "not downloaded" else {
                val flags = vdf(manifest, "StateFlags")?.toIntOrNull() ?: 0
                val dir = vdf(manifest, "installdir")?.takeIf { it.isNotEmpty() && '/' !in it && it != "." && it != ".." }
                val v = dir?.let { version(File(steam, "steamapps/common/$it")) }
                (if (flags == 4) "installed" else "not complete (StateFlags $flags)") + (v?.let { ", version $it" } ?: "")
            }
        }
    }

    /**
     * Has the Steam client finished its first start? Parts: client installed (droiddeck-steam-install's stamp + client
     * binary), starts that never showed the UI (droiddeck-unconfirmed, cleared on the first frame of a Steam session),
     * ever logged in (loginusers.vdf present; contents are not read). The report is written before the guest starts, so
     * this is the state earlier sessions left. [x64] = the x86-64 client in root/.droiddeck-x64.
     */
    fun steamState(runtimeRoot: File, x64: Boolean): List<Pair<String, String>> {
        val home = File(runtimeRoot, if (x64) "root/.droiddeck-x64" else "root")
        val steam = File(home, ".local/share/Steam")
        val stamped = File(steam, "package/droiddeck-installed").isFile || File(steam, "package/bannerlator-installed").isFile
        val binary = File(steam, if (x64) "steamrt64/steam" else "steamrtarm64/steam").isFile
        val installed = stamped && binary
        val starts = read(File(steam, "package/droiddeck-unconfirmed"))?.lines()?.count { it.trim() == "start" } ?: 0
        val login = File(steam, "config/loginusers.vdf").let { it.isFile && it.length() > 0 }
        val ready = installed && login && starts == 0
        return listOf(
            "Steam client (${if (x64) "x86-64" else "arm64"})" to if (installed) "installed" else "not installed",
            "  starts without UI (before this session)" to starts.toString(),
            "  ever logged in" to if (login) "yes" else "no",
            "Steam bootstrapped (before this session)" to if (ready) "yes" else "no",
        )
    }
}
