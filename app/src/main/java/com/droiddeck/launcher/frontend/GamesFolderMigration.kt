package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import java.io.File
import java.util.zip.CRC32

object GamesFolderMigration {
    private const val TAG = "GamesFolderMigration"

    private const val ROOTS_RETRY_MS = 60_000L

    @Volatile
    internal var rootsTried = 0L

    @Synchronized
    fun run(context: Context) {
        migrateLegacyDirs(context)
        rebindGameRoots(context)
        pruneNestedBinds(context)
    }

    private fun migrateLegacyDirs(context: Context) {
        val dirs = SessionPrefs.addedGamesDirs(context)
        if (dirs.isEmpty()) return
        val roots = legacyRoots(SessionPrefs.gameFolderBinds(context).map { it.first } + dirs)
            .filter { root -> root.host.path in dirs }
        SessionPrefs.setGameFolderBinds(context, SessionPrefs.gameFolderBinds(context) + roots.map { it.host.path to it.guest })
        val removed = SessionPrefs.removedAddedGames(context).toSet()
        val entries = AddedExes.list(context).map { it.folder }.toMutableSet()
        val storeRoots = StoreInstallRoot.roots(context).map { it.absolutePath }
        for (root in roots) {
            var n = 0
            val steamInstalls = AddedGames.steamInstallDirs(root.host)
            for (top in root.host.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()) {
                if (AddedGames.isSteamLibraryFolder(top.name) || top.name.lowercase() in steamInstalls) continue
                if (top.absolutePath in storeRoots || StoreGameSidecar.read(top) != null) continue
                if (top.path in entries) continue
                val topPick = SessionPrefs.addedGameExe(context, top.path).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                val groups = AddedGames.groupGames(top)
                val played = topPick ?: groups?.let { AddedGames.rank(top).exes.firstOrNull() }
                val owner = played?.let { exe -> top.listFiles { f -> f.isDirectory }?.firstOrNull { exe.path.startsWith(it.path + File.separator) } }
                val folders = when {
                    groups == null -> listOf(top)
                    topPick == null || owner in groups -> groups
                    owner == null || owner.name.startsWith(".") || AddedGames.isSteamLibraryFolder(owner.name) -> listOf(top)
                    else -> (groups + owner).sortedBy { it.name.lowercase() }
                }
                for (folder in folders) {
                    if (folder.absolutePath in storeRoots || StoreGameSidecar.read(folder) != null) continue
                    if (folder.path in entries) continue
                    val picked = SessionPrefs.addedGameExe(context, folder.path).takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }
                        ?: topPick?.takeIf { it.path.startsWith(folder.path + File.separator) }
                    val ranked = AddedGames.rank(folder).exes.firstOrNull()
                    val exe = picked ?: played?.takeIf { folder == owner } ?: ranked ?: continue
                    AddedExes.put(context, AddedExes.Entry(folder.path, exe.path, picked = picked != null || exe != ranked))
                    entries.add(folder.path)
                    if (folder != top && folder == owner) SessionPrefs.inheritAddedGame(context, top.path, folder.path)
                    if (folder != top && top.path in removed) SessionPrefs.setAddedGameRemoved(context, folder.path, true)
                    else if (folder.path !in removed) n++
                }
            }
            Log.i(TAG, "games: migrated ${root.host} → $n games")
        }
        SessionPrefs.setAddedGamesDirs(context, emptyList())
        SessionPrefs.setGamesFoldersMigrated(context)
    }

    @Volatile
    internal var lastVolumes: Set<String>? = null

    /** The binds that existed before the update; the migration never looks at a bind made later. */
    internal fun rootsSnapshot(context: Context): List<String> =
        SessionPrefs.addedGameRootsPending(context)
            ?: SessionPrefs.gameFolderBinds(context).map { it.first }.also { SessionPrefs.setAddedGameRootsPending(context, it) }

    internal fun rebindGameRoots(context: Context) {
        if (SessionPrefs.addedGameRootsMigrated(context)) return
        val todo = rootsSnapshot(context).toSet()
        val now = System.currentTimeMillis()
        if (todo.isNotEmpty() && now - rootsTried < ROOTS_RETRY_MS) return
        rootsTried = now
        val volumes = if (todo.isEmpty()) emptyList() else AddedGames.volumeRoots(context).filter { it.list() != null }
        val mounted = volumes.map { it.absolutePath }.toSet()
        if (todo.isNotEmpty() && mounted == lastVolumes) return
        lastVolumes = mounted
        val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
        val exes by lazy { AddedExes.list(context).map { it.exe } }
        val dead = HashSet<String>()
        val waiting = ArrayList<String>()
        for ((host, guest) in SessionPrefs.gameFolderBinds(context)) {
            if (host !in todo) continue
            val dir = File(host)
            val volume = volumes.firstOrNull { AddedGames.inside(dir.absolutePath, it.absolutePath) }
            if (!dir.isDirectory) {
                if (volume != null) dead.add(host) else if (!AddedGames.inside(dir.absolutePath, storage)) waiting.add(host)
                continue
            }
            if (volume == null || GameStorage.hasInstalledGames(dir) || exes.none { AddedGames.inside(it, dir.absolutePath) }) continue
            if (AddedGames.mappedGuestPath(context, dir) != null) continue
            val probe = File(dir, "game.exe")
            val top = AddedGames.topFolder(volume, probe) ?: continue
            val target = AddedGames.bindTarget(context, top, probe)
            if (target.absolutePath == dir.absolutePath || !AddedGames.inside(dir.absolutePath, target.absolutePath)) continue
            if (SessionPrefs.gameFolderBinds(context).any { it.first != host && AddedGames.inside(host, it.first) }) continue
            AddedGames.bindFolder(context, target)
            Log.i(TAG, "games: $host ($guest) is not its game root; bound ${target.path}")
        }
        if (dead.isNotEmpty()) {
            SessionPrefs.setGameFolderBinds(context, SessionPrefs.gameFolderBinds(context).filterNot { it.first in dead })
            Log.i(TAG, "games: dropped binds of moved or deleted folders: ${dead.joinToString()}")
        }
        SessionPrefs.setAddedGameRootsPending(context, waiting)
        if (waiting.isEmpty()) SessionPrefs.setAddedGameRootsMigrated(context, true)
        else Log.i(TAG, "games: ${waiting.size} bind(s) wait for their volume: ${waiting.joinToString()}")
    }

    /**
     * Whether a bind nested under another bind must stay. It stays when it holds a Steam library (any
     * `steamapps` folder counts, because a filtered storage view may hide the `.acf` files) or when its
     * folder cannot be listed (volume unplugged or still mounting, permission revoked: a missing nested
     * bind is harmless). The folder is listed LAST: a stick pulled after the library probe makes that
     * list fail, so the bind is kept instead of dropped.
     */
    @Volatile
    internal var keepNestedBind: (File) -> Boolean = { dir -> File(dir, "steamapps").isDirectory || GameStorage.hasInstalledGames(dir) || dir.list() == null }

    /**
     * Nested binds [keepNestedBind] chose to keep in this process. Only "keep" is remembered: a stale
     * keep never loses data, while a drop is always decided from the disk. [forgetNestedKeeps] clears it
     * on every media broadcast and when the launcher comes back to the front, so a remounted or changed
     * volume is read again.
     */
    private val keptNested: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun forgetNestedKeeps() = keptNested.clear()

    internal fun pruneNestedBinds(context: Context) {
        val binds = SessionPrefs.gameFolderBinds(context)
        if (binds.size < 2) return
        val candidates = binds.mapNotNull { bind ->
            binds.filter { it.first != bind.first && bind.first.startsWith(it.first.trimEnd('/') + "/") }
                .minByOrNull { it.first.length }?.let { bind to it }
        }
        if (candidates.isEmpty()) return
        val nested = candidates.filterNot { (inner, _) ->
            inner.first in keptNested || keepNestedBind(File(inner.first)).also { if (it) keptNested.add(inner.first) }
        }
        if (nested.isEmpty()) return
        for ((inner, outer) in nested) {
            val moved = outer.second + "/" + inner.first.removePrefix(outer.first.trimEnd('/') + "/")
            SessionPrefs.moveAddedGameStartIns(context, inner.second, moved)
            Log.i(TAG, "games: ${inner.first} lies inside ${outer.first}; dropped its bind ${inner.second}")
        }
        val dropped = nested.map { it.first }.toSet()
        SessionPrefs.setGameFolderBinds(context, binds.filterNot { it in dropped })
    }

    internal fun legacyRoots(paths: List<String>): List<AddedGames.Root> {
        val hosts = paths.distinct().map { File(it) }
        val names = hosts.groupingBy { it.name.lowercase() }.eachCount()
        return hosts.map { host ->
            val name = host.name.ifEmpty { "games" }
            val guestName = if ((names[name.lowercase()] ?: 0) > 1) name + "-" + "%08x".format(CRC32().apply { update(host.path.toByteArray()) }.value).take(4) else name
            AddedGames.Root(host, "${AddedGames.GUEST_DIR}/$guestName")
        }
    }
}
