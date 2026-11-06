package com.droiddeck.launcher.session

import java.net.URLEncoder

/** The report's summary.md and the GitHub issue text built from it, plus the issue repo rule (user decision 11-04). */
object ReportSummary {
    enum class Repo(val slug: String) {
        BLOBFERRY("zolotvok/blobferry"),
        FORK("zolotvok/DroidDeck");

        val newIssue: String get() = "https://github.com/$slug/issues/new"
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
        "VK_ERROR_[A-Z_]+|DEVICE_LOST|failed to find physical device|vkCreate(?:Instance|Device) failed|" +
            "GROUP_ERROR_FATAL|TRANSLATION_FAULT|GPU (?:page )?fault|mali-bridge: .*(?:fail|error)|" +
            "mali-compat: .*(?:fail|error|abort)|libhybris.*(?:fail|error|cannot)|vulkan\\.mali\\.so.*(?:fail|error|not found)|" +
            "No Vulkan (?:device|driver)|vkd3d.*(?:err|fail).*device|D3D12 not supported|DX12 not supported",
        RegexOption.IGNORE_CASE,
    )

    /** Whether session/Proton log lines show a Vulkan or driver failure (the "unsure" rule of the repo choice). */
    fun vulkanDriverError(lines: Sequence<String>): Boolean = lines.any { VULKAN_ERROR.containsMatchIn(it) }

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
    )

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
        row("Env", envText(info.gameEnv))
        row("Focus guard / boost", "${info.focusGuard} / ${info.boost}")
        row("Logs", "${info.logs}; Proton log ${info.protonLog}")
        row("Last exit", info.lastExit)
    }

    fun markdown(info: Info): String = buildString {
        append("# DroidDeck problem report\n\n")
        append("Written ").append(info.written).append(". Account names, tokens, ids and home paths are redacted; no saves are included.\n\n")
        append(table(info))
        if (info.vulkanError) append("\nThe session or Proton log shows a Vulkan/driver error.\n")
    }

    fun issueTitle(category: Category, info: Info): String =
        "[${category.key}] " + info.game.substringBefore(" (").ifEmpty { "DroidDeck" } + ": (describe the problem)"

    fun issue(info: Info, category: Category, zipName: String?): String = buildString {
        append("**What happened** (fill in)\n\n\n")
        append("**Game** ").append(info.game).append("  **Category** ").append(category.key).append("\n\n")
        append("**Expected / actual** (fill in)\n\n\n")
        append(table(info))
        append("\nLogs: attach ").append(zipName ?: "the DroidDeck-report zip").append(" (drag it onto the issue)\n")
    }

    /** The new-issue link; the body rides along only while the link stays short enough for the browser. */
    fun issueUrl(repo: Repo, title: String, body: String, maxBody: Int = 6000): String {
        val t = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val b = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
        return repo.newIssue + "?title=" + t + if (b.length <= maxBody) "&body=$b" else ""
    }
}
