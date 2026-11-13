package com.droiddeck.launcher.gpu

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The updater's install log, `files/components/install.log`: append-only TSV, one line per event
 * (time, component, op, from-version, to-version, sha256, source, result[, note]). The optional 9th column only follows the
 * 8 fixed ones (a PanVK change while the session runs another PanVK says so there). Rotated at [MAX_BYTES] into one `.1`.
 * Never throws into the caller: a log that cannot be written is only a logcat warning. [app, Claude Opus 5.5] 2026-11-13
 */
object ComponentLog {
    private const val TAG = "ComponentLog"
    const val DIR = "components"
    const val FILE = "install.log"
    const val MAX_BYTES = 256L * 1024
    private const val MAX_FIELD = 300

    const val INSTALL = "install"
    const val ROLLBACK = "rollback"
    const val RESET = "reset"
    const val RESTAGE = "restage"
    const val UPGRADE_AT_START = "upgrade-at-start"
    const val MIGRATE = "migrate"
    const val USE = "use"
    const val UNUSE = "unuse"
    const val REFUSED = "refused"
    const val OK = "ok"

    val FIELDS = listOf("time", "component", "op", "from", "to", "sha256", "source", "result", "note")
    private val LOCK = Any()
    private val INGEST_LOCK = Any()

    /** The log of the app whose files dir is [filesDir]. */
    fun file(filesDir: File) = File(filesDir, "$DIR/$FILE")

    /** The log next to a component store (`files/<store>` -> `files/components/install.log`), as the tests' temp dirs. */
    fun forStore(store: File) = file(store.absoluteFile.parentFile ?: store)

    /** One field: tabs, newlines and other control characters become spaces, capped, "-" when empty. */
    fun clean(value: String?): String {
        val s = value.orEmpty().map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("").trim()
        return (if (s.length > MAX_FIELD) s.take(MAX_FIELD) else s).ifEmpty { "-" }
    }

    fun append(
        log: File, component: String, op: String, from: String?, to: String?, sha256: String?, source: String?, result: String?,
        note: String? = null, now: Long = System.currentTimeMillis(),
    ) {
        try {
            val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
            val fields = listOf(time, component, op, from, to, sha256, source, result) + listOfNotNull(note)
            appendLine(log, fields.joinToString("\t") { clean(it) } + "\n")
        } catch (e: Throwable) {
            Log.w(TAG, "install log not written ($component $op)", e)
        }
    }

    private fun appendLine(log: File, line: String) = synchronized(LOCK) {
        log.parentFile?.mkdirs()
        if (log.isFile && log.length() + line.length > MAX_BYTES) {
            val old = File(log.parentFile, log.name + ".1")
            old.delete()
            if (!log.renameTo(old)) log.delete()
        }
        log.appendText(line)
    }

    private val TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

    /**
     * Moves the events another writer left in [guest] (the guest's own TSV of the same 8 or 9 fields, e.g.
     * droiddeck-mali-defaults' fex events) into [log], keeping their time. Only lines [accept] takes (fields split) are
     * copied; the guest file is renamed away first, so a writer that appends meanwhile starts a new one. Returns the count.
     */
    fun ingest(log: File, guest: File, accept: (List<String>) -> Boolean): Int = synchronized(INGEST_LOCK) {
        val taken = File(guest.parentFile, guest.name + ".ingest")
        try {
            if (!taken.isFile && (!guest.isFile || !guest.renameTo(taken))) return@synchronized 0
            var count = 0
            val lines = taken.bufferedReader().use { r -> generateSequence { r.readLine() }.take(10_000).toList() }
            for (line in lines) {
                val fields = line.split('\t')
                if (fields.size !in 8..9 || !TIME.matches(fields[0]) || !accept(fields)) continue
                appendLine(log, fields.joinToString("\t") { clean(it) } + "\n")
                count++
            }
            taken.delete()
            count
        } catch (e: Throwable) {
            Log.w(TAG, "guest log ${guest.path} not ingested", e)
            0
        }
    }

    /** The last [n] lines (the rotated file first when the current one is short), oldest first. */
    fun tail(log: File, n: Int): List<String> = try {
        val wanted = n.coerceIn(0, 10_000)
        val current = if (log.isFile) log.readLines().filter { it.isNotBlank() } else emptyList()
        val rotated = File(log.parentFile, log.name + ".1")
        val older = if (current.size < wanted && rotated.isFile) rotated.readLines().filter { it.isNotBlank() } else emptyList()
        (older + current).takeLast(wanted)
    } catch (e: Throwable) {
        Log.w(TAG, "install log not read", e)
        emptyList()
    }

    /** A log line as JSON with [FIELDS] as keys (missing fields ""). */
    fun json(line: String): JSONObject {
        val parts = line.split('\t')
        return JSONObject().also { j -> FIELDS.forEachIndexed { i, k -> j.put(k, parts.getOrNull(i).orEmpty()) } }
    }
}
