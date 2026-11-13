package com.droiddeck.launcher.ui

import com.droiddeck.launcher.gpu.FexArm64ecPackage
import com.droiddeck.launcher.gpu.InstalledComponents
import com.droiddeck.launcher.gpu.PanvkPackage
import com.droiddeck.launcher.session.MaliEnvDefaults
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Components card's rows from the real InstalledComponents rows (the `droiddeckctl components status` JSON). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentsCardTest {
    private val slot = PanvkPackage.SLOT_ID
    private val md5 = "0123456789abcdef0123456789abcdef"

    private fun blobferry(source: String = "bundled", previous: String? = null, bundled: String? = "2026.11.13.1") = InstalledComponents.Row(
        "blobferry", "2026.11.13.1", source, "4077514abcdef", "2026-11-13", "layer fixes", "compat.so", md5, previous, previous != null,
        JSONObject().put("bundledVersion", bundled ?: JSONObject.NULL),
    ).json()

    private fun panvk(
        source: String = "bundled", previous: String? = null, session: PanvkPackage.SessionUse? = null, useBack: String? = null,
        notOurs: Boolean = false, installed: Boolean = true,
    ) = InstalledComponents.panvk(PanvkPackage.Status(
        source = if (installed) source else "none", version = if (installed) "26.0.0-bf3" else "", driverVersion = "26.0.0", commit = "abc1234def",
        soMd5 = md5, previous = previous, bundledVersion = "26.0.0-bf3", installed = installed, canSwitch = true, slot = slot,
        notOurs = notOurs, foreign = listOf(PanvkPackage.Foreign("PanVK_Kbase", "PanVK Kbase", "25.3.0", md5)), session = session, useBack = useBack,
    )).json()

    private fun fex(source: String = "bundled", previous: String? = null) = InstalledComponents.fexArm64ec(FexArm64ecPackage.Status(
        source, "2026.11.13.1", "2d90092006bf", "2026-11-13", "two rows", emptyList(), md5, previous, "2026.11.13.1", true, "bundled",
    )).json()

    private fun env(source: String = "bundled", previous: String? = null) = InstalledComponents.envDefaults(MaliEnvDefaults.Status(
        source, "2026.11.07.3", 14, "f".repeat(64), null, "2026.11.07.3", previous, false,
    )).json()

    private fun rows(vararg items: JSONObject, running: Boolean = false, busy: Boolean = false) =
        componentRows(JSONArray(items.toList()), running, busy).associateBy { it.component }

    private fun ops(row: ComponentRow) = row.actions.map { it.op }

    @Test fun bundledOnlyShowsNoButtons() {
        val r = rows(blobferry(), panvk(), fex(), env())
        for (name in listOf("blobferry", "fex-arm64ec", "env-defaults")) assertEquals(name, emptyList<ComponentOp>(), ops(r.getValue(name)))
        val b = r.getValue("blobferry")
        assertEquals(listOf("2026.11.13.1", "bundled", "4077514", "2026-11-13", "layer fixes", "compat.so", "md5 01234567"),
            listOf(b.version, b.source, b.commit, b.date, b.changelog, b.keyFile, b.keyHash))
        assertNull(b.previous)
        assertEquals("sha256 ffffffff", r.getValue("env-defaults").keyHash)
        // the session's driver is unknown to this status: no in-use line and no Use button
        assertNull(r.getValue("panvk").inUse)
        assertEquals(emptyList<ComponentOp>(), ops(r.getValue("panvk")))
    }

    @Test fun installedWithPreviousOffersRollBackAndReset() {
        val r = rows(blobferry("adb", "bundled"), fex("adb", "2026.11.13.1"), env("adb", "2026.11.07.2"), panvk("adb", "26.0.0-bf2"))
        for (name in listOf("blobferry", "fex-arm64ec", "env-defaults", "panvk")) {
            val row = r.getValue(name)
            assertEquals(name, listOf(ComponentOp.ROLLBACK, ComponentOp.RESET), ops(row))
            assertTrue(name, row.actions.all { it.enabled })
        }
        assertEquals("bundled", r.getValue("blobferry").previous)
        // no bundled package: nothing to reset to
        assertEquals(listOf(ComponentOp.ROLLBACK), ops(rows(blobferry("adb", "2026.11.01.1", bundled = null)).getValue("blobferry")))
        // built-in env defaults are what a reset gives
        assertEquals(emptyList<ComponentOp>(), ops(rows(env(MaliEnvDefaults.SOURCE_BUILT_IN)).getValue("env-defaults")))
    }

    @Test fun aRunningSessionDisablesTheSwitchesExceptEnvDefaults() {
        val r = rows(blobferry("adb", "bundled"), fex("adb", "x"), env("adb", "y"), running = true)
        assertTrue(r.getValue("blobferry").actions.all { it.block == ComponentBlock.SESSION_RUNNING })
        assertTrue(r.getValue("fex-arm64ec").actions.all { it.block == ComponentBlock.SESSION_RUNNING })
        assertTrue(r.getValue("env-defaults").actions.all { it.enabled })
        assertTrue(rows(env("adb", "y"), busy = true).getValue("env-defaults").actions.all { it.block == ComponentBlock.BUSY })
    }

    @Test fun panvkNotInUseOffersUseOnlyWhenTheSessionRunsAPanvk() {
        val kbase = JSONObject().put("id", "PanVK_Kbase").put("ours", false)
        val row = rows(panvk(session = PanvkPackage.SessionUse("PanVK_Kbase", false, kbase, false))).getValue("panvk")
        assertEquals("PanVK_Kbase", row.inUse)
        assertEquals(listOf(ComponentOp.USE), ops(row))
        assertEquals(listOf("PanVK_Kbase"), row.foreign.map { it.id })
        assertEquals("panvk use is refused for a non-PanVK session (NOT_PANVK_SESSION)", emptyList<ComponentOp>(),
            ops(rows(panvk(session = PanvkPackage.SessionUse("Turnip_25", false, null, false))).getValue("panvk")))
        val runtime = rows(panvk(session = PanvkPackage.SessionUse("", false, null, false))).getValue("panvk")
        assertEquals("", runtime.inUse)
        assertEquals(emptyList<ComponentOp>(), ops(runtime))
        assertEquals(ComponentBlock.SESSION_RUNNING, rows(panvk(session = PanvkPackage.SessionUse("PanVK_Kbase", false, kbase, true)), running = true)
            .getValue("panvk").action(ComponentOp.USE)?.block)
    }

    @Test fun panvkInUseAndAUseRecordOfferBackTo() {
        val ours = JSONObject().put("id", slot).put("ours", true)
        val inUse = rows(panvk(session = PanvkPackage.SessionUse(slot, true, ours, false))).getValue("panvk")
        assertEquals(slot, inUse.inUse)
        assertEquals(emptyList<ComponentOp>(), ops(inUse))
        val used = rows(panvk(session = PanvkPackage.SessionUse(slot, true, ours, false), useBack = "PanVK_Kbase")).getValue("panvk")
        assertEquals(listOf(ComponentOp.UNUSE), ops(used))
        assertEquals("PanVK_Kbase", used.useBack)
        assertEquals("", rows(panvk(session = PanvkPackage.SessionUse(slot, true, ours, false), useBack = "")).getValue("panvk").useBack)
    }

    @Test fun aSlotThatIsNotOursOrAbsentComponentsChangeNothing() {
        val notOurs = rows(panvk("hand-imported", "26.0.0-bf2", notOurs = true)).getValue("panvk")
        assertEquals(listOf(ComponentOp.ROLLBACK, ComponentOp.RESET), ops(notOurs))
        assertTrue(notOurs.actions.all { it.block == ComponentBlock.NOT_OURS })
        val absent = rows(panvk(installed = false), fex("none"), blobferry("none", bundled = null))
        assertEquals("", absent.getValue("panvk").version)
        assertEquals(listOf(ComponentOp.RESET), ops(absent.getValue("panvk")))
        assertEquals("", absent.getValue("fex-arm64ec").version)
        assertEquals(emptyList<ComponentOp>(), ops(absent.getValue("blobferry")))
        val error = componentRows(JSONArray().put(JSONObject().put("component", "guest-tools").put("error", "boom")), false, false).single()
        assertEquals("boom", error.error)
        assertEquals(emptyList<ComponentOp>(), ops(error))
    }
}
