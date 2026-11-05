package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.ReportSummary
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog

/** One row of the report preview: a part of the zip, ticked or not. */
class ReportRow(val path: String, val size: String, val ticked: Boolean)

class ReportUi(
    val crash: Boolean,
    /** The next-start card: when the app crashed, shown before anything is collected. */
    val askCrashAt: String?,
    val collecting: Boolean,
    val rows: List<ReportRow>,
    val notes: List<String>,
    val logsOff: Boolean,
    val busy: Float?,
    val preview: Pair<String, String>?,
    val category: ReportSummary.Category,
    val repo: ReportSummary.Repo,
    val suggested: ReportSummary.Repo,
    val nextLaunchLog: Boolean,
    val message: String?,
)

class ReportActions(
    val onTick: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onClosePreview: () -> Unit,
    val onShare: () -> Unit,
    val onSave: () -> Unit,
    val onCategory: (ReportSummary.Category) -> Unit,
    val onRepo: (ReportSummary.Repo) -> Unit,
    val onCopyIssue: () -> Unit,
    val onOpenIssue: () -> Unit,
    val onNextLaunchLog: (Boolean) -> Unit,
    val onLogsOn: () -> Unit,
    val onCrashAnswer: (Boolean) -> Unit,
    val onClose: () -> Unit,
)

internal fun categoryLabel(c: ReportSummary.Category): Int = when (c) {
    ReportSummary.Category.GRAPHICS -> R.string.report_cat_graphics
    ReportSummary.Category.GPU_FAULT -> R.string.report_cat_gpu_fault
    ReportSummary.Category.DRIVER_LAYER -> R.string.report_cat_driver_layer
    ReportSummary.Category.BRIDGE_CRASH -> R.string.report_cat_bridge_crash
    ReportSummary.Category.MALI_PERF -> R.string.report_cat_mali_perf
    ReportSummary.Category.APP_UI -> R.string.report_cat_app_ui
    ReportSummary.Category.STORE_LAUNCH -> R.string.report_cat_store_launch
    ReportSummary.Category.STEAM_WINDOW -> R.string.report_cat_steam_window
    ReportSummary.Category.SESSION_START -> R.string.report_cat_session_start
    ReportSummary.Category.UPDATER_REPORT -> R.string.report_cat_updater_report
    ReportSummary.Category.GAME_NO_START -> R.string.report_cat_game_no_start
    ReportSummary.Category.UNSURE -> R.string.report_cat_unsure
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(state: ReportUi, actions: ReportActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (state.crash) R.string.report_crash_title else R.string.report_title),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (state.askCrashAt != null) {
                Text(stringResource(R.string.report_crash_ask, state.askCrashAt), fontSize = 14.sp, color = colors.onBackground)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(R.string.report_crash_report), modifier = Modifier.testTag("report-crash-yes")) { actions.onCrashAnswer(true) }
                    SecondaryButton(stringResource(R.string.report_not_now), modifier = Modifier.testTag("report-crash-no")) { actions.onCrashAnswer(false) }
                }
                return@Column
            }
            Text(stringResource(R.string.report_intro), fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            if (state.logsOff) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.report_logs_off), fontSize = 13.sp, color = AttentionAmber, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.report_logs_turn_on), compact = true, onClick = actions.onLogsOn)
            }
            if (state.collecting) {
                Text(stringResource(R.string.report_collecting), fontSize = 13.sp, color = colors.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, pal.line, shape).padding(vertical = 6.dp),
                ) {
                    for (row in state.rows) Row(
                        Modifier.fillMaxWidth().clickable { actions.onOpen(row.path) }.padding(horizontal = 8.dp).testTag("report-part-${row.path}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = row.ticked, onCheckedChange = { actions.onTick(row.path) })
                        Text(row.path, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onBackground,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(row.size, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (state.notes.isNotEmpty()) Text(state.notes.joinToString("\n") { "• $it" }, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().clickable { actions.onNextLaunchLog(!state.nextLaunchLog) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.nextLaunchLog, onCheckedChange = { actions.onNextLaunchLog(it) }, modifier = Modifier.testTag("report-next-launch-log"))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_next_launch_log), fontSize = 14.sp, color = colors.onBackground)
                    Text(stringResource(R.string.report_next_launch_log_hint), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
            state.busy?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            state.message?.let { Text(it, fontSize = 13.sp, color = pal.signal) }
            val idle = state.busy == null && !state.collecting && state.rows.any { it.ticked }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.report_share), enabled = idle, modifier = Modifier.testTag("report-share"), onClick = actions.onShare)
                SecondaryButton(stringResource(R.string.report_save), enabled = idle, modifier = Modifier.testTag("report-save"), onClick = actions.onSave)
                SecondaryButton(stringResource(R.string.report_close), onClick = actions.onClose)
            }

            Text(stringResource(R.string.report_issue_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.report_category), fontSize = 13.sp, color = colors.onSurfaceVariant)
            Column {
                for (c in ReportSummary.Category.entries) Row(
                    Modifier.fillMaxWidth().clickable { actions.onCategory(c) }.testTag("report-cat-${c.key}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(state.category == c, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(stringResource(categoryLabel(c)), fontSize = 14.sp, color = colors.onBackground)
                }
            }
            Text(stringResource(R.string.report_repo), fontSize = 13.sp, color = colors.onSurfaceVariant)
            Column {
                for (r in ReportSummary.Repo.entries) Row(
                    Modifier.fillMaxWidth().clickable { actions.onRepo(r) }.testTag("report-repo-${r.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(state.repo == r, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(
                        stringResource(if (r == ReportSummary.Repo.BLOBFERRY) R.string.report_repo_blobferry else R.string.report_repo_fork) +
                            "  (" + r.slug + ")" + if (r == state.suggested) "  · " + stringResource(R.string.report_repo_suggested) else "",
                        fontSize = 14.sp, color = colors.onBackground,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.report_copy_issue), enabled = !state.collecting, modifier = Modifier.testTag("report-copy-issue"),
                    onClick = actions.onCopyIssue)
                SecondaryButton(stringResource(R.string.report_open_issue), enabled = !state.collecting, modifier = Modifier.testTag("report-open-issue"),
                    onClick = actions.onOpenIssue)
            }
        }
    }
    state.preview?.let { (path, text) ->
        AlertDialog(
            onDismissRequest = actions.onClosePreview,
            title = { Text(stringResource(R.string.report_preview_title, path), fontSize = 15.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                }
            },
            confirmButton = { FocusText(stringResource(R.string.report_close), pal.signal, onClick = actions.onClosePreview) },
        )
    }
}
