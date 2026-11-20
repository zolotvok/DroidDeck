package com.droiddeck.launcher.session

import java.net.URLEncoder

/** The report's summary.md and the GitHub issue text built from it, plus the issue repo rule (user decision 11-04). */
object ReportSummary {
    enum class Repo(val slug: String) {
        BLOBFERRY("zolotvok/blobferry"),
        FORK("zolotvok/DroidDeck");

        val newIssue: String get() = "https://github.com/$slug/issues/new"
        val issues: String get() = "https://github.com/$slug/issues"
    }

    /** [repo] null = decided by the logs: Blobferry when they show a Vulkan/driver error, otherwise the fork. */
    enum class Category(val key: String, val repo: Repo?) {
        GRAPHICS("graphics", Repo.BLOBFERRY),
        GPU_FAULT("gpu-fault", Repo.BLOBFERRY),
        DRIVER_LAYER("driver-layer", Repo.BLOBFERRY),
        BRIDGE_CRASH("bridge-crash", Repo.BLOBFERRY),
        MALI_PERF("mali-performance", Repo.BLOBFERRY),
        APP_UI("app-ui", Repo.FORK),
        STORE_LAUNCH("store-launch", Repo.FORK),
        STEAM_WINDOW("steam-window", Repo.FORK),
        SESSION_START("session-start", Repo.FORK),
        UPDATER_REPORT("updater-report", Repo.FORK),
        GAME_NO_START("game-no-start", null),
        UNSURE("unsure", null);

        companion object {
            fun of(key: String?): Category? = entries.firstOrNull { it.key == key }
        }
    }

    fun suggestRepo(category: Category, vulkanError: Boolean): Repo =
        category.repo ?: if (vulkanError) Repo.BLOBFERRY else Repo.FORK

    private val VULKAN_ERROR = Regex(
        "VK_ERROR_(?!OUT_OF_DATE_KHR)[A-Z_]+|DEVICE_LOST|failed to find physical device|vkCreate(?:Instance|Device) failed|" +
            "GROUP_ERROR_FATAL|TRANSLATION_FAULT|GPU (?:page )?fault|mali-bridge: .*(?:fail|error)|" +
            "mali-compat: .*(?:fail|error|abort)|libhybris.*(?:fail|error|cannot)|vulkan\\.mali\\.so.*(?:fail|error|not found)|" +
            "No Vulkan (?:device|driver)|vkd3d.*(?:err|fail).*device|D3D12 not supported|DX12 not supported",
        RegexOption.IGNORE_CASE,
    )

    /** Whether session/Proton log lines show a Vulkan or driver failure (the "unsure" rule of the repo choice).
     *  VK_ERROR_OUT_OF_DATE_KHR is a swapchain's normal "recreate me", not a failure. */
    fun vulkanDriverError(lines: Sequence<String>): Boolean = vulkanErrorLine(lines) != null

    /** The first line that shows a Vulkan or driver failure: the evidence the summary quotes. */
    fun vulkanErrorLine(lines: Sequence<String>): String? =
        lines.firstOrNull { VULKAN_ERROR.containsMatchIn(it) && !LIKELY_CAUSE.containsMatchIn(it) }?.trim()

    /** game-env's own "likely cause" sentences: our words about a log line, not evidence. */
    private val LIKELY_CAUSE = Regex("droiddeck-game-env: likely cause: ")

    class Info(
        val written: String,
        val appVersion: String,
        val appId: String,
        val buildType: String,
        val sourceCommit: String,
        val device: String,
        val android: String,
        val soc: String,
        val gpu: String,
        val vendorDriver: String,
        val blobferryVersion: String,
        val blobferryCommit: String,
        val blobferrySource: String,
        val blobferryShimMd5: String,
        val fexDllMd5: String,
        val proton: String,
        val displayDriver: String,
        val runtimeDriver: String,
        val sessionDriver: String,
        val gameDriver: String,
        val game: String,
        val gameEnv: Map<String, String?>,
        val focusGuard: String,
        val boost: String,
        val logs: String,
        val protonLog: String,
        val lastExit: String,
        val vulkanError: Boolean,
        /** Hand-deployed (.dev) Blobferry: the files that are not the bundled package's (empty: all are, or not dev). */
        val blobferryDevFiles: List<String> = emptyList(),
        /** Hand-deployed (.dev) Blobferry: the bundled commit the other files come from. */
        val blobferryBaseCommit: String = "",
        /** PanVK (session driver on Mali): version (source), .so md5; empty = not a Mali report. */
        val panvk: String = "",
        /** The env defaults in force: version (source), games; empty = not a Mali report. */
        val envDefaults: String = "",
        /** The last game launch as droiddeck-game-env logged it ([lastLaunch]): driver, logs, exit, failure summary, HAL. */
        val lastLaunch: List<String> = emptyList(),
        /** "What went wrong": [ReportCause.findings] bullets, most telling first. */
        val cause: List<String> = emptyList(),
        /** The last swapchain/WSI lines, shown under the bullets when a stall or a hung swapchain destroy is named. */
        val causeWsi: List<String> = emptyList(),
        /** The Env cell ([envCell]); null: the plain per-game entries of [gameEnv]. */
        val envCell: String? = null,
    )

    private val LAUNCH_START = Regex("droiddeck-game-env: game driver:")
    private val GAME_ENV = Regex("droiddeck-game-env: ")
    private val LAUNCH_LINE = Regex(
        "droiddeck-game-env: (?:game driver:|game logs|game exited|game ended|likely cause|failure summary|[|!] |no Proton log)"
    )
    private val SUMMARY_LINE = Regex("droiddeck-game-env: (?:failure summary from|[|!] )")
    private val SUMMARY_LOG = Regex("failure summary from (\\S+)")
    private val SUMMARY_TEXT = Regex("droiddeck-game-env: [|!] (.*)")
    private val HAL_LINE = Regex("mali-bridge: (?:vendor HAL|hybris)")
    private val DEFAULTS_LINE = Regex("droiddeck-game-env: Mali defaults for (.+) \\(([a-z]+:[^)\\s]+|[0-9]+)\\), ")
    private val KEPT_LINE = Regex("droiddeck-game-env: .*: your own ([A-Za-z0-9_, ]+) kept over the defaults")
    private const val LAUNCH_MAX = 40
    private const val LAUNCH_SCAN_MAX = 2000
    private const val HAL_MAX = 5

    /** The last launch's game-env lines in session.log, from its "game driver:" line on. */
    fun launchLines(session: Sequence<String>): List<String> {
        var launch = ArrayList<String>()
        for (line in session) {
            if (LAUNCH_START.containsMatchIn(line)) launch = ArrayList()
            if (GAME_ENV.containsMatchIn(line) && launch.size < LAUNCH_SCAN_MAX) launch += line.trim()
        }
        return launch
    }

    /** The Proton log game-env's failure summary of the launch names. */
    fun summaryLogName(launch: List<String>): String? = launch.firstNotNullOfOrNull { SUMMARY_LOG.find(it) }?.groupValues?.get(1)

    /** The launch's game title and env-defaults key ("epic:Duckbill") as the Mali defaults line names them. */
    fun launchGame(launch: List<String>): Pair<String, String>? =
        launch.firstNotNullOfOrNull { DEFAULTS_LINE.find(it) }?.let { it.groupValues[1] to it.groupValues[2] }

    /** The env names the launch kept from the user's own settings over the Mali defaults. */
    fun keptOwnKeys(launch: List<String>): Set<String> =
        launch.mapNotNull { KEPT_LINE.find(it)?.groupValues?.get(1) }.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * The launch block of the summary: driver, logs, exit and HAL lines of session.log, then the key lines of the
     * Proton log ([keys], from [protonName]) in place of game-env's own failure summary when that summary is from the
     * same log or missing; a summary from another log is kept, without noise and repeats.
     */
    fun lastLaunch(launch: List<String>, proton: List<String>, protonName: String? = null, keys: ReportCause.KeyLines? = null,
                   stopped: Boolean = false): List<String> {
        val summaryLog = summaryLogName(launch)
        val ours = keys != null && protonName != null && (summaryLog == null || summaryLog == protonName)
        val shown = ArrayList<String>()
        val seen = HashSet<String>()
        for (line in launch) {
            if (!LAUNCH_LINE.containsMatchIn(line) || shown.size >= LAUNCH_MAX) continue
            // the known causes of our own key lines are the summary's "What went wrong" bullets already
            if (ours && ReportCause.KNOWN_CAUSES.any { line.startsWith("droiddeck-game-env: likely cause: ${it.first}: ") }) continue
            if (SUMMARY_LINE.containsMatchIn(line) && !HAL_LINE.containsMatchIn(line)) {
                if (ours) continue
                val text = SUMMARY_TEXT.find(line)?.groupValues?.get(1)
                if (text != null && (ReportCause.noise(text, stopped) || !seen.add(ReportCause.repeatKey(text)))) continue
            }
            shown += line
        }
        if (ours && keys != null) {
            shown += "key lines of $protonName (! = known cause; start-up${if (stopped) "/teardown" else ""} noise and repeats left out):"
            keys.causes.forEach { shown += "! " + it.trim().take(400) }
            keys.others.forEach { shown += "| " + it.trim().take(400) }
        }
        val hal = proton.asSequence().filter { HAL_LINE.containsMatchIn(it) }.map { it.trim() }.distinct().take(HAL_MAX)
            .filter { h -> shown.none { it.endsWith(h) } }.toList()
        return shown + hal
    }

    /** The per-game env scope of a game id: a non-Steam shortcut's 64-bit game id carries its 32-bit app id on top. */
    fun scopeOf(id: String?): String? {
        val n = id?.toULongOrNull() ?: return null
        val scope = if (n > 0xFFFFFFFFUL) n shr 32 else n
        return scope.takeIf { it > 0UL }?.toString()
    }

    /** The Env cell: the user's own per-game keys, then what the launch's Mali defaults added on top of them. */
    fun envCell(own: Map<String, String?>, keptNames: Set<String>, defaultsKey: String?, defaults: Map<String, String>?): String {
        val parts = ArrayList<String>()
        val unlogged = keptNames - own.keys
        if (own.isNotEmpty() || unlogged.isNotEmpty())
            parts += "yours: " + listOf(envText(own).takeIf { own.isNotEmpty() }, unlogged.sorted().joinToString(" ").takeIf { it.isNotEmpty() }
                ?.let { "$it (value not in the logs)" }).filterNotNull().joinToString(" ")
        if (defaultsKey != null) {
            val left = defaults?.filterKeys { it !in own && it !in keptNames }
            parts += "Mali defaults $defaultsKey: " + when {
                left == null -> "applied (values not read)"
                left.isEmpty() -> "all overridden by yours"
                else -> envText(left)
            }
        }
        return parts.joinToString("; ").ifEmpty { "none" }
    }

    /** The Blobferry cell: version @ commit (source), or the dev build without a commit and what it changed. */
    fun blobferryCell(info: Info): String {
        val version = info.blobferryVersion.ifEmpty { "none" }
        val shim = "shim md5 ${info.blobferryShimMd5.ifEmpty { "?" }}"
        if (info.blobferrySource == "dev" && info.blobferryCommit.isEmpty()) {
            val files = info.blobferryDevFiles.joinToString(", ") { it.substringAfterLast('/') }.ifEmpty { "unknown files" }
            val base = info.blobferryBaseCommit.take(7).ifEmpty { "?" }
            return "$version, dev build (no commit): hand-deployed $files; the rest is the bundled package @ $base, $shim"
        }
        return "$version @ ${info.blobferryCommit.take(7).ifEmpty { "?" }} (${info.blobferrySource}), $shim"
    }

    private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ").ifEmpty { "?" }

    private fun envText(env: Map<String, String?>) =
        env.entries.joinToString(" ") { (k, v) -> if (v == null) "-$k" else "$k=$v" }.ifEmpty { "none" }

    fun table(info: Info): String = buildString {
        append("| | |\n|---|---|\n")
        fun row(k: String, v: String) = append("| ").append(k).append(" | ").append(cell(v)).append(" |\n")
        row("DroidDeck", "${info.appVersion} (${info.appId}), ${info.buildType}" + if (info.sourceCommit.isNotEmpty()) " @ ${info.sourceCommit.take(10)}" else "")
        row("Device", "${info.device}, Android ${info.android}, ${info.soc}, ${info.gpu}" + if (info.vendorDriver.isNotEmpty()) " + ${info.vendorDriver}" else "")
        row("Blobferry", blobferryCell(info))
        row("FEX DLL md5", info.fexDllMd5.ifEmpty { "not installed" })
        if (info.panvk.isNotEmpty()) row("PanVK", info.panvk)
        if (info.envDefaults.isNotEmpty()) row("Env defaults", info.envDefaults)
        row("Driver", "display ${info.displayDriver}, runtime ${info.runtimeDriver}, session ${info.sessionDriver}, games ${info.gameDriver}")
        row("Proton", info.proton)
        row("Game", info.game)
        row("Env", info.envCell ?: envText(info.gameEnv))
        row("Focus guard / boost", "${info.focusGuard} / ${info.boost}")
        row("Logs", "${info.logs}; Proton log ${info.protonLog}")
        row("Last exit", info.lastExit)
    }

    fun markdown(info: Info): String = buildString {
        append("# DroidDeck problem report\n\n")
        append("Written ").append(info.written).append(". Account names, tokens, ids and home paths are redacted; no saves are included.\n\n")
        if (info.cause.isNotEmpty()) {
            append("## What went wrong\n\n")
            info.cause.forEach { append("- ").append(it).append('\n') }
            if (info.causeWsi.isNotEmpty()) {
                append("\nLast swapchain/WSI lines:\n\n```\n")
                info.causeWsi.forEach { append(it.replace("```", "` ` `")).append('\n') }
                append("```\n")
            }
            append('\n')
        }
        append(table(info))
        if (info.lastLaunch.isNotEmpty()) {
            append("\n## Last game launch\n\n```\n")
            info.lastLaunch.forEach { append(it.replace("```", "` ` `")).append('\n') }
            append("```\n")
        }
    }

    fun issueTitle(category: Category, info: Info): String =
        "[${category.key}] " + info.game.substringBefore(" (").ifEmpty { "DroidDeck" } + ": (describe the problem)"

    fun issue(info: Info, category: Category, zipName: String?): String = buildString {
        append("**What happened** (fill in)\n\n\n")
        append("**Game** ").append(info.game).append("  **Category** ").append(category.key).append("\n\n")
        append("**Expected / actual** (fill in)\n\n\n")
        if (info.cause.isNotEmpty()) {
            append("**What went wrong (from the logs)**\n\n")
            info.cause.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append(table(info))
        append("\nLogs: attach ").append(zipName ?: "the DroidDeck-report zip").append(" (drag it onto the issue)\n")
    }

    /** The issue text's first line: which file to attach and where it is ([saved]: in Downloads/DroidDeck). */
    fun attachLine(zipName: String, saved: Boolean): String =
        if (saved) "**Attach the logs:** tap \"Attach files\" (or \"Paste, drop, or click to add files\") below and pick " +
            "`$zipName` from Downloads/DroidDeck.\n\n"
        else "**Attach the logs:** the report `$zipName` could not be saved to Downloads; use Share on the report screen to send it.\n\n"

    /** Links longer than this lose the prefill in some browsers (and GitHub refuses very long ones). */
    const val ISSUE_URL_MAX = 8000

    /** The new-issue link with the full [body], or with [shortBody] when that link would be too long; second = shortened. */
    fun issueLink(repo: Repo, title: String, body: String, shortBody: String, max: Int = ISSUE_URL_MAX): Pair<String, Boolean> {
        val full = issueUrl(repo, title, body, maxBody = Int.MAX_VALUE)
        if (full.length <= max) return full to false
        return issueUrl(repo, title, shortBody, maxBody = Int.MAX_VALUE) to true
    }

    /** The short body: the attach line and where the full text is (the clipboard). */
    fun shortIssue(attach: String): String =
        attach + "**Paste the copied report text here** (DroidDeck put it on the clipboard; long-press, Paste).\n"

    /** The new-issue link; the body rides along only while the link stays short enough for the browser. */
    fun issueUrl(repo: Repo, title: String, body: String, maxBody: Int = 6000): String {
        val t = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val b = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        return repo.newIssue + "?title=" + t + if (b.length <= maxBody) "&body=$b" else ""
    }
}
