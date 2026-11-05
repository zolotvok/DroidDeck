package com.droiddeck.launcher.session

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.core.ReportRedactor
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Report a problem": one zip with the last two session logs, the game's Proton log, a filtered logcat, the compat and
 * WSI layer lines, our own crash traces, the per-game env and a summary. An allowlist: only these parts are opened, and
 * every text goes through ReportRedactor. Nothing leaves the app cache until the user shares or saves the zip.
 */
object ReportBundle {
    private const val TAG = "ReportBundle"

    const val MAX_TOTAL = 20L * 1024 * 1024
    const val HEAD_BYTES = 2L * 1024 * 1024
    const val TAIL_BYTES = 8L * 1024 * 1024
    const val MIN_PART = 256L * 1024
    const val LOGCAT_LINES = 20_000
    const val LOGCAT_MIN_LINES = 5_000
    const val CRASH_TRACE_BYTES = 256L * 1024
    private const val RECENT_MS = 7 * 86_400_000L
    private const val PREFS = "report"

    enum class Kind { SUMMARY, SESSION, PREVIOUS, PROTON, LOGCAT, LAYERS, CRASH, ENV, STORES }

    /** One zip entry. A file part is read at build time; a text part is produced by [text] (sizes are then estimates). */
    class Part(
        val path: String,
        val kind: Kind,
        val size: Long,
        val file: File? = null,
        val text: (() -> String)? = null,
    )

    class Plan(val parts: List<Part>, val notes: List<String>, val info: ReportSummary.Info) {
        val summaryText: String get() = ReportRedactor.redactText(ReportSummary.markdown(info))
    }

    class Options(
        val skip: Set<String> = emptySet(),
        val previous: Boolean = true,
        val proton: Boolean = true,
        val maxBytes: Long = MAX_TOTAL,
        val raw: Boolean = false,
    )

    class Entry(val path: String, val kind: Kind, val source: Long, val written: Long, val redacted: Boolean, val trimmed: Boolean, val hits: Long) {
        fun json(): JSONObject = JSONObject().put("path", path).put("kind", kind.name.lowercase(Locale.US)).put("source", source)
            .put("written", written).put("redacted", redacted).put("trimmed", trimmed).put("hits", hits)
    }

    class Result(val zip: File, val entries: List<Entry>, val notes: List<String>) {
        fun json(): JSONObject = JSONObject().put("zip", zip.name).put("bytes", zip.length())
            .put("parts", JSONArray(entries.map { it.json() })).put("notes", JSONArray(notes))
    }

    // ---- collect ----

    private val NEVER = Regex(
        "(?i)(^|/)(loginusers\\.vdf|config\\.vdf|localconfig\\.vdf|ssfn[^/]*|cookies?[^/]*|.*\\.(?:sav|save|savegame|ess|dat|db|sqlite|ldb|key|pem|p12|jks))$"
    )
    private val SAVE_DIRS = Regex("(?i)(^|/)(saves?|saved games|savegames?|documents|appdata/roaming|userdata|remote)(/|$)")

    internal fun allowed(relative: String): Boolean = !NEVER.containsMatchIn(relative) && !SAVE_DIRS.containsMatchIn(relative)

    private fun sessionParts(folder: File, prefix: String, kind: Kind, mainOnly: Boolean): List<Part> {
        if (!folder.isDirectory) return emptyList()
        val main = setOf("session.log", "app.log", "crash.log", "crash-app.txt", "wayland.log", "device.txt", "ended-without-teardown.txt")
        return folder.walkTopDown().filter { it.isFile }.map { it to it.relativeTo(folder).invariantSeparatorsPath }
            .filter { (f, rel) -> allowed(rel) && (!mainOnly || f.name in main) && !LogRedactor.isBinary(f) }
            .sortedBy { it.second }
            .map { (f, rel) -> Part("$prefix/${folder.name}/$rel", kind, f.length(), file = f) }.toList()
    }

    /** Proton logs (`steam-<appid>.log`, PROTON_LOG=1 without PROTON_LOG_DIR) of the last week, newest first. */
    fun protonLogs(root: File): List<File> {
        val since = System.currentTimeMillis() - RECENT_MS
        return listOf(File(root, "root"), File(root, "root/.droiddeck-x64")).flatMap { dir ->
            dir.listFiles { f -> f.isFile && PROTON_LOG.matches(f.name) && f.lastModified() >= since }.orEmpty().toList()
        }.sortedByDescending { it.lastModified() }
    }

    private val PROTON_LOG = Regex("steam-(\\d+)\\.log")

    fun appIdOf(protonLog: File): String? = PROTON_LOG.matchEntire(protonLog.name)?.groupValues?.get(1)

    fun collect(context: Context, options: Options = Options()): Plan {
        val notes = ArrayList<String>()
        learn(context)
        val folders = SessionPaths.sessionFolders(context)
        val latest = SessionPaths.current()?.takeIf { it.isDirectory } ?: folders.lastOrNull()
        val previous = latest?.let { l -> folders.filter { it != l && SessionPaths.chronological.compare(it, l) < 0 }.lastOrNull() }
        val parts = ArrayList<Part>()
        if (!SessionPrefs.logsEnabled(context)) notes += "Session logs are off (Setup > Diagnostics): sessions are discarded when they end."
        if (latest == null) notes += "No session folder was found." else parts += sessionParts(latest, "session", Kind.SESSION, mainOnly = false)
        if (latest != null) parts += SessionLogShare.liveSteamLogs(context, latest).map { Part("session/${latest.name}/steam/${it.name}", Kind.SESSION, it.length(), file = it) }
        if (options.previous && previous != null) parts += sessionParts(previous, "previous-session", Kind.PREVIOUS, mainOnly = true)
        val root = LinuxRuntime.rootDir(context)
        val proton = protonLogs(root)
        if (options.proton) {
            if (proton.isEmpty()) notes += "No Proton log of the last 7 days: PROTON_LOG was off (use \"Next launch with PROTON_LOG=1\")."
            proton.take(2).forEach { f -> parts += Part("proton/${f.name}", Kind.PROTON, f.length(), file = f) }
        }
        val logs = LinuxRuntime.logDir(context)
        SessionLogShare.recent(File(logs, SessionLogShare.STORES_DIR)).filter { allowed(it.name) && !LogRedactor.isBinary(it) }
            .forEach { parts += Part("stores/${it.name}", Kind.STORES, it.length(), file = it) }
        val layerSources = proton.take(2) + listOfNotNull(latest?.let { File(it, "session.log") }?.takeIf { it.isFile })
        parts += Part("layers/compat.log", Kind.LAYERS, -1, text = { layerLines(layerSources, COMPAT_LINE) })
        parts += Part("layers/wsi.log", Kind.LAYERS, -1, text = { layerLines(layerSources, WSI_LINE) })
        parts += Part("logcat.txt", Kind.LOGCAT, -1, text = { logcat(context, LOGCAT_LINES) })
        crashParts(context, notes).let { parts += it }
        val appId = SessionState.gameId?.takeIf { GameEnvironment.validScope(it) && it.isNotEmpty() } ?: proton.firstOrNull()?.let { appIdOf(it) }
        val env = envText(context, appId)
        parts += Part("env/game-env.txt", Kind.ENV, env.length.toLong(), text = { env })
        val info = info(context, appId, proton.firstOrNull(), latest)
        if (info.vulkanError) notes += "The logs show a Vulkan/driver error."
        parts.add(0, Part("summary.md", Kind.SUMMARY, -1, text = { ReportSummary.markdown(info) }))
        return Plan(parts.filter { it.path !in options.skip }, notes, info)
    }

    private val COMPAT_LINE = Regex("mali-compat:|mali-bridge:|hybris-lazyinit:|libhybris|droiddeck-game-env: game driver")
    private val WSI_LINE = Regex("mali-wsi|VK_LAYER_window_system_integration|gamescope-wsi|wsi:", RegexOption.IGNORE_CASE)

    /** The layers write to stderr, which lands in the Proton log and session.log: their lines, per source file. */
    internal fun layerLines(sources: List<File>, match: Regex, cap: Int = 20_000): String = buildString {
        append("# Extracted from the Proton log and session.log (the layers log to stderr).\n")
        for (f in sources) {
            if (!f.isFile) continue
            val kept = ArrayDeque<String>()
            runCatching {
                f.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
                    lines.filter { it.length < LogRedactor.MAX_LINE_LENGTH && match.containsMatchIn(it) }.forEach {
                        kept.addLast(it)
                        if (kept.size > cap) kept.removeFirst()
                    }
                }
            }
            append("\n## ").append(f.name).append(" (").append(kept.size).append(" lines)\n")
            kept.forEach { append(it).append('\n') }
        }
    }

    // ---- logcat ----

    private val LOGCAT_TAGS = Regex(
        "^(DroidDeck.*|Session.*|ReportBundle|CrashHandler|AgentBridge.*|Blobferry.*|LinuxRuntime.*|libhybris|mali_bridge|" +
            "VkLayer.*|vulkan|FEX.*|wine|DEBUG|libc|AndroidRuntime|ActivityManager|lowmemorykiller|lmkd)$"
    )
    private val THREADTIME = Regex("^\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+(\\d+)\\s+\\d+\\s+[VDIWEFA]\\s+([^:]*?)\\s*:")

    /** Lines of our own pids, or of an allowlisted tag (ActivityManager only when it names our package); the last [maxLines]. */
    internal fun filterLogcat(lines: Sequence<String>, pids: Set<Int>, packageName: String, maxLines: Int): List<String> {
        val kept = ArrayDeque<String>()
        for (line in lines) {
            val m = THREADTIME.find(line) ?: continue
            val pid = m.groupValues[1].toIntOrNull()
            val tag = m.groupValues[2]
            val ok = pid in pids || (LOGCAT_TAGS.matches(tag) && (tag != "ActivityManager" || line.contains(packageName)))
            if (!ok) continue
            kept.addLast(line)
            if (kept.size > maxLines) kept.removeFirst()
        }
        return kept.toList()
    }

    private fun logcat(context: Context, maxLines: Int): String {
        val pids = runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            am.runningAppProcesses.orEmpty().filter { it.uid == android.os.Process.myUid() }.map { it.pid }.toSet()
        }.getOrDefault(emptySet()) + android.os.Process.myPid()
        return try {
            val p = ProcessBuilder("/system/bin/logcat", "-d", "-v", "threadtime", "-t", (maxLines * 4).toString())
                .redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().useLines { filterLogcat(it, pids, context.packageName, maxLines) }
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            "# logcat -d -v threadtime, filtered to this app's processes and its tags (an app sees only its own lines).\n" +
                lines.joinToString("\n") + "\n"
        } catch (e: Exception) {
            "# logcat could not be read: ${e.javaClass.simpleName}\n"
        }
    }

    // ---- crash traces ----

    private fun crashParts(context: Context, notes: MutableList<String>): List<Part> {
        val parts = ArrayList<Part>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val exits = runCatching {
                context.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 8)
            }.getOrNull().orEmpty()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            val list = exits.joinToString("\n") { e ->
                "${stamp.format(Date(e.timestamp))} ${com.droiddeck.launcher.core.DeviceReport.exitSummary(e.processName, e.reason, e.status, e.description)}"
            }
            parts += Part("crash/exits.txt", Kind.CRASH, list.length.toLong(), text = { "# ApplicationExitInfo, this app's last exits\n$list\n" })
            exits.filter { it.reason in TRACE_REASONS }.take(3).forEach { e ->
                val name = "crash/exit-${stamp.format(Date(e.timestamp))}-${e.reason}.txt"
                parts += Part(name, Kind.CRASH, -1, text = {
                    runCatching { e.traceInputStream?.use { readCapped(it, CRASH_TRACE_BYTES) } }.getOrNull() ?: "(no trace was kept for this exit)\n"
                })
            }
        } else notes += "Exit traces need Android 11."
        val dropbox = dropBox(context)
        if (dropbox == null) notes += "DropBox: not readable without READ_LOGS; /data/tombstones is not readable by an app."
        else dropbox.forEachIndexed { i, text -> parts += Part("crash/dropbox-${i + 1}.txt", Kind.CRASH, text.length.toLong(), text = { text }) }
        return parts
    }

    private val TRACE_REASONS = setOf(5, 6, 4) // REASON_CRASH_NATIVE, REASON_ANR, REASON_CRASH

    private fun dropBox(context: Context): List<String>? = try {
        val box = context.getSystemService(android.os.DropBoxManager::class.java) ?: return null
        val since = System.currentTimeMillis() - RECENT_MS
        val out = ArrayList<String>()
        for (tag in listOf("data_app_crash", "data_app_native_crash", "data_app_anr")) {
            var t = since
            while (out.size < 3) {
                val e = box.getNextEntry(tag, t) ?: break
                t = e.timeMillis
                val text = e.getText(CRASH_TRACE_BYTES.toInt()).orEmpty()
                e.close()
                if (text.contains(context.packageName)) out += "# DropBox $tag ${Date(t)}\n$text"
            }
        }
        out
    } catch (e: SecurityException) {
        null
    }

    private fun readCapped(input: InputStream, cap: Long): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (bytes.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), cap - bytes.size()).toInt())
            if (n < 0) break
            bytes.write(buf, 0, n)
        }
        return bytes.toString("UTF-8")
    }

    // ---- env + summary ----

    private val SECRETISH = Regex("(?i)token|secret|password|passwd|cookie|key|auth|ticket|session")

    private fun secretName(k: String) = SECRETISH.containsMatchIn(k) && !k.startsWith("DXVK_") && !k.startsWith("VKD3D_")

    /** Env values whose names look like secrets are masked (the redactor cannot see an EOS_TOKEN=x as one). */
    internal fun maskEnv(entries: Map<String, String?>): Map<String, String?> =
        entries.mapValues { (k, v) -> if (v != null && secretName(k)) "<redacted:value>" else v }

    internal fun envLines(entries: Map<String, String?>): List<String> = maskEnv(entries).map { (k, v) -> if (v == null) "-$k" else "$k=$v" }

    private fun envText(context: Context, appId: String?): String = buildString {
        val config = runCatching { GameEnvironmentStore.read(context) }.getOrNull() ?: GameEnvironment.Config()
        append("# Per-game environment (the app's game settings). Game: ").append(appId ?: "unknown").append('\n')
        append("\n[shared]\n")
        envLines(config.shared).forEach { append(it).append('\n') }
        if (appId != null) {
            append("\n[game $appId]\n")
            envLines(config.entries(appId)).forEach { append(it).append('\n') }
            append("\n[in effect for $appId, defaults included]\n")
            envLines(runCatching { GameEnvironment.effective(config, SessionPrefs.fexPreset(context), appId, SessionPrefs.forceSsbs(context)) }
                .getOrDefault(emptyMap())).forEach { append(it).append('\n') }
        }
        if (SessionPrefs.nextLaunchLog(context)) append("\nPROTON_LOG=1 is set for the next launch only.\n")
        SessionService.envSwitchFile(context)?.takeIf { it.isFile }?.let { f ->
            append("\n[droiddeck-env switch file]\n")
            runCatching { f.readLines().take(200) }.getOrDefault(emptyList()).forEach { line ->
                val name = line.substringBefore('=').trim()
                append(if (line.contains('=') && SECRETISH.containsMatchIn(name)) "$name=<redacted:value>" else line).append('\n')
            }
        }
    }

    private fun md5(file: File): String = runCatching {
        val d = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; d.update(b, 0, n) } }
        d.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    const val FEX_DLL = "usr/local/lib/droiddeck-fex-arm64ec/libarm64ecfex.dll"

    private fun info(context: Context, appId: String?, protonLog: File?, latest: File?): ReportSummary.Info {
        val pm = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val gpu = runCatching { com.droiddeck.launcher.gpu.GpuInfo.detect() }.getOrNull()
        val vk = com.droiddeck.launcher.gpu.VulkanInfo.cachedOrNull().orEmpty()
        val bf = runCatching { com.droiddeck.launcher.gpu.BlobferryPackage.status(context) }.getOrNull()
        val root = LinuxRuntime.rootDir(context)
        val config = runCatching { GameEnvironmentStore.read(context) }.getOrNull() ?: GameEnvironment.Config()
        val gameEnv = if (appId != null) config.entries(appId) else emptyMap()
        val shared = config.shared
        fun flag(name: String) = gameEnv[name] ?: shared[name]
        val bridgeGame = (flag("ENABLE_MALI_COMPAT") ?: flag("ENABLE_MALI_WSI")) == "1" || flag(GameEnvironment.GAME_DRIVER) == "bridge" ||
            SessionState.gameDriver?.contains("bridge", true) == true
        val focus = when (flag("BL_FOCUS_GUARD")) {
            "0" -> "off (BL_FOCUS_GUARD=0)"
            "1" -> "on (BL_FOCUS_GUARD=1)"
            else -> if (bridgeGame) "on (bridge game)" else "off"
        }
        val boost = runCatching {
            "boost " + (if (SessionPrefs.performanceBoostActive(context)) "on" else "off") +
                (shared["BL_GAME_BOOST"]?.let { " (BL_GAME_BOOST=$it)" } ?: "") + if (SessionPrefs.boostCheck(context)) ", check on" else ""
        }.getOrDefault("?")
        val gameName = appId?.toIntOrNull()?.let { id ->
            runCatching { com.droiddeck.launcher.frontend.Library.steamGames(context).firstOrNull { it.appId == id }?.name }.getOrNull()
        }
        val proton = runCatching {
            val all = ComponentsManager.protons(context)
            ProtonDefault.selectedId(context, all)?.let { id -> all.firstOrNull { it.id == id }?.let { "${it.name} ${it.version}".trim() } ?: id }
        }.getOrNull()
        val lastExit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            context.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()?.let { com.droiddeck.launcher.core.DeviceReport.exitSummary(it.processName, it.reason, it.status, it.description) }
        }.getOrNull() else null
        val scan = listOfNotNull(protonLog, latest?.let { File(it, "session.log") }).filter { it.isFile }
        val vulkanError = scan.any { f -> runCatching { ReportSummary.vulkanDriverError(tailLines(f, 1024 * 1024)) }.getOrDefault(false) }
        return ReportSummary.Info(
            written = SimpleDateFormat("yyyy-MM-dd HH:mm zzz", Locale.US).format(Date()),
            appVersion = pm?.let { "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else 0})" } ?: "?",
            appId = context.packageName,
            buildType = BuildConfig.BUILD_TYPE + if (debuggable) ", debuggable" else "",
            sourceCommit = BuildConfig.SOURCE_COMMIT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE.orEmpty(),
            gpu = gpu?.name ?: vk["device"].orEmpty(),
            vendorDriver = listOfNotNull(vk["driver_name"], vk["driver_info"] ?: vk["driver_version"]).joinToString(" "),
            blobferryVersion = bf?.version.orEmpty(),
            blobferryCommit = bf?.commit.orEmpty() + if (bf?.dirty == true) "-dirty" else "",
            blobferrySource = bf?.source ?: "none",
            blobferryShimMd5 = bf?.shimMd5.orEmpty(),
            fexDllMd5 = File(root, FEX_DLL).takeIf { it.isFile }?.let { md5(it) }.orEmpty(),
            proton = proton ?: "?",
            displayDriver = SessionPrefs.androidDriver(context).ifEmpty { "auto" },
            runtimeDriver = runCatching { SessionPrefs.linuxDriver(context) }.getOrDefault("").ifEmpty { "runtime default" },
            sessionDriver = SessionState.sessionDriver ?: "-",
            gameDriver = flag(GameEnvironment.GAME_DRIVER) ?: SessionState.gameDriver ?: "-",
            game = if (appId == null) "unknown" else "${gameName ?: "app"} ($appId)",
            gameEnv = maskEnv(gameEnv),
            focusGuard = focus,
            boost = boost,
            logs = (if (SessionPrefs.logsEnabled(context)) "session logs on" else "session logs OFF") +
                if (SessionPrefs.gameDebugLogsEnabled(context)) ", game debug logs on" else "",
            protonLog = when {
                SessionPrefs.nextLaunchLog(context) -> "armed for the next launch"
                protonLog != null -> "found (${protonLog.name})"
                else -> "not found (PROTON_LOG off)"
            },
            lastExit = lastExit ?: "?",
            vulkanError = vulkanError,
        )
    }

    private fun tailLines(f: File, bytes: Long): Sequence<String> {
        val size = f.length()
        val start = (size - bytes).coerceAtLeast(0)
        val text = RandomAccessFile(f, "r").use { r -> r.seek(start); ByteArray((size - start).toInt()).also { r.readFully(it) } }
        return String(text, Charsets.ISO_8859_1).lineSequence()
    }

    /** Names the redactor removes wherever they appear: store accounts, the device name, the guest host name. */
    fun learn(context: Context) {
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val names = ArrayList<String>()
        for (store in com.droiddeck.launcher.stores.Store.entries) {
            runCatching { com.droiddeck.launcher.stores.StoreAccounts.signedInAs(context, store) }.getOrNull()
                ?.takeIf { it != store.label }?.let { names += it }
        }
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.let { names += it }
        runCatching { android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull()?.let { names += it }
        runCatching { SessionPrefs.guestHostname(context) }.getOrNull()?.let { names += it }
        ReportRedactor.learn(names.filter { it != Build.MODEL && it != Build.DEVICE })
    }

    // ---- build ----

    /** Per-part byte budgets: head+tail for one file, then the biggest parts halve until the total fits. */
    internal fun budgets(sizes: Map<String, Long>, max: Long): Map<String, Long> {
        val b = sizes.mapValues { (_, s) -> minOf(s.coerceAtLeast(0), HEAD_BYTES + TAIL_BYTES) }.toMutableMap()
        while (b.values.sum() > max) {
            val (key, value) = b.entries.filter { it.value > MIN_PART }.maxByOrNull { it.value } ?: break
            b[key] = (value / 2).coerceAtLeast(MIN_PART)
        }
        return b
    }

    fun shareDir(context: Context): File = File(context.cacheDir, "share")

    fun zipName(now: Date = Date()): String = "DroidDeck-report-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now) + ".zip"

    fun build(context: Context, plan: Plan, options: Options = Options(), onProgress: ((Float) -> Unit)? = null): Result {
        val dir = shareDir(context).apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("DroidDeck-report-") }?.forEach { it.delete() }
        val zip = File(dir, zipName())
        val result = write(plan.parts.filter { it.path !in options.skip }, plan.notes, zip, options, onProgress)
        markReported(context)
        return result
    }

    /** Writes [parts] (redacted unless [Options.raw]) and manifest.txt into [zip]. */
    fun write(parts: List<Part>, notes: List<String>, zip: File, options: Options = Options(), onProgress: ((Float) -> Unit)? = null): Result {
        val texts = HashMap<String, String>()
        fun textOf(p: Part, logcatLines: Int? = null): String = texts.getOrPut(p.path) {
            runCatching { p.text!!.invoke() }.getOrElse { "(could not be produced: ${it.javaClass.simpleName})\n" }
                .let { t -> if (logcatLines != null) t.lineSequence().toList().takeLast(logcatLines).joinToString("\n") else t }
        }
        val sizes = LinkedHashMap<String, Long>()
        for (p in parts) sizes[p.path] = if (p.file != null) p.file.length() else textOf(p).length.toLong()
        if (sizes.values.sum() > options.maxBytes) {
            parts.firstOrNull { it.kind == Kind.LOGCAT }?.let { p ->
                texts.remove(p.path)
                sizes[p.path] = textOf(p, LOGCAT_MIN_LINES).length.toLong()
            }
        }
        val budget = budgets(sizes, options.maxBytes)
        val entries = ArrayList<Entry>()
        val total = sizes.values.sum().coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (p in parts) {
                val source = sizes.getValue(p.path)
                val allowed = budget.getValue(p.path)
                z.putNextEntry(ZipEntry(p.path))
                val counter = CountingWriter(z.bufferedWriter())
                val stats = ReportRedactor.Stats()
                val trimmed = source > allowed
                if (p.file != null) {
                    writeFile(p.file, allowed, counter, options.raw, stats)
                } else {
                    val text = textOf(p)
                    val bytes = text.toByteArray()
                    if (!trimmed) emit(bytes.inputStream(), counter, options.raw, stats)
                    else writeTrimmed({ s, e -> java.io.ByteArrayInputStream(bytes, s.toInt(), (e - s).toInt()) }, bytes.size.toLong(), allowed, counter, options.raw, stats) { s, e -> lineBounds(bytes, s, e) }
                }
                counter.flush()
                z.closeEntry()
                entries += Entry(p.path, p.kind, source, counter.count, !options.raw, trimmed, stats.hits)
                done += source
                onProgress?.invoke((done.toFloat() / total).coerceAtMost(1f))
            }
            z.putNextEntry(ZipEntry("manifest.txt"))
            val w = z.bufferedWriter()
            w.write(manifest(entries, notes, options.raw))
            w.flush()
            z.closeEntry()
        }
        return Result(zip, entries, notes)
    }

    internal fun manifest(entries: List<Entry>, notes: List<String>, raw: Boolean): String = buildString {
        append("DroidDeck problem report: ").append(entries.size).append(" parts").append(if (raw) " (RAW, not redacted)" else ", every text redacted").append('\n')
        append("Never included: saves, Steam config/loginusers/ssfn, store credentials, cookies, shared preferences.\n\n")
        append(String.format(Locale.US, "%-56s %10s %10s %-8s %-7s %s\n", "part", "source", "written", "redacted", "trimmed", "hits"))
        for (e in entries) append(String.format(Locale.US, "%-56s %10d %10d %-8s %-7s %d\n", e.path, e.source, e.written,
            if (e.redacted) "yes" else "no", if (e.trimmed) "yes" else "no", e.hits))
        if (notes.isNotEmpty()) {
            append("\nNotes:\n")
            notes.forEach { append("- ").append(it).append('\n') }
        }
    }

    private class CountingWriter(val out: Writer) : Writer() {
        var count = 0L
        override fun write(cbuf: CharArray, off: Int, len: Int) { out.write(cbuf, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private fun emit(input: InputStream, out: Writer, raw: Boolean, stats: ReportRedactor.Stats) {
        if (!raw) { ReportRedactor.scrub(input, out, stats); return }
        input.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = CharArray(8192)
            while (true) { val n = r.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        }
    }

    private fun writeFile(f: File, allowed: Long, out: Writer, raw: Boolean, stats: ReportRedactor.Stats) {
        val size = f.length()
        if (size <= allowed) { f.inputStream().use { emit(it, out, raw, stats) }; return }
        RandomAccessFile(f, "r").use { raf ->
            writeTrimmed({ s, e -> RangeStream(raf, s, e) }, size, allowed, out, raw, stats) { s, e -> lineBounds(raf, s, e) }
        }
    }

    /** Head (1/5 of the budget) and tail, cut at line ends, with a marker line between them. */
    private fun writeTrimmed(
        open: (Long, Long) -> InputStream, size: Long, allowed: Long, out: Writer, raw: Boolean, stats: ReportRedactor.Stats,
        bounds: (Long, Long) -> Pair<Long, Long>,
    ) {
        val head = allowed / 5
        val (headEnd, tailStart) = bounds(head, size - (allowed - head))
        open(0, headEnd).use { emit(it, out, raw, stats) }
        out.write("\n... ${tailStart - headEnd} bytes trimmed (report size cap) ...\n")
        open(tailStart, size).use { emit(it, out, raw, stats) }
    }

    /** headEnd: just after the last '\n' before [head]; tailStart: just after the first '\n' from [tail]. */
    private fun lineBounds(raf: RandomAccessFile, head: Long, tail: Long): Pair<Long, Long> {
        fun scanBack(from: Long): Long {
            var p = from
            val buf = ByteArray(8192)
            while (p > 0) {
                val start = (p - buf.size).coerceAtLeast(0)
                raf.seek(start)
                val n = (p - start).toInt()
                raf.readFully(buf, 0, n)
                for (i in n - 1 downTo 0) if (buf[i] == '\n'.code.toByte()) return start + i + 1
                p = start
            }
            return 0
        }
        fun scanForward(from: Long): Long {
            var p = from
            val buf = ByteArray(8192)
            val size = raf.length()
            while (p < size) {
                raf.seek(p)
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) return p + i + 1
                p += n
            }
            return size
        }
        val h = scanBack(head)
        return h to scanForward(tail).coerceAtLeast(h)
    }

    private fun lineBounds(bytes: ByteArray, head: Long, tail: Long): Pair<Long, Long> {
        var h = head.toInt().coerceIn(0, bytes.size)
        while (h > 0 && bytes[h - 1] != '\n'.code.toByte()) h--
        var t = tail.toInt().coerceIn(0, bytes.size)
        if (t > 0 && bytes[t - 1] != '\n'.code.toByte()) { while (t < bytes.size && bytes[t] != '\n'.code.toByte()) t++; if (t < bytes.size) t++ }
        return h.toLong() to t.toLong().coerceAtLeast(h.toLong())
    }

    private class RangeStream(val raf: RandomAccessFile, start: Long, val end: Long) : InputStream() {
        private var pos = start
        override fun read(): Int {
            if (pos >= end) return -1
            raf.seek(pos++)
            return raf.read()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= end) return -1
            raf.seek(pos)
            val n = raf.read(b, off, minOf(len.toLong(), end - pos).toInt())
            if (n > 0) pos += n
            return n
        }
    }

    // ---- hand-off ----

    /** Copies [zip] to Downloads/DroidDeck (MediaStore on Android 10+, no permission). Returns the shown path, null on failure. */
    fun saveToDownloads(context: Context, zip: File): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DroidDeck")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out -> zip.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "Download/DroidDeck/${zip.name}"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidDeck").apply { mkdirs() }
            zip.copyTo(File(dir, zip.name), overwrite = true)
            "Download/DroidDeck/${zip.name}"
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not save the report to Downloads", e)
        null
    }

    /** Clears the one-shot PROTON_LOG once a game wrote a Proton log after it was set (a session without a game keeps it). */
    fun finishNextLaunchLog(context: Context, ended: File?) {
        val since = SessionPrefs.nextLaunchLogSince(context)
        if (since <= 0) return
        val logs = protonLogs(LinuxRuntime.rootDir(context)) + ended?.listFiles { f -> PROTON_LOG.matches(f.name) }.orEmpty()
        if (logs.any { it.lastModified() > since }) SessionPrefs.setNextLaunchLog(context, false)
    }

    // ---- crash entry state ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markReported(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong("lastReport", at).apply()
    }

    fun markCrashSeen(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong("crashSeen", at).apply()
    }

    private fun handled(context: Context): Long = maxOf(prefs(context).getLong("lastReport", 0), prefs(context).getLong("crashSeen", 0))

    /** When the app last crashed (crash-app.txt of a session, or a crash exit record), if not reported or dismissed since. */
    fun unreportedAppCrash(context: Context): Long? {
        val crashFile = SessionPaths.sessionFolders(context).takeLast(2).map { File(it, "crash-app.txt") }.filter { it.isFile }
            .maxOfOrNull { it.lastModified() } ?: 0L
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            context.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.reason == 4 || it.reason == 5 }.maxOfOrNull { it.timestamp }
        }.getOrNull() ?: 0L else 0L
        val at = maxOf(crashFile, exit)
        return at.takeIf { it > 0 && it > handled(context) }
    }

    private val GAME_CRASH = Regex(
        "Unhandled exception|unhandled page fault|wine: Unhandled|EXCEPTION_ACCESS_VIOLATION|VK_ERROR_DEVICE_LOST|" +
            "Backtrace:|c0000005|c000001d|err:seh:.*(?:crash|unhandled)|Fatal error|terminate called",
        RegexOption.IGNORE_CASE,
    )

    internal fun crashIn(lines: Sequence<String>): Boolean = lines.any { GAME_CRASH.containsMatchIn(it) }

    /** The newest Proton log shows a crash and was written after the last report (and within [withinMs]). */
    fun recentGameCrash(context: Context, withinMs: Long = 30 * 60_000L): Boolean {
        val log = protonLogs(LinuxRuntime.rootDir(context)).firstOrNull() ?: return false
        val m = log.lastModified()
        if (m <= handled(context) || m < System.currentTimeMillis() - withinMs) return false
        return runCatching { crashIn(tailLines(log, 512 * 1024)) }.getOrDefault(false)
    }
}
