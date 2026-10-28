package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File

object GameEnvironmentStore {
    private const val SETTINGS = "game-environment.json"
    private const val GUEST_FILE = "root/.config/droiddeck/game-environment.json"
    const val DXVK_CONFIG = "dxvkConfig"
    const val DXVK_CONFIG_GAMES = "dxvkConfigGames"

    @Synchronized
    fun read(context: Context): GameEnvironment.Config {
        val file = AtomicFile(File(context.filesDir, SETTINGS))
        val bytes = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return GameEnvironment.Config() }
        return decode(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    fun decode(json: JSONObject): GameEnvironment.Config {
        require(json.getInt("version") == 1)
        fun entries(value: JSONObject?): Map<String, String?> = buildMap {
            value?.keys()?.forEach { name ->
                require(GameEnvironment.validName(name))
                val entry = value.get(name)
                require(entry == JSONObject.NULL || entry is String && GameEnvironment.validValue(entry))
                put(name, if (entry == JSONObject.NULL) null else entry as String)
            }
        }
        val games = json.optJSONObject("games")
        val templates = json.optJSONObject("templates")
        return GameEnvironment.Config(entries(json.optJSONObject("shared")), buildMap {
            games?.keys()?.forEach { id ->
                require(id.isNotEmpty() && GameEnvironment.validScope(id))
                put(id, entries(games.getJSONObject(id)))
            }
        }, buildMap {
            templates?.keys()?.forEach { name ->
                require(GameEnvironment.validTemplateName(name))
                put(name, entries(templates.getJSONObject(name)))
            }
        })
    }

    fun encode(config: GameEnvironment.Config): JSONObject {
        fun entries(values: Map<String, String?>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        return JSONObject().put("version", 1).put("shared", entries(config.shared)).put("games", JSONObject().apply {
            config.games.forEach { (id, values) -> put(id, entries(values)) }
        }).apply {
            if (config.templates.isNotEmpty()) put("templates", JSONObject().apply {
                config.templates.forEach { (name, values) -> put(name, entries(values)) }
            })
        }
    }

    @Synchronized
    fun save(context: Context, config: GameEnvironment.Config) {
        val json = encode(config)
        decode(json)
        write(File(context.filesDir, SETTINGS), json.toString())
        publish(context, config)
    }

    /** The game's Vulkan driver: "bridge" (Mali system driver), "session" (PanVK) or null (the runtime driver choice). */
    @Synchronized
    fun setGameDriver(context: Context, scope: String, driver: String?): GameEnvironment.Config {
        require(scope.isNotEmpty() && GameEnvironment.validScope(scope))
        val config = read(context)
        val entries = config.entries(scope).toMutableMap()
        if (driver == null) entries.remove(GameEnvironment.GAME_DRIVER) else entries[GameEnvironment.GAME_DRIVER] = driver
        val next = config.withEntries(scope, entries)
        save(context, next)
        return next
    }

    @Synchronized
    fun setGameFexPreset(context: Context, scope: String, preset: String?): GameEnvironment.Config {
        val config = GameEnvironment.withGameFexPreset(read(context), scope, preset)
        save(context, config)
        return config
    }

    @Synchronized
    fun setGameNoSteam(context: Context, scope: String, on: Boolean): GameEnvironment.Config {
        val config = GameEnvironment.withGameNoSteam(read(context), scope, on)
        save(context, config)
        return config
    }

    @Synchronized
    fun publish(context: Context, config: GameEnvironment.Config = read(context)) {
        val resolved = config.copy(shared = GameEnvironment.defaults(SessionPrefs.fexPreset(context), SessionPrefs.forceSsbs(context)) + config.shared)
        val json = encode(resolved)
        val dxvk = TextureFiltering.dxvkOptions(
            SessionPrefs.textureAnisotropy(context), SessionPrefs.textureLodBias(context),
            TextureFiltering.autoLodBias(SessionState.upscaleRatio),
        )
        if (dxvk.isNotEmpty()) json.put(DXVK_CONFIG, dxvk)
        val autoBias = TextureFiltering.autoLodBias(SessionState.upscaleRatio)
        val generalAnisotropy = SessionPrefs.textureAnisotropy(context)
        val generalLodBias = SessionPrefs.textureLodBias(context)
        val gameDxvk = JSONObject()
        SessionPrefs.gameTextureFiltering(context).forEach { (scope, choice) ->
            gameDxvk.put(
                scope,
                TextureFiltering.dxvkOptions(
                    choice.anisotropy ?: generalAnisotropy,
                    choice.lodBias ?: generalLodBias,
                    autoBias,
                ),
            )
        }
        if (gameDxvk.length() > 0) json.put(DXVK_CONFIG_GAMES, gameDxvk)
        write(File(LinuxRuntime.rootDir(context), GUEST_FILE), json.toString())
    }

    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
