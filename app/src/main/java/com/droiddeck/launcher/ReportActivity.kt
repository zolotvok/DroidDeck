package com.droiddeck.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.ReportRedactor
import com.droiddeck.launcher.session.ReportBundle
import com.droiddeck.launcher.session.ReportSummary
import com.droiddeck.launcher.session.SessionLogShare
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.ReportActions
import com.droiddeck.launcher.ui.ReportRow
import com.droiddeck.launcher.ui.ReportScreen
import com.droiddeck.launcher.ui.ReportUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Report a problem": preview with tick boxes, then share, save to Downloads, or copy a GitHub issue. Never sends anything itself. */
class ReportActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ReportActivity"
        const val EXTRA_CRASH = "crash"
        const val EXTRA_ASK_CRASH_AT = "askCrashAt"

        fun start(context: Context, crash: Boolean = false, askCrashAt: Long = 0L) {
            val intent = Intent(context, ReportActivity::class.java).putExtra(EXTRA_CRASH, crash).putExtra(EXTRA_ASK_CRASH_AT, askCrashAt)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "could not open the report", it) }
        }

        /** At app start: offer the report once when the app crashed since the last report or "Not now".
         *  Not with Debugging tools on: test plans drive the front end and must not meet an extra screen. */
        fun offerAfterCrash(context: Context) {
            if (runCatching { com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(context) }.getOrDefault(false)) return
            Thread({
                val at = runCatching { ReportBundle.unreportedAppCrash(context) }.getOrNull() ?: return@Thread
                Handler(Looper.getMainLooper()).post { start(context, crash = true, askCrashAt = at) }
            }, "report-crash-check").start()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var plan: ReportBundle.Plan? = null
    private var crash = false
    private var askCrashAt by mutableStateOf<String?>(null)
    private var askCrashTime = 0L
    private var collecting by mutableStateOf(true)
    private var ticked by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var rows by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var notes by mutableStateOf<List<String>>(emptyList())
    private var busy by mutableStateOf<Float?>(null)
    private var preview by mutableStateOf<Pair<String, String>?>(null)
    private var category by mutableStateOf(ReportSummary.Category.UNSURE)
    private var repo by mutableStateOf(ReportSummary.Repo.FORK)
    private var repoChosen = false
    private var nextLaunchLog by mutableStateOf(false)
    private var logsOff by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var built: File? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crash = intent.getBooleanExtra(EXTRA_CRASH, false)
        askCrashTime = intent.getLongExtra(EXTRA_ASK_CRASH_AT, 0L)
        if (askCrashTime > 0) askCrashAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(askCrashTime))
        if (crash) category = ReportSummary.Category.UNSURE
        nextLaunchLog = SessionPrefs.nextLaunchLog(this)
        logsOff = !SessionPrefs.logsEnabled(this)
        setContent { DroidDeckTheme { ReportScreen(ui(), actions()) } }
        if (askCrashAt == null) collect()
    }

    private fun ui() = ReportUi(
        crash = crash, askCrashAt = askCrashAt, collecting = collecting,
        rows = rows.map { (path, size) -> ReportRow(path, size, ticked[path] != false) },
        notes = notes, logsOff = logsOff, busy = busy, preview = preview, category = category, repo = repo,
        suggested = suggested(), nextLaunchLog = nextLaunchLog, message = message,
    )

    private fun suggested() = ReportSummary.suggestRepo(category, plan?.info?.vulkanError == true)

    private fun actions() = ReportActions(
        onTick = { path -> ticked = ticked + (path to (ticked[path] == false)); built = null },
        onOpen = ::openPreview,
        onClosePreview = { preview = null },
        onShare = { build { zip -> runCatching { startActivity(SessionLogShare.shareIntent(this, zip)) }.onFailure { toast(R.string.report_build_failed) } } },
        onSave = {
            build { zip ->
                Thread({
                    val where = ReportBundle.saveToDownloads(this, zip)
                    main.post { message = if (where != null) getString(R.string.report_saved, where) else getString(R.string.report_save_failed) }
                }, "report-save").start()
            }
        },
        onCategory = { c -> category = c; if (!repoChosen) repo = suggested() },
        onRepo = { r -> repo = r; repoChosen = true },
        onCopyIssue = { copyIssue() },
        onOpenIssue = { openIssue() },
        onNextLaunchLog = { on ->
            nextLaunchLog = on
            Thread({ SessionPrefs.setNextLaunchLog(this, on) }, "report-next-log").start()
        },
        onLogsOn = { SessionPrefs.setLogsEnabled(this, true); logsOff = false },
        onCrashAnswer = { yes ->
            askCrashAt = null
            if (yes) collect() else { ReportBundle.markCrashSeen(this, maxOf(askCrashTime, System.currentTimeMillis())); finish() }
        },
        onClose = { finish() },
    )

    private fun collect() {
        collecting = true
        Thread({
            val p = runCatching { ReportBundle.collect(this) }.onFailure { Log.w(TAG, "collect failed", it) }.getOrNull()
            main.post {
                plan = p
                collecting = false
                if (p == null) { message = getString(R.string.report_build_failed); return@post }
                rows = p.parts.map { it.path to if (it.size >= 0) FileUtils.sizeToString(it.size) else "…" }
                ticked = p.parts.associate { it.path to true }
                notes = p.notes
                if (!repoChosen) repo = suggested()
                if (crash) ReportBundle.markCrashSeen(this)
            }
        }, "report-collect").start()
    }

    private fun openPreview(path: String) {
        val part = plan?.parts?.firstOrNull { it.path == path } ?: return
        Thread({
            val text = runCatching {
                val raw = part.file?.let { f -> java.io.RandomAccessFile(f, "r").use { r ->
                    ByteArray(minOf(r.length(), 512L * 1024).toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8)
                } }
                    ?: part.text!!.invoke().lineSequence().take(200).joinToString("\n")
                ReportRedactor.preview(raw, 200)
            }.getOrElse { "(${it.javaClass.simpleName})" }
            main.post { preview = path to text }
        }, "report-preview").start()
    }

    private fun build(then: (File) -> Unit) {
        val p = plan ?: return
        built?.takeIf { it.isFile }?.let { then(it); return }
        val skip = ticked.filterValues { !it }.keys
        busy = 0f
        message = null
        Thread({
            val result = runCatching { ReportBundle.build(this, p, ReportBundle.Options(skip = skip)) { f -> main.post { if (busy != null) busy = f } } }
                .onFailure { Log.w(TAG, "report build failed", it) }.getOrNull()
            main.post {
                busy = null
                if (result == null) { toast(R.string.report_build_failed); return@post }
                built = result.zip
                val trimmed = result.entries.count { it.trimmed }
                val hits = result.entries.sumOf { it.hits }
                message = getString(R.string.report_built, result.zip.name, FileUtils.sizeToString(result.zip.length()), hits, trimmed)
                then(result.zip)
            }
        }, "report-build").start()
    }

    private fun issueText(): Pair<String, String>? {
        val p = plan ?: return null
        val title = ReportSummary.issueTitle(category, p.info)
        val body = ReportRedactor.redactText(ReportSummary.issue(p.info, category, built?.name))
        return title to body
    }

    private fun copyIssue(): Boolean {
        val (_, body) = issueText() ?: return false
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("DroidDeck issue", body))
        message = getString(R.string.report_issue_copied)
        return true
    }

    private fun openIssue() {
        val (title, body) = issueText() ?: return
        copyIssue()
        val url = ReportSummary.issueUrl(repo, title, body)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { toast(R.string.report_no_browser) }
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()
}
