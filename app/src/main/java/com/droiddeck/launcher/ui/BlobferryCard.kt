package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog

/** The Blobferry card of the GPU drivers page (Mali only): what is active, import, update check, roll back, reset. */
class BlobferryCardState(
    val version: String,
    val source: String,
    val commit: String,
    /** What the card shows for the commit: short commit, or "dev build (no commit)" for a hand-deployed build. */
    val commitText: String,
    val shimMd5: String,
    val zipMd5: String,
    val testBuild: Boolean,
    val changed: List<String>,
    /** "Roll back to …" label, null without a previous package. */
    val rollback: String?,
    val bundledVersion: String?,
    val canReset: Boolean,
    val canSwitch: Boolean,
    val busy: String?,
    val note: String,
    val files: Map<String, String>,
    val confirm: BlobferryConfirm? = null,
    val offer: String? = null,
    /** Hand-deployed build: the line naming the files that are not the bundled package's (null = not a dev build). */
    val devNote: String? = null,
    /** Offer dialog title for a PanVK / env defaults offer (null = the Blobferry title). */
    val offerTitle: String? = null,
    /** PanVK (the session driver): "PanVK <v> · <source> · md5 <8>", an amber note, the roll back label. */
    val panvkLine: String? = null,
    val panvkNote: String? = null,
    val panvkRollback: String? = null,
    /** Env defaults: "Env defaults <v> · <source> · N games", an amber note, the roll back label. */
    val envLine: String? = null,
    val envNote: String? = null,
    val envRollback: String? = null,
)

class BlobferryConfirm(val title: String, val text: String)

class BlobferryActions(
    val onImport: () -> Unit = {},
    val onCheck: () -> Unit = {},
    val onRollback: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onConfirm: (Boolean) -> Unit = {},
    val onOffer: (Boolean) -> Unit = {},
    val onPanvkRollback: () -> Unit = {},
    val onEnvRollback: () -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlobferryCard(state: BlobferryCardState, actions: BlobferryActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(14.dp)
    var details by rememberSaveable { mutableStateOf(false) }
    var resetAsk by rememberSaveable { mutableStateOf(false) }
    val idle = state.busy == null && state.canSwitch
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, pal.line, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("blobferry-card"),
    ) {
        Text(stringResource(R.string.blobferry_title), fontSize = 12.5.sp, color = colors.onSurfaceVariant)
        Text(
            stringResource(R.string.blobferry_version, state.version.ifEmpty { "?" }) + "  ·  " + state.source,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
        )
        if (state.commitText.isNotEmpty() || state.shimMd5.isNotEmpty()) FocusText(
            stringResource(R.string.blobferry_ids, state.commitText.ifEmpty { "?" }, state.shimMd5.take(8).ifEmpty { "?" },
                state.zipMd5.take(8).ifEmpty { "-" }),
            colors.onSurfaceVariant,
        ) { details = true }
        state.devNote?.let { Text(it, fontSize = 12.5.sp, color = AttentionAmber) }
        if (state.testBuild) Text(stringResource(R.string.blobferry_test_build), fontSize = 12.5.sp, color = AttentionAmber)
        if (state.changed.isNotEmpty()) Text(
            stringResource(R.string.blobferry_changed, state.changed.joinToString { it.substringAfterLast('/') }),
            fontSize = 12.5.sp, color = AttentionAmber,
        )
        state.panvkLine?.let { Text(it, fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.testTag("panvk-line")) }
        state.panvkNote?.let { Text(it, fontSize = 12.5.sp, color = AttentionAmber) }
        state.envLine?.let { Text(it, fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.testTag("envdefaults-line")) }
        state.envNote?.let { Text(it, fontSize = 12.5.sp, color = AttentionAmber) }
        val note = state.busy ?: if (!state.canSwitch) stringResource(R.string.blobferry_stop_session) else state.note
        if (note.isNotEmpty()) Text(note, fontSize = 12.5.sp, color = if (!state.canSwitch) AttentionAmber else colors.onSurfaceVariant)
        if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.blobferry_import), enabled = idle, compact = true,
                modifier = Modifier.testTag("blobferry-import"), onClick = actions.onImport)
            SecondaryButton(stringResource(R.string.blobferry_check), enabled = state.busy == null, compact = true,
                modifier = Modifier.testTag("blobferry-check"), onClick = actions.onCheck)
            state.rollback?.let {
                SecondaryButton(it, enabled = idle, compact = true, modifier = Modifier.testTag("blobferry-rollback"), onClick = actions.onRollback)
            }
            if (state.canReset) SecondaryButton(stringResource(R.string.blobferry_reset), enabled = idle, compact = true,
                modifier = Modifier.testTag("blobferry-reset")) { resetAsk = true }
            state.panvkRollback?.let {
                SecondaryButton(it, enabled = idle, compact = true, modifier = Modifier.testTag("panvk-rollback"), onClick = actions.onPanvkRollback)
            }
            // env defaults are read at each game start, so a running session does not block them
            state.envRollback?.let {
                SecondaryButton(it, enabled = state.busy == null, compact = true, modifier = Modifier.testTag("envdefaults-rollback"), onClick = actions.onEnvRollback)
            }
        }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.blobferry_details_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("commit ${state.commit.ifEmpty { state.commitText }}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                state.devNote?.let { Text(it, fontSize = 12.sp) }
                if (state.zipMd5.isNotEmpty()) Text("package md5 ${state.zipMd5}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("shim md5 ${state.shimMd5.ifEmpty { "?" }}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                for ((path, md5) in state.files.toSortedMap()) Text("$md5  $path", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            FocusText(stringResource(R.string.blobferry_details_copy), pal.signal) {
                clipboard.setText(AnnotatedString(blobferryDetailsText(state)))
                details = false
            }
        },
        dismissButton = { DialogCancel { details = false } },
    )
    if (resetAsk) AlertDialog(
        onDismissRequest = { resetAsk = false },
        title = { Text(stringResource(R.string.blobferry_reset_title)) },
        text = { Text(stringResource(R.string.blobferry_reset_text, state.bundledVersion.orEmpty()), fontSize = 13.sp) },
        confirmButton = { FocusText(stringResource(R.string.blobferry_reset), colors.error) { resetAsk = false; actions.onReset() } },
        dismissButton = { DialogCancel { resetAsk = false } },
    )
    state.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { actions.onConfirm(false) },
            title = { Text(c.title) },
            text = { Text(c.text, fontSize = 13.sp) },
            confirmButton = { FocusText(stringResource(R.string.blobferry_install), pal.signal, modifier = Modifier.testTag("blobferry-confirm")) { actions.onConfirm(true) } },
            dismissButton = { DialogCancel { actions.onConfirm(false) } },
        )
    }
    state.offer?.let { text ->
        AlertDialog(
            onDismissRequest = { actions.onOffer(false) },
            title = { Text(state.offerTitle ?: stringResource(R.string.blobferry_offer_title)) },
            text = { Text(text, fontSize = 13.sp) },
            confirmButton = { FocusText(stringResource(R.string.blobferry_download), pal.signal, modifier = Modifier.testTag("blobferry-download")) { actions.onOffer(true) } },
            dismissButton = {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                FocusText(stringResource(R.string.blobferry_not_now), colors.onBackground, modifier = Modifier.focusRequester(focus)) { actions.onOffer(false) }
            },
        )
    }
}

@Composable
private fun DialogCancel(onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    FocusText(stringResource(R.string.common_cancel), MaterialTheme.colorScheme.onBackground, modifier = Modifier.focusRequester(focus), onClick = onClick)
}

internal fun blobferryDetailsText(state: BlobferryCardState): String = buildString {
    append("Blobferry ").append(state.version).append(" (").append(state.source).append(")\n")
    append("commit ").append(state.commit.ifEmpty { state.commitText }).append('\n')
    state.devNote?.let { append(it).append('\n') }
    if (state.zipMd5.isNotEmpty()) append("package md5 ").append(state.zipMd5).append('\n')
    append("shim md5 ").append(state.shimMd5).append('\n')
    for ((path, md5) in state.files.toSortedMap()) append(md5).append("  ").append(path).append('\n')
}
