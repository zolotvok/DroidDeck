package com.droiddeck.launcher.gpu

import java.io.File

/**
 * Names of the phone's Android Vulkan HAL for the Mali bridge ICD. Android's loader opens vulkan.<ro.hardware.vulkan>.so,
 * then vulkan.<ro.board.platform>.so, in the vendor/odm hw dirs; the ICD (Blobferry src/icd/mali_bridge_icd.c hal_find)
 * tries the names from [ENV] in this order, then vulkan.mali.so, each in /vendor/lib64/hw then /odm/lib64/hw, then any
 * vulkan.?*.so there (sorted), and logs the file it took ("mali-bridge: vendor HAL: ...").
 */
object VendorVulkanHal {
    const val ENV = "MALI_BRIDGE_HAL_NAMES"
    val PROPERTIES = listOf("ro.hardware.vulkan", "ro.board.platform", "ro.hardware")
    val DIRS = listOf("/vendor/lib64/hw", "/odm/lib64/hw")
    private val NAME = Regex("[A-Za-z0-9._-]{1,63}")
    private val FILE = Regex("vulkan\\..+\\.so")

    /** "ro.hardware.vulkan=mali,ro.board.platform=mt6993,..."; properties that are empty or not a plain name are left out. */
    internal fun names(prop: (String) -> String): String =
        PROPERTIES.mapNotNull { key -> prop(key).trim().takeIf { NAME.matches(it) }?.let { "$key=$it" } }.joinToString(",")

    fun names(): String = names(GpuInfo::systemProperty)

    /** For reports: each property's raw value ("unset" when empty). */
    fun properties(prop: (String) -> String = GpuInfo::systemProperty): List<Pair<String, String>> =
        PROPERTIES.map { it to prop(it).trim().ifEmpty { "unset" } }

    private fun defaultList(dir: String): List<String>? = File(dir).list()?.toList()

    /** For reports: the vulkan.*.so file names per hw dir, e.g. "vendor: vulkan.mali.so; odm: none". */
    fun files(list: (String) -> List<String>? = ::defaultList): String = DIRS.joinToString("; ") { dir ->
        val names = runCatching { list(dir) }.getOrNull()
        dir.split('/')[1] + ": " + when {
            names == null -> "not readable"
            else -> names.filter { FILE.matches(it) }.sorted().joinToString(", ").ifEmpty { "none" }
        }
    }

    /**
     * For reports: the file the ICD would try first that exists, by the ICD's order (without its dlopen/open checks),
     * as "path (via key=name)", or null. [exists] and [list] are injectable for tests.
     */
    fun resolve(
        prop: (String) -> String = GpuInfo::systemProperty,
        exists: (String) -> Boolean = { File(it).isFile },
        list: (String) -> List<String>? = ::defaultList,
    ): String? {
        val named = names(prop).split(',').filter { it.isNotEmpty() }.map { it.substringAfter('=') to it }
            .distinctBy { it.first }.toMutableList()
        if (named.none { it.first == "mali" }) named += "mali" to "built-in default"
        for ((name, via) in named) for (dir in DIRS) {
            val path = "$dir/vulkan.$name.so"
            if (runCatching { exists(path) }.getOrDefault(false)) return "$path (via $via)"
        }
        for (dir in DIRS) {
            val first = runCatching { list(dir) }.getOrNull().orEmpty().filter { FILE.matches(it) }.sorted()
                .firstOrNull { runCatching { exists("$dir/$it") }.getOrDefault(false) }
            if (first != null) return "$dir/$first (via scan of $dir)"
        }
        return null
    }
}
