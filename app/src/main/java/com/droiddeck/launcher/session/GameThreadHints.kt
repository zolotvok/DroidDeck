package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The game's busiest threads named to Android's performance hint service (ADPF) for a session.
 *
 * Qualcomm's WALT moves a thread to the prime cores only once it fills a mid core (the Odin 3's
 * sched_upmigrate is 95), and a game thread that waits on its workers or the GPU for part of each
 * frame never does: Factorio's update thread sat at ~68% on cores 0-5 with 6-7 idle, and ran 60%
 * faster pinned to 6-7. An app may not raise a thread's uclamp (EPERM without CAP_SYS_NICE), but a
 * hint session may name any thread of its UID - the guest's are - and one reported behind its
 * target is boosted by the power HAL onto the prime cores: on the Odin 3 a 70%-busy thread of a
 * child process went from cores 0-5 to 6-7 and ran 1.7x faster within a second.
 *
 * Once a second the busiest threads of this UID are read from /proc; one busy more than [ENTER] of
 * the time (averaged with the second before, so a loading spike does not count) joins the session,
 * and leaves it under [LEAVE] - well below, since it runs faster where it was moved. While the
 * session has threads it reports them behind target, which is what asks for the boost: the reports
 * carry no frame timing, the busy share is the signal (a capped game's frame interval looks the same
 * as a slow one's). Nothing is boosted while the device is critically hot or saves power.
 *
 * Nor while the game holds its own target ([paceTarget]): the lowest frame limit in force if one is
 * set below the panel - the compositor's ([SessionState.paceFpsLimit]) or a frame cap in the game's
 * environment ([frameCap]: VKD3D_FRAME_RATE, DXVK_FRAME_RATE, DXVK_CONFIG maxFrameRate) - else the
 * panel's active refresh rate,
 * and also 60 fps on a panel above 60 (the common in-game cap). There is nothing to gain there, and it
 * cost frame pacing - Serious Sam capped at 120 fps ran its boosted main thread on prime cores the
 * governor kept at 2.2 GHz, slower than a 3.5 GHz mid core, and 23-25% of its frames fell under 90 fps
 * against 2-7% unboosted. Lower fractions of the panel (refresh/3, /4: 40 and 30 at 120 Hz) are NOT a
 * pace: Watch Dogs at 39-40 fps and Watch Dogs 2 at 28.5 on a 120 Hz panel were never boosted under
 * that old rule. A game with no frames (Factorio's benchmark, a loading screen) or one falling behind
 * its target is boosted; one holding pace keeps what it has for [PACE_LEAVE_S] seconds and is then let
 * go. A game whose load is low gets nothing anyway: no thread is busy [ENTER] of the time ([pick]).
 * There is no cheap always-on GPU load reading in the app (GpuStatsComponent feeds the HUD only), so
 * "CPU and GPU both idle" is not a separate rule.
 *
 * Part of the Performance boost switch, on by default only where it was measured ([measuredHere]).
 * Another phone's HAL may boost differently, or not at all. `BL_GAME_BOOST=1|0` in a game's own
 * environment entry beats the shared one and the switch for that game ([SessionPrefs.boostFor]): the
 * running game is the busiest process whose environment names a Steam compat prefix
 * (STEAM_COMPAT_DATA_PATH, the id the game environment keys by), else the session's launch id. All
 * Epic titles run under one Steam shortcut, so they share one entry. Whether this thread runs at all
 * is decided once at the session's start (the boost on, or some game entry with `=1`): a `=1` added
 * mid-session takes a session restart; inside a running thread the store and the switch are re-read
 * every [CONFIG_EVERY_S] seconds (a failed read keeps the last good one). While nothing is boosted
 * the thread wakes once a second only. SamsungPerf (Samsung phones) still follows the session-wide
 * boost only, not a game's own entry.
 */
object GameThreadHints {
    private const val TAG = "GameThreadHints"
    private const val TARGET_NS = 10_000_000L
    private const val REPORT_MS = 50L
    private const val SCAN_EVERY = 20
    private const val ENTER = 0.6
    private const val LEAVE = 0.15
    private const val MAX_THREADS = 2
    private const val TOP_PROCESSES = 3
    internal const val PACE_WINDOW_S = 3
    internal const val PACE_LEAVE_S = 10
    private const val PACE_TOLERANCE = 0.08
    private const val COMMON_CAP = 60.0
    private const val CONFIG_EVERY_S = 10
    internal const val GAME_OFF = "off for this game"

    private val NOT_GAMES = setOf("steam", "steamwebhelper")

    /** The Odin 3 (CQ8725S), where the boost was measured. */
    val measuredHere: Boolean get() = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL == "CQ8725S"

    private var worker: Thread? = null

    /** For BoostReport: the last start line, the threads boosted now, why none is, and hint sessions refused. */
    @Volatile internal var startLine = "game thread hints: off"
    @Volatile internal var boostedThreads = emptySet<Int>()
    @Volatile internal var offWhy: String? = null
    @Volatile internal var refused = 0
    /** The running game's frame cap from its environment ([frameCap]), 0 for none; SamsungPerf's pace target uses it too. */
    @Volatile internal var gameFrameCap = 0

    /** At a session's start. The line is for the log. */
    @Synchronized
    fun start(context: Context): String {
        offWhy = "starting"
        return begin(context).also {
            startLine = it
            if (!it.startsWith("game thread hints: watching")) offWhy = it.removePrefix("game thread hints: ")
        }
    }

    private fun begin(context: Context): String {
        refused = 0
        val sessionWide = SessionPrefs.performanceBoostActive(context)
        if (!sessionWide && !SessionPrefs.gameBoostWanted(context)) return "game thread hints: off"
        if (Build.VERSION.SDK_INT < 31) return "game thread hints: needs Android 12"
        val hm = context.getSystemService(PerformanceHintManager::class.java)
            ?: return "game thread hints: no PerformanceHintManager"
        val pm = context.getSystemService(PowerManager::class.java)
        worker?.interrupt()
        val app = context.applicationContext
        worker = Thread({ run(app, hm, pm) }, "game-thread-hints").apply { isDaemon = true; start() }
        return "game thread hints: watching" + if (sessionWide) "" else " (only games with BL_GAME_BOOST=1)"
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
    }

    private fun run(context: Context, hm: PerformanceHintManager, pm: PowerManager) {
        val scanner = Scanner()
        var config = GameEnvironment.Config()
        var switchOn = false
        var lastChoice: String? = null
        var readFailed = false
        var session: PerformanceHintManager.Session? = null
        var boosted = emptySet<Int>()
        val frames = ArrayDeque<Long>()
        var pacedFor = 0
        var tick = 0
        var scans = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (tick++ % SCAN_EVERY == 0) {
                    if (scans++ % CONFIG_EVERY_S == 0) {
                        val read = GameEnvironmentStore.readLenientOrNull(context)
                        if (read == null && !readFailed) Log.w(TAG, "game environment unreadable; keeping the last good copy")
                        readFailed = read == null
                        config = read ?: config
                        switchOn = SessionPrefs.performanceBoost(context)
                    }
                    val busy = scanner.sample()
                    val game = scanner.gameId() ?: SessionState.gameId?.takeIf { it.isNotEmpty() && GameEnvironment.validScope(it) }
                    val wanted = SessionPrefs.boostFor(config, game, switchOn)
                    val cap = frameCap(config, game)
                    gameFrameCap = cap
                    val choice = "${game ?: "no game"} ${if (wanted) "on" else "off"} $cap"
                    if (choice != lastChoice) {
                        lastChoice = choice
                        Log.i(TAG, "game ${game ?: "not known"}: boost ${if (wanted) "on" else "off"}" +
                            (game?.let { config.games[it]?.get(SessionPrefs.BOOST_KEY) }?.let { " (game BL_GAME_BOOST=$it)" } ?: "") +
                            (if (cap > 0) ", frame cap $cap" else ""))
                    }
                    frames.addLast(WaylandCompositor.gameFrameCount())
                    if (frames.size > PACE_WINDOW_S + 1) frames.removeFirst()
                    val fps = (frames.last() - frames.first()).toDouble() / (frames.size - 1).coerceAtLeast(1)
                    val target = if (frames.size > PACE_WINDOW_S) paceTarget(fps, SessionState.refreshHz.toDouble(), lowestLimit(SessionState.paceFpsLimit, cap)) else null
                    val paced = target != null
                    pacedFor = if (paced) pacedFor + 1 else 0
                    val spare = when {
                        !wanted -> GAME_OFF
                        BoostReport.paused -> "check pause"
                        pm.isPowerSaveMode -> BoostReport.BATTERY_SAVER
                        // Not SEVERE: the Odin 3 reports it once its skin reaches 50 C, a couple of minutes into any game.
                        pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> BoostReport.TOO_HOT
                        pacedFor >= PACE_LEAVE_S -> BoostReport.KEEPS_UP
                        else -> null
                    }
                    val next = when {
                        spare != null -> emptySet()
                        paced -> pick(boosted, busy.filterKeys { it in boosted })
                        else -> pick(boosted, busy)
                    }
                    if (next != boosted) {
                        session = update(hm, session, next)
                        boosted = if (session == null) emptySet() else next
                        boostedThreads = boosted
                        Log.i(TAG, (if (session == null && next.isNotEmpty()) "no session for " else "boosting ") +
                            next.joinToString { "${readName("/proc/$it/comm")}/$it ${((busy[it] ?: 0.0) * 100).toInt()}%" }.ifEmpty { "nothing" } +
                            " (game ${fps.toInt()} fps${target?.let { ", holding pace at ${it.roundToInt()}" } ?: ""})")
                    }
                    offWhy = offReason(spare, boosted, paced, next)
                }
                session?.reportActualWorkDuration(TARGET_NS * 3 / 2)
                // Nothing to report while no hint session exists: sleep to the next scan (once a second).
                val wait = if (session == null) ((SCAN_EVERY - tick % SCAN_EVERY) % SCAN_EVERY).coerceAtLeast(1) else 1
                tick += wait - 1
                Thread.sleep(REPORT_MS * wait)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "stopped", t)
        } finally {
            session?.close()
            boostedThreads = emptySet()
        }
    }

    /** Why no thread is boosted, for the log; null while one is. [spare] is why the boost is held back, if it is. */
    internal fun offReason(spare: String?, boosted: Set<Int>, paced: Boolean, next: Set<Int>): String? = when {
        spare != null -> spare
        boosted.isNotEmpty() -> null
        paced -> BoostReport.KEEPS_UP
        next.isEmpty() -> "no thread busy enough"
        else -> "Android refused the hint"
    }

    /** A new session over [tids] in place of [session]; null for none. The set changes seldom, so no setThreads. */
    private fun update(hm: PerformanceHintManager, session: PerformanceHintManager.Session?, tids: Set<Int>): PerformanceHintManager.Session? {
        session?.close()
        if (tids.isEmpty()) return null
        return try {
            hm.createHintSession(tids.toIntArray(), TARGET_NS).also { if (it == null) refused++ }
        } catch (t: RuntimeException) {
            Log.w(TAG, "createHintSession", t)
            refused++
            null
        }
    }

    /**
     * The target a game at [fps] holds, or null for none. The top target is [fpsLimit] when one is set
     * (> 0) below [refreshHz], else [refreshHz]; it is held from (1 - tolerance) of it upwards. On a
     * top above 60, 60 fps (the common in-game cap) is held within the tolerance either side. No other
     * fraction of the panel counts: 40 or 30 fps at 120 Hz with no limit is falling behind.
     */
    internal fun paceTarget(fps: Double, refreshHz: Double, fpsLimit: Int = 0): Double? {
        if (fps < 10) return null
        val top = when {
            fpsLimit > 0 && (refreshHz <= 0 || fpsLimit < refreshHz) -> fpsLimit.toDouble()
            refreshHz > 0 -> refreshHz
            else -> return null
        }
        return when {
            fps >= top * (1 - PACE_TOLERANCE) -> top
            top > COMMON_CAP && abs(fps - COMMON_CAP) < PACE_TOLERANCE * COMMON_CAP -> COMMON_CAP
            else -> null
        }
    }

    internal fun holdsPace(fps: Double, refreshHz: Double, fpsLimit: Int = 0): Boolean = paceTarget(fps, refreshHz, fpsLimit) != null

    /** The lowest positive of [limits], 0 for none. */
    internal fun lowestLimit(vararg limits: Int): Int = limits.filter { it > 0 }.minOrNull() ?: 0

    private val CAP_KEYS = listOf("VKD3D_FRAME_RATE", "DXVK_FRAME_RATE")
    private val DXVK_MAX_FRAME_RATE = Regex("""^\s*(?:dxvk|d3d9)\.maxFrameRate\s*=\s*(-?[0-9]+)\s*$""")

    /**
     * The frame cap game [scope]'s environment sets (its own entry over the shared one, as
     * droiddeck-game-env merges them; a null value unsets): the lowest positive of VKD3D_FRAME_RATE,
     * DXVK_FRAME_RATE and DXVK_CONFIG's dxvk./d3d9.maxFrameRate; 0 for none.
     */
    internal fun frameCap(config: GameEnvironment.Config, scope: String?): Int {
        val game = scope?.takeIf { it.isNotEmpty() }?.let { config.games[it] }.orEmpty()
        fun value(name: String) = if (name in game) game[name] else config.shared[name]
        val caps = CAP_KEYS.mapNotNull { value(it)?.trim()?.toIntOrNull() } +
            value("DXVK_CONFIG").orEmpty().split(';').mapNotNull { DXVK_MAX_FRAME_RATE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return lowestLimit(*caps.toIntArray())
    }

    /** The game environment id of a Steam compat prefix path, as droiddeck-game-env's game_id() makes it; null for none. */
    internal fun appIdOfCompatPath(path: String): String? {
        val value = path.trimEnd('/').substringAfterLast('/')
        if (!Regex("-?[0-9]+").matches(value)) return null
        val number = value.toLongOrNull() ?: return null
        if (number < -(1L shl 31) || number >= (1L shl 32) || number == 0L) return null
        return Math.floorMod(number, 1L shl 32).toString()
    }

    internal fun pick(boosted: Set<Int>, busy: Map<Int, Double>): Set<Int> =
        busy.entries
            .filter { (tid, share) -> share >= if (tid in boosted) LEAVE else ENTER }
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.key in boosted }.thenByDescending { it.value })
            .take(MAX_THREADS)
            .map { it.key }
            .toSet()

    private fun readName(path: String) = try { File(path).readText().trim() } catch (_: Exception) { "?" }

    private class Scanner {
        private val self = Process.myPid()
        private val hz = Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
        private var lastNs = 0L
        private var lastProcess = HashMap<Int, Long>()
        private var lastThread = HashMap<Int, Long>()
        private var lastBusy = HashMap<Int, Double>()
        private var topPids = emptyList<Int>()
        private val appIds = HashMap<Int, String?>()
        private var gamePid = -1

        fun sample(): Map<Int, Double> {
            val now = System.nanoTime()
            val seconds = (now - lastNs) / 1e9
            lastNs = now
            val process = HashMap<Int, Long>()
            File("/proc").list()?.forEach { entry ->
                val pid = entry.toIntOrNull() ?: return@forEach
                cpuTicks("/proc/$pid/stat")?.let { process[pid] = it }
            }
            val top = process.entries
                .map { (pid, ticks) -> pid to ticks - (lastProcess[pid] ?: ticks) }
                .filter { (pid, delta) -> delta > 0 && pid != self }
                .sortedByDescending { it.second }
                .asSequence()
                .filter { (pid, _) -> readName("/proc/$pid/comm") !in NOT_GAMES }
                .take(TOP_PROCESSES)
                .toList()
            lastProcess = process
            topPids = top.map { it.first }
            appIds.keys.retainAll(process.keys)
            val thread = HashMap<Int, Long>()
            val busy = HashMap<Int, Double>()
            for ((pid, _) in top) {
                File("/proc/$pid/task").list()?.forEach { entry ->
                    val tid = entry.toIntOrNull() ?: return@forEach
                    val ticks = cpuTicks("/proc/$pid/task/$tid/stat") ?: return@forEach
                    thread[tid] = ticks
                    lastThread[tid]?.let { before -> busy[tid] = ((ticks - before) / hz / seconds + (lastBusy[tid] ?: 0.0)) / 2 }
                }
            }
            lastThread = thread
            lastBusy = busy
            return busy
        }

        /**
         * The running game's environment id: the busiest top process whose environment names a Steam
         * compat prefix, else the last one found while it lives. Each pid's environ is read once (it
         * is fixed at exec), hit or miss, until the pid dies; an unreadable one is tried again.
         */
        fun gameId(): String? {
            for (pid in topPids) {
                if (pid !in appIds) appIdOf(pid)?.let { appIds[pid] = it.getOrNull() }
                val id = appIds[pid] ?: continue
                gamePid = pid
                return id
            }
            return appIds[gamePid]
        }

        /** The pid's id (a miss is a success with null, kept until the pid dies); null if environ was unreadable, to retry. */
        private fun appIdOf(pid: Int): Result<String?>? = try {
            Result.success(File("/proc/$pid/environ").readBytes().toString(Charsets.UTF_8).split('\u0000')
                .firstOrNull { it.startsWith("STEAM_COMPAT_DATA_PATH=") }
                ?.let { appIdOfCompatPath(it.substringAfter('=')) })
        } catch (_: Exception) {
            null
        }

        private fun cpuTicks(path: String): Long? = try {
            val line = File(path).readText()
            val fields = line.substring(line.lastIndexOf(')') + 2).split(' ')
            fields[11].toLong() + fields[12].toLong()
        } catch (_: Exception) {
            null
        }
    }
}
