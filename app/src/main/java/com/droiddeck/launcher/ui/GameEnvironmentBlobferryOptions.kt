package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.BlobferryOptions
import com.droiddeck.launcher.runtime.LinuxRuntime

/**
 * "Blobferry options" in the game environment editor: every key of the installed package's options manifest
 * (usr/share/blobferry/capabilities.json), by category, with the control that fits its type. A value is written as
 * NAME=value into the profile's own entries, reset removes the entry (so the shared/default value applies again).
 * Without a usable manifest (missing file, older package) the five Mali switches built into the app are listed.
 * See docs/agents/rtx-switch-1107.md.
 */
@Composable
internal fun BlobferryOptionsPanel(
    own: Map<String, String?>, effective: Map<String, String?>, gameName: String?, enabled: Boolean,
    onSet: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val manifest = remember { BlobferryOptions.load(LinuxRuntime.rootDir(context)) }
    val options = remember(manifest) { manifest ?: BlobferryOptions.fallback { context.getString(it) } }
    var expanded by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val groups = BlobferryOptions.grouped(options, showDebug)
    Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, LocalPalette.current.line, Shape14)) {
        SettingsRow(stringResource(R.string.bo_title), stringResource(R.string.bo_hint, options.size)) {
            SecondaryButton(
                stringResource(if (expanded) R.string.bo_hide else R.string.bo_show),
                modifier = Modifier.testTag("blobferry-options-toggle").semantics { contentDescription = context.getString(if (expanded) R.string.bo_a11y_hide else R.string.bo_a11y_show) },
            ) { expanded = !expanded }
        }
        if (!expanded) return@Column
        if (manifest == null) Text(
            stringResource(R.string.bo_fallback_note), fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        if (manifest != null && manifest.any { it.status == BlobferryOptions.Status.DEBUG_ONLY }) {
            SettingsRow(stringResource(R.string.bo_debug_toggle), stringResource(R.string.bo_debug_hint)) {
                ToggleSwitch(showDebug, true, stringResource(R.string.bo_debug_toggle), Modifier.testTag("blobferry-options-debug")) { showDebug = it }
            }
        }
        for ((category, list) in groups) {
            Text(
                category.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.signal,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
            )
            for (option in list) {
                BlobferryOptionRow(
                    option, own.containsKey(option.name), effective[option.name], gameName, enabled,
                    menu == "bo:${option.name}", { menu = if (menu == "bo:${option.name}") null else "bo:${option.name}" }, { menu = null }, onSet,
                )
            }
        }
    }
}

@Composable
private fun BlobferryOptionRow(
    option: BlobferryOptions.Option, isOwn: Boolean, current: String?, gameName: String?, enabled: Boolean,
    menuOpen: Boolean, onMenu: () -> Unit, onDismiss: () -> Unit, onSet: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val value = current ?: option.default
    val proven = option.provenValue(gameName)
    val description = option.description.ifEmpty { option.name }
    val a11y = stringResource(R.string.bo_a11y_option, option.name, description)
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).padding(horizontal = 14.dp).background(LocalPalette.current.line))
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text(option.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text(description, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    when (option.status) {
                        BlobferryOptions.Status.EXPERIMENTAL -> Chip(stringResource(R.string.bo_experimental), LocalPalette.current.signal)
                        BlobferryOptions.Status.DEBUG_ONLY -> Chip(stringResource(R.string.bo_debug_badge), colors.onSurfaceVariant)
                        else -> {}
                    }
                    if (proven != null) Chip(
                        stringResource(R.string.bo_proven, option.games.joinToString { it.game }) + if (proven.isNotEmpty()) " " + stringResource(R.string.bo_proven_value, proven) else "",
                        LocalPalette.current.signal,
                    )
                }
            }
            when (option.control) {
                BlobferryOptions.Control.SWITCH -> ToggleSwitch(
                    BlobferryOptions.isOn(value), enabled, option.name,
                    Modifier.testTag("blobferry-option-${option.name}").semantics { contentDescription = a11y },
                ) { onSet(option.name, if (it) "1" else "0") }
                BlobferryOptions.Control.DROPDOWN -> Box {
                    ValueChip(
                        value ?: stringResource(R.string.game_env_select_value), menuOpen, enabled = enabled,
                        modifier = Modifier.widthIn(min = 96.dp, max = 200.dp).heightIn(min = 48.dp).testTag("blobferry-option-${option.name}")
                            .semantics { contentDescription = context.getString(R.string.bo_a11y_choose, option.name, value ?: "") },
                        onClick = onMenu,
                    )
                    AnchoredMenu(menuOpen, onDismiss = onDismiss, title = option.name) { first ->
                        for ((i, choice) in option.choices.withIndex()) MenuItem(choice, checked = choice == value, focusRequester = if (i == 0) first else null) {
                            onDismiss(); onSet(option.name, choice)
                        }
                    }
                }
                BlobferryOptions.Control.TEXT -> {}
            }
            if (option.control != BlobferryOptions.Control.TEXT && isOwn) ResetButton(option.name, enabled) { onSet(option.name, null) }
        }
        if (option.control == BlobferryOptions.Control.TEXT) TextOptionEditor(option, isOwn, current, enabled, onSet)
    }
}

@Composable
private fun TextOptionEditor(option: BlobferryOptions.Option, isOwn: Boolean, current: String?, enabled: Boolean, onSet: (String, String?) -> Unit) {
    val context = LocalContext.current
    var draft by remember(option.name, current) { mutableStateOf(current.orEmpty()) }
    val valid = BlobferryOptions.valid(option, draft)
    val changed = draft != current.orEmpty()
    val set = { if (valid && enabled) onSet(option.name, draft) }
    val label = when {
        option.kind == BlobferryOptions.Kind.INT && option.min != null && option.max != null -> stringResource(R.string.bo_int_range, option.min!!, option.max!!)
        option.kind == BlobferryOptions.Kind.INT && option.min != null -> stringResource(R.string.bo_int_min, option.min!!)
        option.kind == BlobferryOptions.Kind.INT -> stringResource(R.string.bo_int)
        option.kind == BlobferryOptions.Kind.HEX -> stringResource(R.string.bo_hex)
        option.kind == BlobferryOptions.Kind.LIST -> stringResource(R.string.bo_list)
        else -> stringResource(R.string.bo_text)
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AdbTextField(
            draft, { draft = it }, label,
            if (option.kind == BlobferryOptions.Kind.INT) KeyboardType.Number else KeyboardType.Ascii, ImeAction.Done,
            modifier = Modifier.weight(1f).testTag("blobferry-option-${option.name}")
                .semantics { contentDescription = context.getString(R.string.bo_a11y_field, option.name, label) },
            placeholder = option.default ?: option.hint, compact = true, onDone = set,
        )
        SecondaryButton(
            stringResource(R.string.bo_set), enabled = enabled && valid && changed,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = context.getString(R.string.bo_a11y_set, option.name) },
            onClick = set,
        )
        if (isOwn) ResetButton(option.name, enabled) { onSet(option.name, null) }
    }
    if (draft.isNotEmpty() && !valid) Text(stringResource(R.string.bo_invalid, label), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ResetButton(name: String, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp), contentAlignment = Alignment.Center) {
        FocusText(
            stringResource(R.string.bo_reset), LocalPalette.current.signal,
            Modifier.semantics { contentDescription = context.getString(R.string.bo_a11y_reset, name) },
        ) { if (enabled) onClick() }
    }
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    val shape = RoundedCornerShape(99.dp)
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.clip(shape).border(1.dp, color.copy(alpha = 0.6f), shape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
