package com.droiddeck.launcher.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The options manifest of the installed Blobferry (`usr/share/blobferry/capabilities.json`, `options_schema` 1):
 * every environment key the bridge layers read, with type, default, category, status and the games it is proven for.
 * The game environment editor builds its "Blobferry options" section from this (docs/agents/rtx-switch-1107.md).
 * Parsing is tolerant: a missing, broken or older file gives null and the editor falls back to the built-in few.
 */
object BlobferryOptions {
    const val SCHEMA = 1

    enum class Kind { BOOL, INT, HEX, ENUM, STRING, LIST }
    enum class Control { SWITCH, DROPDOWN, TEXT }
    enum class Status { STABLE, EXPERIMENTAL, DEBUG_ONLY }

    data class ProvenGame(val game: String, val value: String)

    data class Option(
        val name: String,
        val kind: Kind,
        /** Manifest default as env text ("1"/"0" for bool), null when the option has none. */
        val default: String?,
        val choices: List<String> = emptyList(),
        val min: Long? = null,
        val max: Long? = null,
        /** `values.item` / `values.pattern`: a hint for the free-text kinds. */
        val hint: String? = null,
        val category: String = "other",
        val status: Status = Status.STABLE,
        val description: String = "",
        val games: List<ProvenGame> = emptyList(),
        val minLayer: String = "any",
    ) {
        val control: Control get() = controlFor(kind)

        /** The value this game is proven with, or null when the manifest does not list the game. */
        fun provenValue(gameName: String?): String? =
            if (gameName.isNullOrBlank()) null else games.firstOrNull { it.game.equals(gameName.trim(), ignoreCase = true) }?.value
    }

    fun controlFor(kind: Kind): Control = when (kind) {
        Kind.BOOL -> Control.SWITCH
        Kind.ENUM -> Control.DROPDOWN
        else -> Control.TEXT
    }

    /** The options of the manifest text, or null when it is not a schema-1 manifest with at least one usable option. */
    fun parse(text: String): List<Option>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("options_schema", 0) != SCHEMA) return null
        val array = root.optJSONArray("options") ?: return null
        val parsed = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::option) }
        return parsed.distinctBy { it.name }.ifEmpty { null }
    }

    private fun option(json: JSONObject): Option? {
        val name = json.optString("name")
        if (!GameEnvironment.validName(name)) return null
        val kind = when (json.optString("type")) {
            "bool" -> Kind.BOOL
            "int" -> Kind.INT
            "hex" -> Kind.HEX
            "enum" -> Kind.ENUM
            "string" -> Kind.STRING
            "list" -> Kind.LIST
            else -> return null
        }
        val values = json.opt("values")
        val choices = (values as? JSONArray)?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        if (kind == Kind.ENUM && choices.isEmpty()) return null
        val range = values as? JSONObject
        val defaultValue = json.opt("default")?.takeIf { it != JSONObject.NULL }
        val default = when {
            defaultValue == null -> null
            kind == Kind.BOOL -> if (defaultValue == true || defaultValue.toString() == "1") "1" else "0"
            else -> defaultValue.toString()
        }
        val games = json.optJSONArray("games")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { g -> g.optString("game").takeIf(String::isNotBlank)?.let { ProvenGame(it, g.optString("value")) } }
            }
        }.orEmpty()
        return Option(
            name = name, kind = kind, default = default, choices = choices,
            min = range?.takeIf { it.has("min") && !it.isNull("min") }?.optLong("min"),
            max = range?.takeIf { it.has("max") && !it.isNull("max") }?.optLong("max"),
            hint = range?.let { it.optString("item").ifEmpty { it.optString("pattern") } }?.ifEmpty { null },
            category = json.optString("category").ifEmpty { "other" },
            status = when (json.optString("status")) {
                "experimental" -> Status.EXPERIMENTAL
                "debug-only" -> Status.DEBUG_ONLY
                else -> Status.STABLE
            },
            description = json.optString("description"),
            games = games,
            minLayer = json.optString("min_layer").ifEmpty { "any" },
        )
    }

    fun load(rootfs: java.io.File): List<Option>? =
        runCatching { parse(java.io.File(rootfs, "usr/share/blobferry/capabilities.json").readText()) }.getOrNull()

    /** The options to list, by category (alphabetical) then name; debug-only ones only when [showDebug]. */
    fun grouped(options: List<Option>, showDebug: Boolean): List<Pair<String, List<Option>>> =
        options.filter { showDebug || it.status != Status.DEBUG_ONLY }
            .groupBy { it.category }.toSortedMap()
            .map { (category, list) -> category to list.sortedBy { it.name } }

    /** Whether [text] is a value this option accepts (the editor enables Set only then). */
    fun valid(option: Option, text: String): Boolean {
        if (text.isEmpty() || text != text.trim() || !GameEnvironment.validValue(text)) return false
        return when (option.kind) {
            Kind.BOOL -> text == "0" || text == "1"
            Kind.ENUM -> text in option.choices
            Kind.INT -> text.toLongOrNull()?.let { v -> (option.min == null || v >= option.min) && (option.max == null || v <= option.max) } == true
            Kind.HEX -> Regex("(0[xX])?[0-9a-fA-F]{1,16}").matches(text)
            Kind.STRING -> text.none { it.isISOControl() }
            Kind.LIST -> text.split(',').all { it.isNotBlank() && it == it.trim() } && text.none { it.isISOControl() }
        }
    }

    fun isOn(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)

    /** The five Mali switches built into the app, used when the installed package has no (usable) manifest. */
    fun fallback(describe: (Int) -> String): List<Option> =
        GameEnvironmentOptions.all.filter { it.name.startsWith("MALI_") && it.type == GameEnvironmentOptions.Type.TOGGLE }
            .map { Option(it.name, Kind.BOOL, it.value, category = "compat", description = if (it.detail != 0) describe(it.detail) else "") }
}
