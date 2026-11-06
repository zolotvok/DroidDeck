package com.droiddeck.launcher.session

import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/**
 * Input times for the guest's droiddeck-agent focus guard, in `$FAKE_EVDEV_DIR/.padapply/` (CLOCK_MONOTONIC ms, the
 * clock libfakeinput's `menu` stamp and the agent's time.monotonic() use):
 *  - `user`: the last real user input the session screen received (touch, a physical pad or keyboard), at most every
 *    [USER_EVERY_MS]. `adb shell input keyevent` (the virtual keyboard, device id -1) is not counted;
 *  - `agent`: the windows "<start> <end>" of the last [AGENT_WINDOWS] inputs the agent provider injected
 *    (droiddeckctl input). libfakeinput stamps their Guide/QAM presses like a user's, so the guard drops a menu
 *    press that falls inside one of these windows.
 * Files are written off the caller's thread, tmp + rename, so the agent never reads half a line.
 */
object InputStamps {
    const val USER_EVERY_MS = 250L
    const val AGENT_WINDOWS = 16
    /** The longest an injected input holds (AgentInput clamps holds to 10 s) plus slack, until its real end is known. */
    private const val PROVISIONAL_MS = 11_000L

    @Volatile var dir: File? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "input-stamps").apply { isDaemon = true } }
    @Volatile private var lastUser = Long.MIN_VALUE / 2
    private val windows = ArrayDeque<LongArray>()

    /** A real input event reached the session screen. */
    fun user(now: Long = SystemClock.uptimeMillis()) {
        if (dir == null || now - lastUser < USER_EVERY_MS) return
        lastUser = now
        write("user", "$now\n")
    }

    /** Runs [block] (one agent input) inside a recorded agent window. */
    fun <T> agent(block: () -> T): T {
        val start = SystemClock.uptimeMillis()
        val window = longArrayOf(start, start + PROVISIONAL_MS)
        record(window)
        try {
            return block()
        } finally {
            window[1] = SystemClock.uptimeMillis()
            record(null)
        }
    }

    private fun record(added: LongArray?) {
        val text = synchronized(windows) {
            if (added != null) {
                windows.addLast(added)
                while (windows.size > AGENT_WINDOWS) windows.removeFirst()
            }
            windows.joinToString("") { "${it[0]} ${it[1]}\n" }
        }
        write("agent", text)
    }

    /** Forgets everything (a new session). */
    fun reset(next: File?) {
        synchronized(windows) { windows.clear() }
        lastUser = Long.MIN_VALUE / 2
        dir = next
    }

    /** Waits for the pending writes (tests). */
    internal fun flush() { io.submit {}.get() }

    private fun write(name: String, text: String) {
        val target = dir ?: return
        io.execute {
            runCatching {
                target.mkdirs()
                val tmp = File(target, ".$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(target, name))) tmp.delete()
            }
        }
    }
}
