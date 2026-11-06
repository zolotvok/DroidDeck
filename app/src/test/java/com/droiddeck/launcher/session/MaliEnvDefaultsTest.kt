package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MaliEnvDefaultsTest {
    private lateinit var tmp: File
    private lateinit var layout: MaliEnvDefaults.Layout
    private var bundled: ByteArray? = null
    private val host = object : MaliEnvDefaults.Host {
        override fun bundled() = bundled
    }
    private val shipped = File("../tools/linuxfs/overlay/usr/share/droiddeck-mali/env-defaults.json")
    private val guest get() = File(layout.rootfs, MaliEnvDefaults.GUEST_FILE)

    @Before fun setUp() {
        tmp = Files.createTempDirectory("envdefaults").toFile()
        layout = MaliEnvDefaults.Layout(File(tmp, "store"), File(tmp, "linuxfs"))
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun manifest(version: String, level: String = "12_1") = JSONObject().put("schema", MaliEnvDefaults.SCHEMA).put("version", version)
        .put("games", JSONObject().put("steam:1245620", JSONObject().put("title", "Elden Ring").put("evidence", "t")
            .put("env", JSONObject().put("VKD3D_FEATURE_LEVEL", level)))).toString().toByteArray()

    private fun install(bytes: ByteArray, source: String = "adb") =
        MaliEnvDefaults.install(layout, host, MaliEnvDefaults.prepare(bytes.inputStream()), source)

    @Test fun theShippedManifestIsValid() {
        val m = MaliEnvDefaults.parse(shipped.readBytes())
        assertEquals(12, m.games.size)
        assertEquals("2026.11.06.4", m.version)
        assertEquals("release", JSONObject(String(shipped.readBytes())).getString("status"))
        assertEquals("12_1", m.games.getValue("steam:1245620").env["VKD3D_FEATURE_LEVEL"])
        assertEquals("2048", m.games.getValue("steam:1245620").env["MALI_COMPAT_BUDGET_MB"])
        // the flash/flicker winner is in force (the built-in PASS_ORDER was reverted on bridge main)
        assertEquals("0x0d6", m.games.getValue("steam:292030").env["MALI_COMPAT_FB_MASK"])
        assertEquals("0x0d6", m.games.getValue("epic:Calluna").env["MALI_COMPAT_FB_MASK"])
        assertTrue(m.games.getValue("steam:292030").pending >= 1)
        // no DXR forcing anywhere, and the NVIDIA vendor id only for DOOM Eternal (its launcher's whitelist)
        assertEquals(mapOf("MALI_COMPAT_VENDOR_ID" to "10de"), m.games.getValue("steam:782330").env)
        for ((key, game) in m.games) {
            assertFalse(key, game.env["VKD3D_FEATURE_LEVEL"] == "12_2")
            assertTrue(key, game.env.keys.none { "NVAPI" in it || it.startsWith("MALI_COMPAT_DEVICE_") })
            if (key != "steam:782330") assertFalse(key, "MALI_COMPAT_VENDOR_ID" in game.env)
        }
        assertTrue(m.games.keys.none { it.startsWith("steam:") && it.removePrefix("steam:").toLong() >= (1L shl 31) })
    }

    /** The same cases as droiddeck-mali-defaults' test (tools/tests/data/env-defaults-cases.json). */
    @Test fun sharedCasesWithTheGuestRules() {
        val cases = JSONObject(File("../tools/tests/data/env-defaults-cases.json").readText())
        val invalid = cases.getJSONArray("invalid")
        for (i in 0 until invalid.length()) {
            val case = invalid.getJSONObject(i)
            try {
                MaliEnvDefaults.parse(case.getJSONObject("manifest").toString().toByteArray())
                fail("accepted: " + case.getString("why"))
            } catch (e: BlobferryException) {
                assertEquals(case.getString("why"), "BAD_MANIFEST", e.code)
            }
        }
        val valid = cases.getJSONArray("valid")
        for (i in 0 until valid.length()) MaliEnvDefaults.parse(valid.getJSONObject(i).getJSONObject("manifest").toString().toByteArray())
    }

    @Test fun newestWinsAndTheGuestCopyFollows() {
        bundled = manifest("2026.11.06.1")
        assertEquals("bundled", MaliEnvDefaults.status(layout, host).source)
        val newer = install(manifest("2026.11.07.1", "12_0"))
        assertEquals("adb", newer.source)
        assertEquals("2026.11.07.1", newer.version)
        assertTrue(guest.readBytes().contentEquals(manifest("2026.11.07.1", "12_0")))
        // an older download than the bundled one stays installed but not in force
        val older = install(manifest("2026.11.05.1"), "download")
        assertEquals("bundled", older.source)
        assertEquals("2026.11.06.1", older.version)
        assertTrue(older.bundledNewer)
        assertFalse(guest.exists())
        assertEquals("2026.11.07.1", older.previous)
        val back = MaliEnvDefaults.rollback(layout, host)
        assertEquals("adb", back.source)
        assertEquals("2026.11.07.1", back.version)
        assertEquals("2026.11.05.1", back.previous)
        assertTrue(guest.isFile)
        val reset = MaliEnvDefaults.reset(layout, host)
        assertEquals("bundled", reset.source)
        assertNull(reset.installedVersion)
        assertFalse(guest.exists())
    }

    @Test fun withoutAnyManifestTheBuiltInRecipesApply() {
        val s = MaliEnvDefaults.status(layout, host)
        assertEquals(MaliEnvDefaults.SOURCE_BUILT_IN, s.source)
        assertEquals("built-in recipes", s.label)
        val installed = install(manifest("2026.11.06.1"))
        assertEquals("2026.11.06.1 (adb), 1 games", installed.label)
        assertTrue(guest.isFile)
    }

    @Test fun checksumAndSizeAreEnforced() {
        try {
            MaliEnvDefaults.prepare(manifest("2026.11.06.1").inputStream(), "0".repeat(64))
            fail()
        } catch (e: BlobferryException) {
            assertEquals("BAD_CHECKSUM", e.code)
        }
        try {
            MaliEnvDefaults.prepare(ByteArray(MaliEnvDefaults.MAX_BYTES + 10) { ' '.code.toByte() }.inputStream())
            fail()
        } catch (e: BlobferryException) {
            assertEquals("TOO_LARGE", e.code)
        }
        try {
            MaliEnvDefaults.rollback(layout, host)
            fail()
        } catch (e: BlobferryException) {
            assertEquals("NO_PREVIOUS", e.code)
        }
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(MaliEnvDefaults.compare("2026.11.06.10", "2026.11.06.9") > 0)
        assertEquals(0, MaliEnvDefaults.compare("2026.11.06.1", "2026.11.06.1"))
        assertTrue(MaliEnvDefaults.compare("2026.10.31.5", "2026.11.01.1") < 0)
    }
}
