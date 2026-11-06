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
    private const val APP = "<redacted:app>"

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
    // a shell prompt or an ssh/scp target: user@host:~/dir, [user@host dir]$, user@host ~ %, ssh -p 22 user@host
    private val PROMPT = Regex(
        "(?<![A-Za-z0-9_.@-])([a-z_][a-z0-9_-]{0,31})@(<[a-z:A-Z0-9 -]{1,40}>|[A-Za-z][A-Za-z0-9-]{0,62}(?:\\.[A-Za-z0-9-]+)*)" +
            "(?=:[~/]|\\s+[~/]\\S*\\s*[\\$#%>](?:\\s|$)|\\s+[^\\s\\]]+\\]\\s*[\\$#%]|\\])"
    )
    private val SSH_TARGET = Regex(
        "(?i)(\\b(?:ssh|scp|sftp|rsync|mosh)\\b[^@\\r\\n]{0,120}?\\s)([A-Za-z0-9_][A-Za-z0-9_.-]{0,31})@(<[a-z:A-Z0-9 -]{1,40}>|[A-Za-z0-9][A-Za-z0-9.-]*)"
    )
    private val GUEST_USERS = setOf("root", "steamuser", "steam", "deck", "user")
    private val GUEST_HOSTS = setOf("localhost", "droiddeck", "steamdeck")
    // a hex run that may be a known file hash (md5 32, sha1 40, sha256 64, sha512 128)
    private val HEX_RUN = Regex("(?<![0-9A-Za-z])[0-9a-fA-F]{32,128}(?![0-9A-Za-z])")
    private val KEEP = Regex("BFKNOWNHASH([A-Y]+)Q")
    private val ANDROID_PACKAGE = Regex("(?<![A-Za-z0-9_.])[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    private val WIN_USER = Regex("(?i)([A-Z]:\\\\+users\\\\+|drive_c/users/)(?!steamuser\\b|public\\b|default\\b|all users\\b)([^\\\\/\\s\"'<>]+)")

    @Volatile
    private var learned: List<Regex> = emptyList()

    /** Names to remove wherever they appear: store display names, the device name, the guest host name. */
    fun learn(names: Collection<String>) {
        learned = names.map { it.trim() }.filter { it.length >= 3 && it.lowercase() !in COMMON }.distinct()
            .sortedByDescending { it.length }
            .map { Regex("(?i)(?<![A-Za-z0-9_])" + Regex.escape(it) + "(?![A-Za-z0-9_])") }
    }

    @Volatile
    private var known: Set<String> = emptySet()

    /**
     * md5/sha hashes of the app's own component files (Blobferry's managed files, the shim, the FEX DLL): kept as they
     * are, where LogRedactor would take any lone 32-hex run for a Steam Web API key. Only an exact known hash passes,
     * and only that heuristic is skipped: a key=value secret rule still redacts it.
     */
    fun allowHashes(hashes: Collection<String>) {
        known = hashes.map { it.trim().lowercase() }.filter { it.length in setOf(32, 40, 64, 128) && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }.toSet()
    }

    @Volatile
    private var apps: Set<String> = emptySet()

    /**
     * The package names of the phone's other apps (the user's rule: no names or ids of non-DroidDeck apps in a report).
     * Kernel process names keep only the last 15 characters, so those tails are listed too.
     */
    fun learnApps(packages: Collection<String>, own: String) {
        val names = packages.map { it.trim() }.filter { it.contains('.') && it != own && !it.startsWith("com.droiddeck.") }
        apps = (names + names.filter { it.length > 15 }.map { it.takeLast(15) }.filter { it.contains('.') }).toHashSet()
    }

    private fun redactApps(line: String): String {
        val set = apps
        if (set.isEmpty() || '.' !in line) return line
        return ANDROID_PACKAGE.replace(line) { m ->
            val token = m.value
            var cut = token.length
            var hit = false
            while (cut > 0) {
                if (token.substring(0, cut) in set) { hit = true; break }
                cut = token.lastIndexOf('.', cut - 1).takeIf { it > 0 } ?: break
            }
            if (hit) APP else token
        }
    }

    private fun protect(line: String, kept: MutableList<String>): String {
        val set = known
        if (set.isEmpty() || line.length < 32) return line
        return HEX_RUN.replace(line) { m ->
            if (m.value.lowercase() in set) { kept += m.value; "BFKNOWNHASH" + letters(kept.size - 1) + "Q" } else m.value
        }
    }

    private fun letters(n: Int): String {
        var i = n
        val b = StringBuilder()
        do { b.append('A' + i % 25); i /= 25 } while (i > 0)  // 'A'..'Y': 'Q' ends the mark only after a letter run
        return b.toString()
    }

    private fun unletters(s: String): Int = s.foldRight(0) { c, acc -> acc * 25 + (c - 'A') }

    private val COMMON = setOf("steam", "steamuser", "root", "user", "android", "phone", "unknown", "localhost", "droiddeck", "public", "default")

    fun redact(line: String): String {
        if (line.isEmpty()) return line
        val kept = ArrayList<String>(0)
        var out = LogRedactor.redactForShare(protect(line, kept))
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
            if ('@' in out) {
                out = SSH_TARGET.replace(out) { it.groupValues[1] + userAt(it.groupValues[2], it.groupValues[3]) }
                out = PROMPT.replace(out) { userAt(it.groupValues[1], it.groupValues[2]) }
            }
            out = redactApps(out)
            if (kept.isNotEmpty()) out = KEEP.replace(out) { m -> kept.getOrNull(unletters(m.groupValues[1])) ?: m.value }
            out
        } catch (t: Throwable) {
            "<redaction failed; line withheld>"
        }
    }

    private fun userAt(user: String, host: String): String {
        val u = if (user.lowercase() in GUEST_USERS || user.startsWith("<redacted")) user else "<redacted:user>"
        val h = if (host.lowercase() in GUEST_HOSTS || host.startsWith("<redacted")) host else "<redacted:host>"
        return "$u@$h"
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
