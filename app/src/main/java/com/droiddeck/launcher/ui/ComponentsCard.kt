package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog

/** What a Components card button does; the caller maps it to the package function the matching droiddeckctl op calls. */
enum class ComponentOp { ROLLBACK, RESET, USE, UNUSE }

/** Why a shown button is disabled. */
enum class ComponentBlock { SESSION_RUNNING, NOT_OURS, BUSY }

class ComponentAction(val op: ComponentOp, val block: ComponentBlock?) {
    val enabled get() = block == null
}

/** A foreign PanVK slot: listed only ("user-installed, not managed"). */
class ForeignSlot(val id: String, val name: String, val driverVersion: String)

/** One row of the Components card, built by [componentRows] from InstalledComponents.status() (the `components status` JSON). */
class ComponentRow(
    val component: String,
    /** "" = not installed. */
    val version: String,
    val source: String,
    val commit: String,
    val date: String,
    val changelog: String,
    val keyFile: String,
    /** "md5 <8>" / "sha256 <8>", "" without one. */
    val keyHash: String,
    val previous: String?,
    val notes: List<String>,
    /** The row could not be read: only this is shown. */
    val error: String? = null,
    val actions: List<ComponentAction> = emptyList(),
    /** PanVK: the session's driver id ("" = the runtime's own), null when unknown or not PanVK. */
    val inUse: String? = null,
    /** PanVK: what [ComponentOp.UNUSE] goes back to ("" = the runtime's own driver). */
    val useBack: String? = null,
    val foreign: List<ForeignSlot> = emptyList(),
) {
    fun action(op: ComponentOp) = actions.firstOrNull { it.op == op }
}

class ComponentsCardState(val rows: List<ComponentRow>, val busy: Boolean, val sessionRunning: Boolean)

/** env-defaults are read at each game start, so a running session does not block their switch (as the Blobferry card). */
private val NOT_SESSION_BOUND = setOf("env-defaults")
private val BUNDLED_SOURCES = setOf("bundled", "built-in")

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.textOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

/**
 * The card's rows from [components] (the same JSONArray `droiddeckctl components status` returns). Roll back shows only with
 * a previous package, Reset to bundled only when the app bundles one and the component is not already on it; both are
 * disabled while [sessionRunning] (env-defaults excepted) or [busy]. PanVK: Use when our slot is installed, not in use and
 * the session runs a PanVK (when `panvk use` accepts it); Back to (unuse) while a use record exists; a slot that is not
 * ours disables every button.
 */
fun componentRows(components: JSONArray, sessionRunning: Boolean, busy: Boolean): List<ComponentRow> =
    (0 until components.length()).mapNotNull { i -> components.optJSONObject(i) }.map { j ->
        val component = j.text("component")
        j.textOrNull("error")?.let { return@map ComponentRow(component, "", "", "", "", "", "", "", null, emptyList(), error = it) }
        val source = j.text("source")
        val version = j.text("version").takeIf { source != "none" }.orEmpty()
        val previous = j.textOrNull("previous")
        val notOurs = j.optBoolean("notOurs", false)
        fun block(): ComponentBlock? = when {
            notOurs -> ComponentBlock.NOT_OURS
            busy -> ComponentBlock.BUSY
            sessionRunning && component !in NOT_SESSION_BOUND -> ComponentBlock.SESSION_RUNNING
            else -> null
        }
        val actions = mutableListOf<ComponentAction>()
        if (previous != null) actions += ComponentAction(ComponentOp.ROLLBACK, block())
        val bundled = j.textOrNull("bundledVersion")
        val canReset = source !in BUNDLED_SOURCES && (bundled != null || component in NOT_SESSION_BOUND && source != "none")
        if (canReset) actions += ComponentAction(ComponentOp.RESET, block())
        var inUse: String? = null
        var useBack: String? = null
        var foreign = emptyList<ForeignSlot>()
        if (component == "panvk") {
            val slot = j.text("slot")
            // inUse is null when the session's driver is unknown; sessionDriver null = the runtime's own driver
            inUse = if (j.isNull("inUse")) null else j.text("sessionDriver")
            useBack = j.textOrNull("useBack")
            val ours = j.optBoolean("inUse", false)
            val sessionPanvk = j.optJSONObject("sessionPanvk")
            if (useBack != null) actions += ComponentAction(ComponentOp.UNUSE, block())
            else if (!ours && !notOurs && source != "none" && sessionPanvk != null && inUse != slot) actions += ComponentAction(ComponentOp.USE, block())
            foreign = j.optJSONArray("foreign")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { ForeignSlot(it.text("id"), it.text("name"), it.text("driverVersion")) }
            }.orEmpty()
        }
        val md5 = j.text("keyFileMd5")
        val sha = j.text("sha256")
        val notes = listOfNotNull(
            j.textOrNull("note"), j.textOrNull("shadowed"), j.textOrNull("problem"),
        ).filter { it.isNotBlank() }
        ComponentRow(
            component, version, source, j.text("commit").take(7), j.text("date"), j.text("changelog"), j.text("keyFile"),
            when {
                md5.isNotEmpty() -> "md5 ${md5.take(8)}"
                sha.isNotEmpty() -> "sha256 ${sha.take(8)}"
                else -> ""
            },
            previous, notes, null, actions, inUse, useBack, foreign,
        )
    }

/** The Components card of the GPU drivers page (Mali only): every updater component, roll back / reset / PanVK use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComponentsCard(state: ComponentsCardState, onAction: (String, ComponentOp) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    var ask by rememberSaveable { mutableStateOf<String?>(null) }
    val runtimeOwn = stringResource(R.string.components_runtime_driver)
    fun driver(id: String) = id.ifEmpty { runtimeOwn }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, pal.line, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("components-card"),
    ) {
        Text(stringResource(R.string.components_title), fontSize = 12.5.sp, color = colors.onSurfaceVariant)
        if (state.sessionRunning) Text(stringResource(R.string.components_stop_session), fontSize = 12.5.sp, color = AttentionAmber)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        for (row in state.rows) ComponentRowView(row, ::driver) { op -> ask = "${row.component}|${op.name}" }
    }
    val pending = ask?.split('|')?.takeIf { it.size == 2 }?.let { (c, op) ->
        state.rows.firstOrNull { it.component == c }?.let { row -> ComponentOp.values().firstOrNull { it.name == op }?.let { row to it } }
    }
    pending?.let { (row, op) ->
        val name = row.component
        val (title, text, confirm) = when (op) {
            ComponentOp.ROLLBACK -> Triple(stringResource(R.string.components_rollback_title, name),
                stringResource(R.string.components_rollback_text, name, row.previous.orEmpty()), stringResource(R.string.components_rollback))
            ComponentOp.RESET -> Triple(stringResource(R.string.components_reset_title, name),
                stringResource(R.string.components_reset_text, name), stringResource(R.string.blobferry_reset))
            ComponentOp.USE -> Triple(stringResource(R.string.components_use_title),
                stringResource(R.string.components_use_text, driver(row.inUse.orEmpty())), stringResource(R.string.components_use_panvk))
            ComponentOp.UNUSE -> driver(row.useBack.orEmpty()).let { back ->
                Triple(stringResource(R.string.components_unuse_title, back), stringResource(R.string.components_unuse_text, back),
                    stringResource(R.string.components_unuse, back))
            }
        }
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(title) },
            text = { Text(text, fontSize = 13.sp) },
            confirmButton = {
                FocusText(confirm, if (op == ComponentOp.RESET) colors.error else pal.signal, modifier = Modifier.testTag("components-confirm")) {
                    ask = null
                    onAction(name, op)
                }
            },
            dismissButton = { DialogCancel { ask = null } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentRowView(row: ComponentRow, driver: (String) -> String, onAsk: (ComponentOp) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("components-row-${row.component}")) {
        Text(
            row.component + "  " + row.version.ifEmpty { stringResource(R.string.components_not_installed) },
            fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
        )
        if (row.error != null) {
            Text(stringResource(R.string.components_unreadable, row.error), fontSize = 12.5.sp, color = AttentionAmber)
        } else {
            val ids = listOfNotNull(
                componentSourceLabel(row.source),
                row.commit.ifEmpty { null }?.let { stringResource(R.string.components_commit, it) },
                row.date.ifEmpty { null },
                row.keyHash.ifEmpty { null }?.let { "${row.keyFile} $it" },
            )
            if (ids.isNotEmpty()) Text(ids.joinToString("  ·  "), fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            if (row.changelog.isNotEmpty()) Text(row.changelog, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            row.previous?.let { Text(stringResource(R.string.components_previous, it), fontSize = 12.5.sp, color = colors.onSurfaceVariant) }
            row.inUse?.let { Text(stringResource(R.string.components_in_use, driver(it)), fontSize = 12.5.sp, color = colors.onBackground) }
            for (f in row.foreign) Text(
                stringResource(R.string.components_foreign, f.name.ifEmpty { f.id }, f.id, f.driverVersion.ifEmpty { "?" }),
                fontSize = 12.sp, color = colors.onSurfaceVariant,
            )
            for (note in row.notes) Text(note, fontSize = 12.sp, color = AttentionAmber)
            if (row.actions.any { it.block == ComponentBlock.NOT_OURS }) {
                Text(stringResource(R.string.components_not_ours), fontSize = 12.sp, color = AttentionAmber)
            }
            if (row.actions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in row.actions) {
                    val label = when (a.op) {
                        ComponentOp.ROLLBACK -> stringResource(R.string.components_rollback)
                        ComponentOp.RESET -> stringResource(R.string.blobferry_reset)
                        ComponentOp.USE -> stringResource(R.string.components_use_panvk)
                        ComponentOp.UNUSE -> stringResource(R.string.components_unuse, driver(row.useBack.orEmpty()))
                    }
                    SecondaryButton(label, enabled = a.enabled, compact = true,
                        modifier = Modifier.testTag("components-${row.component}-${a.op.name.lowercase()}")) { onAsk(a.op) }
                }
            }
        }
    }
}

/** The source as the neighbouring Blobferry card names it; an unknown one as it is. */
@Composable
private fun componentSourceLabel(source: String): String? = when (source) {
    "" -> null
    "bundled" -> stringResource(R.string.blobferry_src_bundled)
    "file" -> stringResource(R.string.blobferry_src_file)
    "download" -> stringResource(R.string.blobferry_src_download)
    "adb" -> stringResource(R.string.blobferry_src_adb)
    "dev" -> stringResource(R.string.blobferry_src_dev)
    "none" -> stringResource(R.string.blobferry_src_none)
    "hand-imported" -> stringResource(R.string.panvk_src_hand)
    "changed on disk" -> stringResource(R.string.panvk_src_changed)
    "built-in" -> stringResource(R.string.envdefaults_src_builtin)
    else -> source
}
