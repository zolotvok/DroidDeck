package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.session.MaliEnvDefaults
import org.json.JSONArray
import org.json.JSONObject

/**
 * The installed-components list (release-perf-1111.md § 3.2): one row per updater component with version, source,
 * commit, date, changelog, key file md5, previous version and whether a roll back is possible. `droiddeckctl components
 * status` returns it as JSON; later component kinds add a row here. [app, Claude Opus 5.5] 2026-11-13
 */
object InstalledComponents {
    class Row(
        val component: String, val version: String, val source: String, val commit: String, val date: String, val changelog: String,
        val keyFile: String, val keyFileMd5: String, val previous: String?, val canRollback: Boolean,
        val extra: JSONObject = JSONObject(),
    ) {
        fun json(): JSONObject = JSONObject().put("component", component).put("version", version).put("source", source)
            .put("commit", commit).put("date", date).put("changelog", changelog).put("keyFile", keyFile).put("keyFileMd5", keyFileMd5)
            .put("previous", previous ?: JSONObject.NULL).put("canRollback", canRollback).also { j -> extra.keys().forEach { j.put(it, extra.get(it)) } }
    }

    fun blobferry(s: BlobferryPackage.Status) = Row(
        BlobferryPackage.COMPONENT, s.version, s.source, s.commit, s.date, s.changelog,
        BlobferryPackage.COMPAT, s.files[BlobferryPackage.COMPAT].orEmpty(), s.previous, s.previous != null && s.canSwitch,
        JSONObject().put("shimMd5", s.shimMd5),
    )

    fun panvk(s: PanvkPackage.Status) = Row(
        PanvkPackage.COMPONENT, s.version, s.source, s.commit, s.date, s.changelog,
        PanvkPackage.LIB, s.soMd5, s.previous, s.previous != null && s.canSwitch && !s.notOurs,
        // the other PanVK slots (PanVK_Kbase, imports) are listed read-only: "user-installed, not managed"; inUse / sessionPanvk
        // say which PanVK the session actually runs (review M1)
        JSONObject().put("slot", s.slot).put("notOurs", s.notOurs).put("foreign", JSONArray(s.foreign.map { it.json() }))
            .put("inUse", s.inUse ?: JSONObject.NULL).put("sessionDriver", s.session?.driver?.ifEmpty { null } ?: JSONObject.NULL)
            .put("sessionPanvk", s.session?.panvk ?: JSONObject.NULL).put("note", s.note("status") ?: JSONObject.NULL),
    )

    fun envDefaults(s: MaliEnvDefaults.Status) = Row(
        MaliEnvDefaults.COMPONENT, s.version, s.source, "", "", "", "env-defaults.json", "", s.previous, s.previous != null,
        JSONObject().put("sha256", s.sha256).put("games", s.games),
    )

    /** Every component; one that cannot be read is a row with its error, the others still show. */
    fun status(context: Context): JSONArray {
        val out = JSONArray()
        fun add(component: String, row: () -> Row) {
            out.put(runCatching { row().json() }.getOrElse { JSONObject().put("component", component).put("error", it.message ?: it.javaClass.simpleName) })
        }
        add(BlobferryPackage.COMPONENT) { blobferry(BlobferryPackage.status(context)) }
        add(PanvkPackage.COMPONENT) { panvk(PanvkPackage.status(context)) }
        add(MaliEnvDefaults.COMPONENT) { envDefaults(MaliEnvDefaults.status(context)) }
        return out
    }
}
