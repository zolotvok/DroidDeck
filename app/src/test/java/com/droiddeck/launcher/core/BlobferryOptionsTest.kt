package com.droiddeck.launcher.core

import com.droiddeck.launcher.core.BlobferryOptions.Control
import com.droiddeck.launcher.core.BlobferryOptions.Kind
import com.droiddeck.launcher.core.BlobferryOptions.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fixture is the stage copy of Blobferry's usr/share/blobferry/capabilities.json (options_schema 1, 78 keys). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BlobferryOptionsTest {
    private val text = javaClass.getResource("/blobferry/capabilities.json")!!.readText()
    private val options = BlobferryOptions.parse(text)!!
    private fun get(name: String) = options.first { it.name == name }

    @Test fun parsesEveryKeyOfTheManifest() {
        assertEquals(78, options.size)
        assertEquals(options.size, options.map { it.name }.toSet().size)
        assertTrue(options.all { GameEnvironment.validName(it.name) })
        assertEquals(setOf(Kind.BOOL, Kind.INT, Kind.HEX, Kind.ENUM, Kind.STRING, Kind.LIST), options.map { it.kind }.toSet())
        assertEquals(setOf(Status.STABLE, Status.EXPERIMENTAL, Status.DEBUG_ONLY), options.map { it.status }.toSet())
    }

    @Test fun eachTypeGetsTheRightControl() {
        for (o in options) assertEquals(
            o.name,
            when (o.kind) { Kind.BOOL -> Control.SWITCH; Kind.ENUM -> Control.DROPDOWN; else -> Control.TEXT },
            o.control,
        )
        assertEquals(Control.SWITCH, get("MALI_COMPAT_RT_PIPELINE").control)
        assertEquals(Control.DROPDOWN, get("MALI_COMPAT_DEPTH_EQUAL").control)
        assertEquals(listOf("always", "lequal", "gequal"), get("MALI_COMPAT_DEPTH_EQUAL").choices)
        assertEquals(Control.TEXT, get("MALI_COMPAT_ASYNC_PSO").control)
        assertEquals(Control.TEXT, get("MALI_COMPAT_SKIP_HASH").control)
        assertEquals(Control.TEXT, get("MALI_COMPAT_COVER_TRIM_GRID").control)
        assertEquals(Control.TEXT, get("MALI_COMPAT_COVER_TRIM_CS").control)
        assertTrue(options.filter { it.kind == Kind.ENUM }.all { it.choices.isNotEmpty() })
    }

    @Test fun boolDefaultsBecomeEnvText() {
        assertEquals("0", get("MALI_COMPAT_RT_PIPELINE").default)
        assertEquals("0", get("DISABLE_MALI_COMPAT").default)
        assertEquals("0", get("MALI_COMPAT_FAIL_LOG").default)
        assertNull(get("MALI_COMPAT_DEVICE_ID").default)
        assertEquals("1", BlobferryOptions.parse(one("bool", "true"))!!.single().default)
    }

    @Test fun provenForListsTheCurrentGameOnly() {
        val pso = get("MALI_COMPAT_ASYNC_PSO")
        assertEquals("50", pso.provenValue("Watch Dogs 2"))
        assertEquals("50", pso.provenValue("watch dogs 2"))
        assertNull(pso.provenValue("Control"))
        assertNull(pso.provenValue(null))
        assertTrue(options.any { it.games.isNotEmpty() })
    }

    @Test fun debugOnlyIsHiddenUnlessAsked() {
        val shown = BlobferryOptions.grouped(options, showDebug = false).flatMap { it.second }
        assertTrue(shown.none { it.status == Status.DEBUG_ONLY })
        assertEquals(options.count { it.status != Status.DEBUG_ONLY }, shown.size)
        val all = BlobferryOptions.grouped(options, showDebug = true)
        assertEquals(options.size, all.sumOf { it.second.size })
        assertEquals(all.map { it.first }, all.map { it.first }.sorted())
        assertTrue(all.all { (_, list) -> list.map { it.name } == list.map { it.name }.sorted() })
        assertTrue(shown.any { it.status == Status.EXPERIMENTAL })
    }

    @Test fun validationFollowsTheType() {
        val int = get("MALI_COMPAT_ASYNC_PSO")
        assertEquals(0L, int.min)
        assertTrue(BlobferryOptions.valid(int, "50"))
        assertFalse(BlobferryOptions.valid(int, "-1"))
        assertFalse(BlobferryOptions.valid(int, "5x"))
        assertFalse(BlobferryOptions.valid(int, ""))
        val ranged = options.first { it.kind == Kind.INT && it.max != null }
        assertFalse(BlobferryOptions.valid(ranged, (ranged.max!! + 1).toString()))
        assertTrue(BlobferryOptions.valid(ranged, ranged.max.toString()))
        val hex = get("MALI_COMPAT_SKIP_HASH")
        assertTrue(BlobferryOptions.valid(hex, "1a2b3c"))
        assertTrue(BlobferryOptions.valid(hex, "0x1A"))
        assertFalse(BlobferryOptions.valid(hex, "xyz"))
        val enum = get("MALI_COMPAT_DEPTH_EQUAL")
        assertTrue(BlobferryOptions.valid(enum, "lequal"))
        assertFalse(BlobferryOptions.valid(enum, "less"))
        val list = get("MALI_COMPAT_FP16_HIDE_PROCS")
        assertTrue(BlobferryOptions.valid(list, "a,b"))
        assertFalse(BlobferryOptions.valid(list, "a,,b"))
        assertFalse(BlobferryOptions.valid(list, " a"))
        assertTrue(BlobferryOptions.valid(get("MALI_COMPAT_DEVICE_NAME"), "Some GPU"))
        assertFalse(BlobferryOptions.valid(get("MALI_COMPAT_DEVICE_NAME"), "a\u0000b"))
    }

    @Test fun missingOrOldManifestsAreRejectedAndTheFallbackIsTheMaliSwitches() {
        assertNull(BlobferryOptions.parse(""))
        assertNull(BlobferryOptions.parse("not json"))
        assertNull(BlobferryOptions.parse("""{"rt_pipeline":"not_implemented","dxr":"not_implemented"}"""))
        assertNull(BlobferryOptions.parse("""{"options_schema":2,"options":[{"name":"X","type":"bool"}]}"""))
        assertNull(BlobferryOptions.parse("""{"options_schema":1,"options":[]}"""))
        assertNull(BlobferryOptions.load(java.io.File("/nonexistent-rootfs")))
        val fallback = BlobferryOptions.fallback { "d$it" }
        assertTrue(fallback.isNotEmpty())
        assertTrue(fallback.all { it.kind == Kind.BOOL && it.name.startsWith("MALI_") && it.control == Control.SWITCH })
        assertNotNull(fallback.firstOrNull { it.name == "MALI_COMPAT_GPU_TIME" })
    }

    @Test fun unknownTypesAndBadNamesAreSkippedNotFatal() {
        val manifest = """{"options_schema":1,"options":[
            {"name":"GOOD","type":"bool","default":false,"status":"stable"},
            {"name":"BAD TYPE","type":"bool"},{"name":"WEIRD","type":"matrix"},{"name":"ENUM_NO_VALUES","type":"enum"}]}"""
        assertEquals(listOf("GOOD"), BlobferryOptions.parse(manifest)!!.map { it.name })
    }

    private fun one(type: String, default: String) =
        """{"options_schema":1,"options":[{"name":"X_OPT","type":"$type","default":$default,"values":null,"category":"c","status":"stable","description":"d","games":[],"min_layer":"any"}]}"""
}
