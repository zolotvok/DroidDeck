package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.epic.EpicApiClient
import com.droiddeck.launcher.stores.epic.EpicCredentialStore
import com.droiddeck.launcher.stores.epic.EpicDownloadManager
import com.droiddeck.launcher.stores.gog.GogAuth
import com.droiddeck.launcher.stores.gog.GogDownloadManager
import org.json.JSONObject
import java.io.File

object StoreListBackfill {
    private const val TAG = "StoreListBackfill"
    private const val FILE = "store-list-backfill.json"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    fun interface Fetcher {
        fun fetch(context: Context, sidecar: StoreGameSidecar): Map<String, String>?
    }

    internal val storeFetcher = Fetcher { context, sidecar -> fromStore(context, sidecar) }

    @VisibleForTesting internal var fetcher: Fetcher = storeFetcher

    fun needs(sidecar: StoreGameSidecar): Boolean =
        when (sidecar.store) {
            Store.GOG -> WinCompSources.LIST_READ !in sidecar.extra
            Store.EPIC -> WinCompSources.LIST_READ !in sidecar.extra || WinCompSources.EPIC_LAUNCH_READ !in sidecar.extra ||
                sidecar.extra[WinCompSources.EPIC_PREREQ_PATH].orEmpty().isNotEmpty() && WinCompSources.EPIC_PREREQ_ARGS !in sidecar.extra
            else -> false
        }

    @Synchronized
    fun fill(context: Context, folder: File): Boolean {
        val sidecar = StoreGameSidecar.read(folder)?.takeIf { it.isInstalled && needs(it) } ?: return false
        val tries = runCatching { JSONObject(AtomicFile(File(context.filesDir, FILE)).readFully().toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        if (now - tries.optLong(folder.path, 0L) < RETRY_AFTER_MS) return false
        if (!AutoComponents.online(context)) return false
        val list = runCatching { fetcher.fetch(context, sidecar) }.getOrNull()
        if (list == null) {
            tries.put(folder.path, now)
            val atomic = AtomicFile(File(context.filesDir, FILE))
            val out = atomic.startWrite()
            try { out.write(tries.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out) }
            Log.i(TAG, "${sidecar.title}: the ${sidecar.store.label} list could not be had; again in a week")
            return false
        }
        val current = StoreGameSidecar.read(folder) ?: return false
        val read = if (sidecar.store == Store.EPIC) mapOf(WinCompSources.LIST_READ to "1", WinCompSources.EPIC_LAUNCH_READ to "1")
            else mapOf(WinCompSources.LIST_READ to "1")
        current.copy(extra = current.extra + list + read).write(folder)
        Log.i(TAG, "backfill: ${sidecar.title} " + when (sidecar.store) {
            Store.GOG -> "gog deps=[${list[WinCompSources.GOG_DEPENDENCIES].orEmpty()}]"
            else -> "epic prereq=" + (list[WinCompSources.EPIC_PREREQ_NAME].orEmpty().ifEmpty { list[WinCompSources.EPIC_PREREQ_PATH].orEmpty() }.ifEmpty { "none" }) +
                " launch=[${list[WinCompSources.EPIC_LAUNCH_COMMAND].orEmpty()}]"
        })
        return true
    }

    private fun fromStore(context: Context, sidecar: StoreGameSidecar): Map<String, String>? {
        return when (sidecar.store) {
            Store.GOG -> {
                val token = GogAuth.validToken(context) ?: return null
                GogDownloadManager.fetchDependencies(sidecar.id, sidecar.installVersion, token)
                    ?.let { mapOf(WinCompSources.GOG_DEPENDENCIES to it.joinToString(",")) }
            }
            Store.EPIC -> {
                val token = EpicCredentialStore.getValidAccessToken(context) ?: return null
                val namespace = sidecar.extra["namespace"].orEmpty()
                val catalogItemId = sidecar.extra["catalogItemId"].orEmpty()
                if (namespace.isEmpty() || catalogItemId.isEmpty()) return null
                val json = EpicApiClient.getManifestApiJson(token, namespace, catalogItemId, sidecar.extra["appName"] ?: sidecar.id) ?: return null
                EpicDownloadManager.fetchPrereq(json)?.let { (name, path, command, args) ->
                    mapOf(WinCompSources.EPIC_PREREQ_NAME to name, WinCompSources.EPIC_PREREQ_PATH to path, WinCompSources.EPIC_LAUNCH_COMMAND to command,
                        WinCompSources.EPIC_PREREQ_ARGS to args)
                }
            }
            else -> null
        }
    }
}
