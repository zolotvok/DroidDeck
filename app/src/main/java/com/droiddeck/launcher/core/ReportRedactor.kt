package com.droiddeck.launcher.core

import java.io.File
import java.io.InputStream
import java.io.Writer
import java.nio.charset.CodingErrorAction

/**
 * The problem report's redaction: everything LogRedactor.redactForShare removes, plus store launch arguments
 * (Epic -AUTH_PASSWORD exchange codes), JSON/VDF secrets, Bearer and Ubisoft tickets, account ids and names,
 * device identifiers and home/app/storage paths. Every report text part goes through [redact].
 */
object ReportRedactor {
    private const val TOKEN = "<redacted:token>"
    private const val ACCOUNT = "<redacted:account>"
    private const val ID = "<redacted:id>"

    private val LAUNCH_ARG = Regex(
        "(?i)(-(?:auth_password|auth_login|auth_token|epicusername|epicuserid|epicovt|epicsandboxid_token|" +
            "uplay_token|ubi_token|steam_ticket|ownership_token)\\s*=\\s*)(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s\"']+)"
    )
    private val UBI_TICKET = Regex("(?i)\\b(ubi_v1\\s+t=)([^\\s\"',;]+)")
    private val BEARER = Regex("(?i)\\b(bearer\\s+)([A-Za-z0-9._~+/\\-]{16,}=*)")

    private const val SECRET_KEYS =
        "exchange[_-]?code|authorization[_-]?code|auth[_-]?code|refresh[_-]?token|access[_-]?token|id[_-]?token|" +
            "ownership[_-]?token|session[_-]?ticket|device[_-]?secret|client[_-]?secret|caldera|ovt|token|ticket|" +
            "password|passwd|secret|cookie|nonce|signature|sessionid|steamloginsecure|webcookie|ssfn"
    private const val ACCOUNT_KEYS =
        "account[_-]?id|account[_-]?name|user[_-]?id|user[_-]?name|profile[_-]?id|profile[_-]?name|display[_-]?name|" +
            "persona[_-]?name|nick[_-]?name|epic[_-]?user[_-]?id|epic[_-]?username|login|email|steam[_-]?id|" +
            "machine[_-]?id|device[_-]?id|installation[_-]?id|android[_-]?id|imei|meid|iccid|bssid|ssid"

    // "key": "value" / key: 'value' / key=value, JSON and env style
    private val SECRET_QUOTED = Regex("(?i)(?<![A-Za-z0-9_])((?:$SECRET_KEYS)[\"']?\\s*[:=]\\s*)([\"'])((?:\\\\.|(?!\\2)[^\\\\\\r\\n]){4,})\\2")
    private val SECRET_BARE = Regex("(?i)(?<![A-Za-z0-9_\\-])((?:$SECRET_KEYS)\\s*[:=]\\s*)(?![\"'<])([^\\s\"'<>&;,}\\]]{4,})")
    private val ACCOUNT_QUOTED = Regex("(?i)(?<![A-Za-z0-9_])((?:$ACCOUNT_KEYS)[\"']?\\s*[:=]\\s*)([\"'])((?:\\\\.|(?!\\2)[^\\\\\\r\\n])+)\\2")
    private val ACCOUNT_BARE = Regex("(?i)(?<![A-Za-z0-9_\\-])((?:$ACCOUNT_KEYS)\\s*[:=]\\s*)(?![\"'<])([^\\s\"'<>&;,}\\]]{2,})")
    // an Epic exchange or auth code shaped "code" value (error codes contain dots, these do not)
    private val CODE_VALUE = Regex("(?i)(?<![A-Za-z0-9_])(code[\"']?\\s*[:=]\\s*[\"']?)([A-Za-z0-9_\\-]{16,})")

    // Valve KeyValues: "AccessToken"  "…"
    private val VDF = Regex("(?i)(\"[A-Za-z0-9_]*(?:token|password|secret|cookie|ssfn|accountname|personaname|steamid|login)[A-Za-z0-9_]*\"\\s+\")([^\"\\r\\n]+)(\")")
    private val SSFN = Regex("(?i)\\b(ssfn)\\d{6,}")
    private val LOGGED_IN = Regex("(?i)\\b(log(?:ged|ging)\\s+(?:in|on)\\s+(?:as|user)\\s+['\"]?)([^'\"\\s,;]+)")
    private val SERIALNO = Regex("(?i)(\\[?ro\\.(?:boot\\.)?serialno\\]?\\s*[:=]\\s*\\[?)([^\\]\\s]+)")

    private val HOME = Regex("(/home/)([^/\\s\"'<>]+)")
    private val APP_DATA = Regex("/data/(?:user(?:_de)?/\\d+|data)/[A-Za-z0-9._]+")
    private val STORAGE = Regex("/storage/emulated/\\d+|/sdcard(?=/|\\b)|/storage/self/primary")
    private val CARD = Regex("(/(?:storage|mnt/media_rw)/)[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
    private val WIN_USER = Regex("(?i)([A-Z]:\\\\+users\\\\+|drive_c/users/)(?!steamuser\\b|public\\b|default\\b|all users\\b)([^\\\\/\\s\"'<>]+)")

    @Volatile
    private var learned: List<Regex> = emptyList()

    /** Names to remove wherever they appear: store display names, the device name, the guest host name. */
    fun learn(names: Collection<String>) {
        learned = names.map { it.trim() }.filter { it.length >= 3 && it.lowercase() !in COMMON }.distinct()
            .sortedByDescending { it.length }
            .map { Regex("(?i)(?<![A-Za-z0-9_])" + Regex.escape(it) + "(?![A-Za-z0-9_])") }
    }

    private val COMMON = setOf("steam", "steamuser", "root", "user", "android", "phone", "unknown", "localhost", "droiddeck", "public", "default")

    fun redact(line: String): String {
        if (line.isEmpty()) return line
        var out = LogRedactor.redactForShare(line)
        if (out.startsWith("<redaction failed") || out == LogRedactor.OVERSIZED_LINE) return out
        return try {
            out = LAUNCH_ARG.replace(out) { it.groupValues[1] + TOKEN }
            out = UBI_TICKET.replace(out) { it.groupValues[1] + TOKEN }
            out = BEARER.replace(out) { it.groupValues[1] + TOKEN }
            out = VDF.replace(out) { it.groupValues[1] + (if (it.groupValues[1].contains("name", true) || it.groupValues[1].contains("login", true)) ACCOUNT else TOKEN) + it.groupValues[3] }
            out = SECRET_QUOTED.replace(out) { if (it.groupValues[3].startsWith("<redacted")) it.value else it.groupValues[1] + it.groupValues[2] + TOKEN + it.groupValues[2] }
            out = SECRET_BARE.replace(out) { it.groupValues[1] + TOKEN }
            out = CODE_VALUE.replace(out) { it.groupValues[1] + TOKEN }
            out = ACCOUNT_QUOTED.replace(out) { if (it.groupValues[3].startsWith("<redacted")) it.value else it.groupValues[1] + it.groupValues[2] + ACCOUNT + it.groupValues[2] }
            out = ACCOUNT_BARE.replace(out) { it.groupValues[1] + ACCOUNT }
            out = SSFN.replace(out) { it.groupValues[1] + "<redacted:n>" }
            out = LOGGED_IN.replace(out) { it.groupValues[1] + ACCOUNT }
            out = SERIALNO.replace(out) { it.groupValues[1] + "<redacted:serial>" }
            for (r in learned) out = r.replace(out, ACCOUNT)
            out = HOME.replace(out) { it.groupValues[1] + "<redacted:user>" }
            out = APP_DATA.replace(out, "<app-data>")
            out = CARD.replace(out) { it.groupValues[1] + "<card>" }
            out = STORAGE.replace(out, "<storage>")
            out = WIN_USER.replace(out) { it.groupValues[1] + "<redacted:user>" }
            out
        } catch (t: Throwable) {
            "<redaction failed; line withheld>"
        }
    }

    /** How many redaction marks [after] carries that [before] did not (the manifest's hit count). */
    fun hits(before: String, after: String): Int = marks(after) - marks(before)

    private fun marks(s: String): Int {
        var n = 0
        for (m in listOf("<redacted", "<lan-address>", "<app-data>", "<storage>", "<card>", "<private IPv", "<public IPv", "<carrier-NAT")) {
            var i = s.indexOf(m)
            while (i >= 0) { n++; i = s.indexOf(m, i + m.length) }
        }
        return n
    }

    class Stats(var lines: Long = 0, var hits: Long = 0, var bytes: Long = 0)

    /** Streams [input] line by line through [redact]; invalid UTF-8 becomes U+FFFD, a UTF-16 file is withheld. */
    fun scrub(input: InputStream, out: Writer, stats: Stats = Stats()): Stats {
        val buffered = input.buffered()
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        if ((first == 0xff && second == 0xfe) || (first == 0xfe && second == 0xff)) {
            out.write("<unsupported encoding; file withheld>\n")
            return stats
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val reader = java.io.InputStreamReader(buffered, decoder)
        val chunk = CharArray(8192)
        val line = StringBuilder(4096)
        var oversized = false
        var afterCr = false
        fun emit() {
            val text = if (oversized) LogRedactor.OVERSIZED_LINE else {
                val raw = line.toString()
                if (raw.indexOf('\u0000') >= 0) "<binary line withheld>" else redact(raw).also { stats.hits += hits(raw, it).coerceAtLeast(0) }
            }
            out.write(text)
            out.write("\n")
            stats.lines++
            stats.bytes += text.length + 1
            line.setLength(0)
            oversized = false
        }
        var pending = false
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) break
            for (i in 0 until n) {
                val c = chunk[i]
                if (afterCr) {
                    afterCr = false
                    if (c == '\n') continue
                }
                when (c) {
                    '\r', '\n' -> { emit(); pending = false; afterCr = c == '\r' }
                    else -> {
                        pending = true
                        if (!oversized) {
                            if (line.length < LogRedactor.MAX_LINE_LENGTH) line.append(c) else { line.setLength(0); oversized = true }
                        }
                    }
                }
            }
        }
        if (pending) emit()
        return stats
    }

    fun scrub(file: File, out: Writer, stats: Stats = Stats()): Stats = file.inputStream().use { scrub(it, out, stats) }

    fun redactText(text: String): String = text.lineSequence().joinToString("\n") { redact(it) }

    /** The first [maxLines] redacted lines of [text], for the preview. */
    fun preview(text: String, maxLines: Int = 200): String =
        text.lineSequence().take(maxLines).joinToString("\n") { redact(it) }
}
