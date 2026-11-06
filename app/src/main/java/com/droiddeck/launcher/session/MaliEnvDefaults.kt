package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The online env updater: per-game env defaults for games on the Mali system driver, as a manifest
 * (`droiddeck-mali-env-defaults/1`) that the APK bundles (`usr/share/droiddeck-mali/env-defaults.json`) and that
 * "Check for updates" / `droiddeckctl env-defaults install` can replace with a newer one. The guest's
 * droiddeck-mali-defaults reads it at every game start; the rules here and there are the same (shared test cases in
 * tools/tests/data/env-defaults-cases.json).
 *
 * Games are keyed by `steam:<app id>` or `<store>:<store id>`, never by a per-install Steam shortcut id. Only `env`
 * applies; `pending` entries are documentation. Names that load code or move a game's files are refused, and a path
 * value must stay under /usr/share/droiddeck-mali. The user's own keys (the game's entry, the user's shared settings,
 * published as `userShared`) always win; that merge is the guest's (droiddeck-mali-defaults.recipe).
 *
 * Store `files/env-defaults/`: `active.json` + `active.meta.json` (source, sha256), `previous.json` (+ meta) for one
 * roll back. The newer of the installed and the bundled manifest is the one in force; publish() writes the installed one
 * to the guest (`/root/.config/droiddeck/mali-env-defaults.json`) only while it is the newer one. No session gate: the
 * file is read at each game start. [app, Claude Opus 5.5] 2026-11-06
 */
object MaliEnvDefaults {
    private const val TAG = "MaliEnvDefaults"
    const val SCHEMA = "droiddeck-mali-env-defaults/1"
    const val STORE = "env-defaults"
    const val BUNDLED_ASSET = "linuxfs/usr/share/droiddeck-mali/env-defaults.json"
    const val GUEST_FILE = "root/.config/droiddeck/mali-env-defaults.json"
    const val MAX_BYTES = 1 shl 20
    const val SOURCE_BUNDLED = "bundled"
    const val SOURCE_BUILT_IN = "built-in"
    private const val MAX_GAMES = 256
    private const val MAX_KEYS = 64
    private const val MAX_VALUE = 1024

    private val VERSION = Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\.[0-9]{1,3}")
    private val GAME_KEY = Regex("steam:[1-9][0-9]{0,9}|[a-z]{2,16}:[A-Za-z0-9._-]{1,128}")
    private val ENV_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val ENV_PREFIXES = listOf("MALI_COMPAT_", "MALI_WSI_", "DXVK_", "VKD3D_", "PROTON_", "WINE", "FEX_", "BL_")
    private val ENV_NAMES = setOf("MALI_DEBUG_CONFIG")
    val ENV_DENIED = setOf(
        "WINEPREFIX", "WINESERVER", "WINELOADER", "WINEDLLPATH", "WINEARCH", "PROTON_LOG_DIR", "PROTONPATH",
        "FEX_ROOTFS", "FEX_THUNKHOSTLIBS", "FEX_THUNKGUESTLIBS", "FEX_THUNKCONFIG", "FEX_APP_CONFIG", "FEX_APP_CONFIG_LOCATION",
        "FEX_APP_DATA_LOCATION", "FEX_SERVERSOCKETPATH", "DXVK_CONFIG_FILE", "DXVK_LOG_PATH", "VKD3D_LOG_FILE",
        "BL_MALI_GAME_DEFAULTS", "BL_DEBUG_DIR",
    )
    private val PATH_ROOTS = listOf("/usr/share/droiddeck-mali/", "Z:/usr/share/droiddeck-mali/")
    private val PREFIX_ACTIONS = setOf("wpad-off")
    private val HEX64 = Regex("(?i)[0-9a-f]{64}")
    private val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\].*")

    class Game(val title: String, val env: Map<String, String>, val pending: Int)

    class Manifest(val version: String, val games: Map<String, Game>, val sha256: String, internal val bytes: ByteArray) {
        val pending get() = games.values.sumOf { it.pending }
    }

    class Layout(val store: File, val rootfs: File)

    interface Host {
        /** The manifest the APK bundles, null without one. */
        fun bundled(): ByteArray?
    }

    class Status(
        /** What the guest uses: downloaded / file / adb (an installed one), bundled, or built-in (no manifest at all). */
        val source: String, val version: String, val games: Int, val sha256: String,
        val installedVersion: String?, val bundledVersion: String?, val previous: String?,
        /** An installed manifest that is older than the bundled one (an app update brought newer defaults). */
        val bundledNewer: Boolean,
        val problem: String? = null,
    ) {
        fun json(): JSONObject = JSONObject().put("source", source).put("version", version).put("games", games).put("sha256", sha256)
            .put("installedVersion", installedVersion ?: JSONObject.NULL).put("bundledVersion", bundledVersion ?: JSONObject.NULL)
            .put("hasPrevious", previous != null).put("previous", previous ?: JSONObject.NULL).put("bundledNewer", bundledNewer)
            .put("problem", problem ?: JSONObject.NULL)

        /** One line for the report and the card: "2026.11.06.1 (bundled), 11 games". */
        val label: String get() = if (source == SOURCE_BUILT_IN) "built-in recipes" else "$version ($source), $games games"
    }

    // ---- validation (the same rules as droiddeck-mali-defaults.parse_manifest) ----

    fun checkEnv(name: String, value: Any?): String? {
        if (!ENV_NAME.matches(name)) return "bad name '$name'"
        if (name in ENV_DENIED || !(name in ENV_NAMES || ENV_PREFIXES.any { name.startsWith(it) })) return "$name is not allowed in env defaults"
        if (value !is String || value.length > MAX_VALUE || value.any { it.code < 0x20 || it.code == 0x7f }) return "$name: bad value"
        if (value.startsWith("/") || DRIVE_PATH.matches(value)) {
            if ('\\' in value || "/../" in "$value/" || PATH_ROOTS.none { value.startsWith(it) }) return "$name: a path outside /usr/share/droiddeck-mali"
        }
        return null
    }

    fun parse(bytes: ByteArray): Manifest {
        fun bad(why: String): Nothing = throw BlobferryException("BAD_MANIFEST", why)
        if (bytes.size > MAX_BYTES) bad("larger than ${MAX_BYTES shr 10} KB")
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { bad("not UTF-8") }
        val json = runCatching { JSONObject(text) }.getOrElse { bad("not JSON") }
        if (json.opt("schema") != SCHEMA) bad("not a $SCHEMA manifest")
        val version = json.opt("version") as? String ?: bad("no version")
        if (!VERSION.matches(version)) bad("bad version '$version'")
        val games = json.opt("games") as? JSONObject ?: bad("games must be an object")
        if (games.length() > MAX_GAMES) bad("more than $MAX_GAMES games")
        val out = LinkedHashMap<String, Game>()
        for (key in games.keys()) {
            if (!GAME_KEY.matches(key)) bad("bad game key '$key'")
            if (key.startsWith("steam:") && key.removePrefix("steam:").toLong() >= (1L shl 31)) bad("$key is a Steam shortcut id, not a store game's stable id")
            val game = games.opt(key) as? JSONObject ?: bad("$key: not an object")
            val title = game.opt("title") as? String
            if (title.isNullOrEmpty() || title.length > 128) bad("$key: bad title")
            val env = game.opt("env") as? JSONObject ?: bad("$key: env must be an object")
            if (env.length() > MAX_KEYS) bad("$key: more than $MAX_KEYS keys")
            val values = LinkedHashMap<String, String>()
            for (name in env.keys()) {
                checkEnv(name, env.opt(name))?.let { bad("$key: $it") }
                values[name] = env.getString(name)
            }
            if (game.has("note") && !game.isNull("note") && (game.opt("note") !is String || game.getString("note").length > 512)) bad("$key: bad note")
            val prefix = if (game.has("prefix")) game.opt("prefix") as? JSONArray ?: bad("$key: prefix must be a list") else JSONArray()
            for (i in 0 until prefix.length()) if (prefix.opt(i) !in PREFIX_ACTIONS) bad("$key: prefix actions are ${PREFIX_ACTIONS.joinToString()}")
            if ((game.opt("evidence") as? String).isNullOrEmpty()) bad("$key: evidence missing")
            val pending = if (game.has("pending")) game.opt("pending") as? JSONArray ?: bad("$key: bad pending list") else JSONArray()
            for (i in 0 until pending.length()) {
                val entry = (pending.opt(i) as? JSONObject)?.opt("env") as? JSONObject ?: bad("$key: bad pending entry")
                for (name in entry.keys()) checkEnv(name, entry.opt(name))?.let { bad("$key (pending): $it") }
            }
            out[key] = Game(title, values, pending.length())
        }
        return Manifest(version, out, sha256(bytes), bytes)
    }

    /** -1 / 0 / 1 for two valid versions (YYYY.MM.DD.N, numerically). */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toInt() }
        val y = b.split('.').map { it.toInt() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    // ---- context entry points ----

    fun layout(context: Context) = Layout(File(context.filesDir, STORE), LinuxRuntime.rootDir(context))

    fun host(context: Context): Host = object : Host {
        override fun bundled(): ByteArray? = runCatching { context.assets.open(BUNDLED_ASSET).use { it.readBytes() } }.getOrNull()
    }

    fun prepare(input: InputStream, expectedSha256: String? = null): Manifest {
        if (expectedSha256 != null && !HEX64.matches(expectedSha256)) throw BlobferryException("BAD_CHECKSUM", "the expected sha256 is not 64 hex digits")
        val bytes = input.use { readCapped(it) }
        val manifest = parse(bytes)
        if (expectedSha256 != null && !expectedSha256.equals(manifest.sha256, ignoreCase = true)) {
            throw BlobferryException("BAD_CHECKSUM", "sha256 ${manifest.sha256} does not match the expected $expectedSha256")
        }
        return manifest
    }

    fun install(context: Context, manifest: Manifest, source: String) = install(layout(context), host(context), manifest, source)
    fun rollback(context: Context) = rollback(layout(context), host(context))
    fun reset(context: Context) = reset(layout(context), host(context))
    fun status(context: Context) = status(layout(context), host(context))
    fun publish(context: Context) = runCatching { publish(layout(context), host(context)) }.onFailure { Log.w(TAG, "publish", it) }

    // ---- switch ----

    @Synchronized
    fun install(layout: Layout, host: Host, manifest: Manifest, source: String): Status {
        parse(manifest.bytes) // re-checked: what is written is exactly what was verified
        layout.store.mkdirs()
        val active = File(layout.store, "active.json")
        if (active.isFile) {
            copyAtomic(active, File(layout.store, "previous.json"))
            File(layout.store, "active.meta.json").takeIf { it.isFile }?.let { copyAtomic(it, File(layout.store, "previous.meta.json")) }
                ?: File(layout.store, "previous.meta.json").delete()
        }
        writeAtomic(File(layout.store, "active.meta.json"), JSONObject().put("source", source).put("sha256", manifest.sha256)
            .put("version", manifest.version).put("installedAt", System.currentTimeMillis()).toString(2).toByteArray())
        writeAtomic(active, manifest.bytes)
        Log.i(TAG, "env defaults ${manifest.version} installed from $source (${manifest.games.size} games)")
        publish(layout, host)
        return status(layout, host)
    }

    /** Swaps the installed manifest and the one before it. */
    @Synchronized
    fun rollback(layout: Layout, host: Host): Status {
        val active = File(layout.store, "active.json")
        val previous = File(layout.store, "previous.json")
        if (!previous.isFile) throw BlobferryException("NO_PREVIOUS", "there are no previous env defaults")
        parse(previous.readBytes())
        val swap = File(layout.store, "swap.json")
        val swapMeta = File(layout.store, "swap.meta.json")
        copyAtomic(previous, swap)
        File(layout.store, "previous.meta.json").let { if (it.isFile) copyAtomic(it, swapMeta) else swapMeta.delete() }
        if (active.isFile) {
            copyAtomic(active, previous)
            File(layout.store, "active.meta.json").let { if (it.isFile) copyAtomic(it, File(layout.store, "previous.meta.json")) }
        } else previous.delete()
        if (swapMeta.isFile) swapMeta.renameTo(File(layout.store, "active.meta.json")) else File(layout.store, "active.meta.json").delete()
        if (!swap.renameTo(active)) throw IOException("cannot activate the previous env defaults")
        publish(layout, host)
        return status(layout, host)
    }

    /** Back to the manifest the APK bundles: the installed ones are removed. */
    @Synchronized
    fun reset(layout: Layout, host: Host): Status {
        for (name in listOf("active.json", "active.meta.json", "previous.json", "previous.meta.json")) File(layout.store, name).delete()
        publish(layout, host)
        return status(layout, host)
    }

    /** The guest copy := the installed manifest while it is valid and newer than the bundled one, else none. */
    @Synchronized
    fun publish(layout: Layout, host: Host) {
        val guest = File(layout.rootfs, GUEST_FILE)
        val installed = installed(layout)
        val bundled = host.bundled()?.let { runCatching { parse(it) }.getOrNull() }
        if (installed != null && (bundled == null || compare(installed.version, bundled.version) > 0)) {
            if (!guest.isFile || !guest.readBytes().contentEquals(installed.bytes)) writeAtomic(guest, installed.bytes)
        } else if (guest.exists()) guest.delete()
    }

    private fun installed(layout: Layout): Manifest? = File(layout.store, "active.json").takeIf { it.isFile }
        ?.let { runCatching { parse(it.readBytes()) }.onFailure { e -> Log.w(TAG, "installed env defaults refused", e) }.getOrNull() }

    fun status(layout: Layout, host: Host): Status {
        var problem: String? = null
        val installed = File(layout.store, "active.json").takeIf { it.isFile }
            ?.let { runCatching { parse(it.readBytes()) }.onFailure { e -> problem = "installed env defaults refused: ${e.message}" }.getOrNull() }
        val bundled = host.bundled()?.let { runCatching { parse(it) }.onFailure { e -> problem = "bundled env defaults refused: ${e.message}" }.getOrNull() }
        val meta = runCatching { JSONObject(File(layout.store, "active.meta.json").readText()) }.getOrNull()
        val previous = File(layout.store, "previous.json").takeIf { it.isFile }?.let { runCatching { parse(it.readBytes()).version }.getOrNull() }
        val useInstalled = installed != null && (bundled == null || compare(installed.version, bundled.version) > 0)
        val inForce = if (useInstalled) installed else bundled
        return Status(
            source = when {
                useInstalled -> meta?.optString("source").orEmpty().ifEmpty { "file" }
                bundled != null -> SOURCE_BUNDLED
                else -> SOURCE_BUILT_IN
            },
            version = inForce?.version.orEmpty(), games = inForce?.games?.size ?: 0, sha256 = inForce?.sha256.orEmpty(),
            installedVersion = installed?.version, bundledVersion = bundled?.version, previous = previous,
            bundledNewer = installed != null && !useInstalled, problem = problem,
        )
    }

    // ---- helpers ----

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 14)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw BlobferryException("TOO_LARGE", "the manifest is larger than ${MAX_BYTES shr 10} KB")
        }
        return out.toByteArray()
    }

    private fun copyAtomic(src: File, target: File) = writeAtomic(target, src.readBytes())

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".staged")
        try {
            staged.writeBytes(bytes)
            if (!staged.renameTo(target)) throw IOException("cannot write $target")
        } finally {
            staged.delete()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
