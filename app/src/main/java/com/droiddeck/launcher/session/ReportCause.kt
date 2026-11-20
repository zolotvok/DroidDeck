package com.droiddeck.launcher.session

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What the report summary names first: the cause of the last game launch's failure, read from its Proton log, the
 * game-env lines of session.log and the compositor's frame statistics in logcat. Plain text in, plain text out.
 */
object ReportCause {
    /** Start-up lines every Proton launch here prints; they never name the failure (docs/analysis/yooka-rc2-1120.md
     *  section 4). The same list as STARTUP_NOISE in droiddeck-game-env. */
    val STARTUP_NOISE = listOf(
        "ld\\.so: object '[^']*gameoverlayrenderer\\.so' from LD_PRELOAD cannot be preloaded",
        "err:wineusb:usb_init Failed to initialize libusb",
        "err:ntoskrnl:ZwLoadDriver failed to create driver .*winebth",
        "err:steamclient:steamclient_init_registry Failed to connect to Steam",
        "OpenXR: Unable to get required Vulkan",
        "D3D11DeviceFeatures: Some formats not supported for resource sharing",
        "QueryInterface: Unknown interface query",
        "^warn:\\s+(?:<redacted:guid>|\\{?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\}?)\\s*$",
        "MakeWindowAssociation: Ignoring flags",
        "xdg_backend:.*Compositor released us but we were not acquired",
        "Proton: Error while copying to .*(?:amdxcffx64|igdext64)\\.dll",
        "Proton: Error: unable to use parent for game drive",
        "unable to check /proc/modules for NV driver presence",
        "Failed to read /etc/machine-id",
        "err:nsi:poll_events bind failed",
        "err:hid:(?:get_container_id_for_usb_udev_device|sdl_bus_init|udev_bus_init)",
    ).map(::Regex)

    /** What Wine prints while a game the session stopped is torn down; noise only after a stop signal. */
    val TEARDOWN_NOISE = listOf(
        "err:virtual:virtual_setup_exception nested exception on signal stack",
        "^terminate called without an active exception",
        "err:seh:call_seh_handlers invalid frame",
        "err:seh:NtRaiseException Exception frame is not in stack limits",
        "^wine client error:\\S+: write: Bad file descriptor",
        "skipping destruction \\(fork without exec\\?\\)",
    ).map(::Regex)

    /** The known-cause patterns, most telling first, with the words the summary uses for each (KNOWN_CAUSES in
     *  droiddeck-game-env: the same labels and patterns, in the same order). A line that matches several is shown
     *  once, under the first. The Vulkan start-up failures go from the driver up: loader, Vulkan result codes, then
     *  what DXVK (Direct3D 8-11) and vkd3d (Direct3D 12) print when they find no usable GPU. */
    val KNOWN_CAUSES = listOf(
        "GPU fault" to "GROUP_ERROR_FATAL|TRANSLATION_FAULT|GPU (?:page )?fault",
        "Vulkan device lost" to "VK_ERROR_DEVICE_LOST",
        "Vendor driver did not load" to "hybris_dlopen\\(.*\\) failed",
        "Vendor Vulkan HAL rejected or missing" to
            "mali-bridge: (?:vendor HAL candidate rejected|no vendor HAL|vendor HAL: none|vendor HAL .*(?:missing|not found|failed))",
        "The Vulkan loader found no GPU driver (no Vulkan ICD was loaded)" to "Found no drivers|\\bNo ICDs\\b",
        "The Vulkan driver is missing or does not fit (VK_ERROR_INCOMPATIBLE_DRIVER)" to "VK_ERROR_INCOMPATIBLE_DRIVER",
        // a swapchain that fails this way is recreated and works (RE Village 11-12..16): no cause on its own
        "The Vulkan driver failed to initialize (VK_ERROR_INITIALIZATION_FAILED)" to
            "^(?!.*swapchain).*VK_ERROR_INITIALIZATION_FAILED",
        "Vulkan could not start (vkCreateInstance failed)" to "vkCreateInstance failed",
        "DXVK could not start Vulkan (it failed to create a Vulkan instance)" to
            "DxvkInstance\\b.*Failed to create Vulkan(?: [0-9.]+)? instance",
        "The game could not start Vulkan (vkd3d or another component failed to create a Vulkan instance)" to
            "^(?!.*DxvkInstance).*Failed to create Vulkan(?: [0-9.]+)? instance",
        // DXVK's "Skipping: Device filter" is no cause on its own: a filter that picks one of two GPUs prints it too
        "The game could not find a usable GPU (DXVK found no Vulkan adapter; check DXVK_FILTER_DEVICE_NAME and the driver)" to
            "DXVK: No adapters found",
        "The game could not find a usable GPU (vkd3d found no Vulkan device)" to
            "No suitable physical device|No physical device available|Failed to enumerate physical devices",
        "The game could not open the GPU (vkd3d failed to create the Vulkan device)" to
            "Failed to create Vulkan device|vkd3d.*Failed to create device",
        "DXVK (Direct3D 8-11 on Vulkan) could not start" to "Failed to initialize DXVK",
    ).map { (label, pattern) -> label to Regex(pattern, RegexOption.IGNORE_CASE) }

    /** A swapchain recreation is routine; these lines are a cause only next to a swapchain destroy that never returned
     *  or a stall (WSI_CAUSE in droiddeck-game-env). */
    val WSI_CAUSES = listOf(
        "Gamescope WSI forced a swapchain recreation" to "\\[Gamescope WSI\\] Forcing swapchain recreation",
        "Swapchain out of date" to "VK_ERROR_OUT_OF_DATE_KHR",
    ).map { (label, pattern) -> label to Regex(pattern, RegexOption.IGNORE_CASE) }

    private val ERR_WARN = Regex("(?:^|:)(?:err|warn):|\\b(?:error|fatal|failed|unhandled|exception)\\b", RegexOption.IGNORE_CASE)
    private val WINE_LINE = Regex("^(\\d+\\.\\d+):[0-9a-fA-F]+:[0-9a-fA-F]+:(err|warn|fixme|trace):")
    private val DESTROYING = Regex("\\[Gamescope WSI\\] Destroying swapchain: (0x[0-9a-fA-F]+)")
    private val DESTROYED = Regex("\\[Gamescope WSI\\] Destroyed swapchain: (0x[0-9a-fA-F]+)")
    private val WSI_LINE = Regex("\\[Gamescope WSI\\]|Presenter: |swapchain", RegexOption.IGNORE_CASE)
    private val ADDRESS = Regex("0x[0-9a-fA-F]+")
    private const val WINE_ERR_SECONDS = 30.0
    const val KEY_LINES = 20
    const val CAUSE_LINES = 12
    /** The signals a stop sends (the session, Steam's Stop); SIGKILL is the low-memory killer's, not a stop. */
    val STOP_SIGNALS = setOf("SIGINT", "SIGTERM", "SIGHUP")

    fun noise(line: String, stopped: Boolean): Boolean =
        STARTUP_NOISE.any { it.containsMatchIn(line) } || (stopped && TEARDOWN_NOISE.any { it.containsMatchIn(line) })

    /** A line without its Wine time/pid/tid prefix and its addresses: repeats of one message share it. */
    fun repeatKey(line: String): String =
        ADDRESS.replace(WINE_LINE.replace(line) { it.groupValues[2] + ":" }, "0x?").trim()

    /** Indexes of `Destroying swapchain` lines the WSI layer never followed with `Destroyed swapchain`. */
    fun hungDestroys(lines: List<String>): List<Int> {
        val open = LinkedHashMap<String, Int>()
        lines.forEachIndexed { i, line ->
            DESTROYING.find(line)?.let { open[it.groupValues[1]] = i; return@forEachIndexed }
            DESTROYED.find(line)?.let { open.remove(it.groupValues[1]) }
        }
        return open.values.sorted()
    }

    class KeyLines(val causes: List<String>, val others: List<String>, val hung: List<String>)

    /** The Proton log's telling lines, each list in log order: known causes (patterns and a swapchain destroy that
     *  never returned with the recreation lines before it, then for a game that was not stopped Wine errors of the
     *  last 30 s), then the last other err/warn lines; noise left out, repeats folded. Same rules as summary_lines in
     *  droiddeck-game-env. */
    fun keyLines(lines: List<String>, stopped: Boolean, max: Int = KEY_LINES, maxCauses: Int = CAUSE_LINES): KeyLines {
        val hung = hungDestroys(lines).toSet()
        val end = lines.mapNotNull { WINE_LINE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }.maxOrNull()
        class Pick(val index: Int, val line: String, val count: Int, val rank: Int)
        val picked = LinkedHashMap<String, Pick>()
        lines.forEachIndexed { i, line ->
            if (noise(line, stopped)) return@forEachIndexed
            val wine = WINE_LINE.find(line)
            val rank = when {
                i in hung || KNOWN_CAUSES.any { it.second.containsMatchIn(line) } ||
                    (hung.isNotEmpty() && WSI_CAUSES.any { it.second.containsMatchIn(line) }) -> 2
                !stopped && wine != null && wine.groupValues[2] == "err" && end != null &&
                    wine.groupValues[1].toDouble() >= end - WINE_ERR_SECONDS -> 1
                else -> 0
            }
            if (rank == 0 && !ERR_WARN.containsMatchIn(line)) return@forEachIndexed
            val key = repeatKey(line)
            val before = picked[key]
            picked[key] = Pick(i, line, (before?.count ?: 0) + 1, maxOf(rank, before?.rank ?: 0))
        }
        fun shown(p: Pick) = if (p.count == 1) p.line else "${p.line} (${p.count} times)"
        val ordered = picked.values.sortedBy { it.index }
        val strong = ordered.filter { it.rank == 2 }.takeLast(maxCauses)
        val late = ordered.filter { it.rank == 1 }.takeLast((maxCauses - strong.size).coerceAtLeast(0))
        val causes = (strong + late).sortedBy { it.index }
        val others = ordered.filter { it.rank == 0 }.takeLast((max - causes.size).coerceAtLeast(0))
        return KeyLines(causes.map(::shown), others.map(::shown), hung.sorted().map { lines[it] })
    }

    /** The last [n] swapchain / WSI lines (distinct, in log order). */
    fun wsiTail(lines: List<String>, n: Int = 4): List<String> =
        lines.filter { WSI_LINE.containsMatchIn(it) && !it.startsWith("info:    ") }.map { it.trim() }
            .asReversed().distinct().take(n).asReversed()

    // ---- the game's exit (droiddeck-game-env) ----

    class Exit(val text: String, val status: Int?, val signal: String?, val seconds: Int?, val endedAtMs: Long?) {
        val stopped: Boolean get() = signal in STOP_SIGNALS
    }

    private val EXIT = Regex(
        "droiddeck-game-env: (game (?:exited with status (\\d+)|ended by (SIG[A-Z0-9]+|signal \\d+))[^;]*?after (\\d+) s[^;]*)" +
            "(?:; ended at unix time (\\d+))?"
    )

    /** The exit of the launch whose game-env lines are [launch] (the last exit line in them), or null. */
    fun exitOf(launch: List<String>): Exit? = launch.asReversed().firstNotNullOfOrNull { EXIT.find(it) }?.let { m ->
        val g = m.groupValues
        Exit(g[1].trim(), g[2].toIntOrNull(), g[3].ifEmpty { null }, g[4].toIntOrNull(), g[5].toLongOrNull()?.times(1000))
    }

    // ---- frames (logcat) ----

    /** One compositor window; [active]: a game drew in it (GPU frames, or redraws of a non-GPU window). */
    class Window(val endMs: Long, val gpuFrames: Int, val redraws: Int = 0) {
        val active: Boolean get() = gpuFrames > 0 || redraws > 0
    }

    private val THREADTIME = Regex("^(\\d\\d)-(\\d\\d)\\s+(\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d{3})")
    private val STATS = Regex("\\[stats\\] last 10 s: .*\\| (\\d+) GPU frames from games \\|(?: (\\d+) window redraws)?")
    private val STOP_REQUEST = Regex("SessionService\\s*: stop requested")

    /** A threadtime stamp (no year) as epoch ms in [zone], in the year that puts it at most a day after [nowMs]. */
    fun logcatTime(line: String, nowMs: Long, zone: TimeZone): Long? {
        val m = THREADTIME.find(line) ?: return null
        val (mo, d, h, mi, s, ms) = m.destructured
        val cal = Calendar.getInstance(zone, Locale.US)
        cal.timeInMillis = nowMs
        val year = cal.get(Calendar.YEAR)
        cal.clear()
        cal.set(year, mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        cal.set(Calendar.MILLISECOND, ms.toInt())
        if (cal.timeInMillis > nowMs + 86_400_000L) cal.add(Calendar.YEAR, -1)
        return cal.timeInMillis
    }

    /** The compositor's 10-second windows: when each ended and how many GPU frames games handed it. */
    fun windows(logcat: List<String>, nowMs: Long, zone: TimeZone): List<Window> = logcat.mapNotNull { line ->
        val m = STATS.find(line) ?: return@mapNotNull null
        val frames = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        logcatTime(line, nowMs, zone)?.let { Window(it, frames, m.groupValues[2].toIntOrNull() ?: 0) }
    }

    fun stopRequests(logcat: List<String>, nowMs: Long, zone: TimeZone): List<Long> =
        logcat.filter { STOP_REQUEST.containsMatchIn(it) }.mapNotNull { logcatTime(it, nowMs, zone) }

    class Stall(val seconds: Int, val drewBefore: Boolean, val untilWhat: String) {
        val text: String get() = if (drewBefore) "Game stalled (no frames for $seconds s $untilWhat)"
            else "Game showed no frames (none in the $seconds s $untilWhat)"
    }

    /**
     * Whether the game stopped drawing: the compositor's windows of this launch end in at least two windows without a
     * game frame. The end is the session's stop request for a stopped game (the process was still alive then), the
     * logged exit time of a failed exit, or the report time when no exit was logged ([notBefore]: the session start).
     * A clean exit (status 0) is no stall: launchers and teardown sit without frames before it.
     */
    fun stall(windows: List<Window>, stops: List<Long>, exit: Exit?, reportMs: Long, notBefore: Long? = null): Stall? {
        if (exit?.status == 0) return null
        val end: Long
        val untilWhat: String
        when {
            exit == null -> { end = reportMs; untilWhat = "before the report; no game exit was logged" }
            exit.stopped -> {
                val at = exit.endedAtMs
                end = (if (at != null) stops.lastOrNull { it in at - 60_000L..at + 2_000L } else stops.lastOrNull()) ?: at ?: return null
                untilWhat = "before it was stopped"
            }
            else -> { end = exit.endedAtMs ?: return null; untilWhat = "before it exited" }
        }
        val start = exit?.seconds?.let { (exit.endedAtMs ?: end) - it * 1000L } ?: notBefore ?: Long.MIN_VALUE
        val run = windows.filter { it.endMs > start && it.endMs <= end + 1_000L }
        val last = run.indexOfLast { it.active }
        val zeros = run.size - 1 - last
        if (zeros < 2) return null
        if (last >= 0) return Stall(((run.last().endMs - run[last].endMs) / 1000).toInt(), true, untilWhat)
        if (exit == null) return null
        return Stall(((run.last().endMs - run.first().endMs) / 1000 + 10).toInt(), false, untilWhat)
    }

    // ---- the summary's "What went wrong" ----

    class Finding(val bullets: List<String>, val wsiLines: List<String>)

    private fun code(line: String) = "`" + line.trim().take(300).replace("`", "'") + "`"

    /** The bullets of "What went wrong", most telling first, and the last swapchain/WSI lines when they matter. */
    fun findings(proton: List<String>, exit: Exit?, stall: Stall?, vulkanErrorLine: String? = null, launched: Boolean = true): Finding {
        val bullets = ArrayList<String>()
        val stopped = exit?.stopped == true
        stall?.let { bullets += it.text + "." }
        val hung = hungDestroys(proton).map { proton[it] }
        hung.lastOrNull()?.let {
            bullets += "A swapchain destroy never returned: ${code(it)} has no `Destroyed swapchain` line" +
                if (stopped) "; the game hung there until it was stopped." else "."
        }
        val shown = HashSet<String>()
        val wsi = stall != null || hung.isNotEmpty()
        for ((label, pattern) in if (wsi) KNOWN_CAUSES + WSI_CAUSES else KNOWN_CAUSES) {
            val line = proton.lastOrNull { pattern.containsMatchIn(it) && !noise(it, stopped) } ?: continue
            if (!shown.add(repeatKey(line))) continue
            bullets += "$label: ${code(line)}"
        }
        if (vulkanErrorLine != null && shown.add(repeatKey(vulkanErrorLine)) && KNOWN_CAUSES.none { it.second.containsMatchIn(vulkanErrorLine) })
            bullets += "Vulkan/driver error: ${code(vulkanErrorLine)}"
        if (bullets.isEmpty()) bullets += "No known cause pattern was found in the logs; the key lines are under \"Last game launch\"."
        bullets += when {
            !launched -> "No game launch was logged in this session; the lines above are from the newest Proton log."
            exit == null -> "No game exit was logged for this launch (the game may still have been running)."
            stopped -> exit.text.replaceFirstChar { it.uppercase() } + " (a stop signal: the session or Steam stopped it)."
            else -> exit.text.replaceFirstChar { it.uppercase() } + "."
        }
        return Finding(bullets, if (wsi) wsiTail(proton) else emptyList())
    }

    /** "What went wrong" for the last launch ([launch]: its game-env lines), or null without a launch and a Proton log. */
    fun analyse(launch: List<String>, proton: List<String>, logcat: List<String>, nowMs: Long, zone: TimeZone,
                vulkanErrorLine: String? = null, sessionStart: Long? = null): Finding? {
        if (launch.isEmpty() && proton.isEmpty()) return null
        val exit = exitOf(launch)
        val stall = if (launch.isEmpty()) null
            else stall(windows(logcat, nowMs, zone), stopRequests(logcat, nowMs, zone), exit, nowMs, sessionStart)
        return findings(proton, exit, stall, vulkanErrorLine, launched = launch.isNotEmpty())
    }
}
