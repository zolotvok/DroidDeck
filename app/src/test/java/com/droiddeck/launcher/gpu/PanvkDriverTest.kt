package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PanvkDriverTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "graphics_driver").deleteRecursively()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
        context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setAndroidDriver(context, "")
        SessionPrefs.setLinuxDriver(context, "")
    }

    private fun library(libc: String) = ByteArray(64).also {
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1).copyInto(it)
        it[18] = 0xB7.toByte()
    } + libc.toByteArray() + ByteArray(1)

    private fun zip(entries: Map<String, ByteArray>): Uri {
        val file = File.createTempFile("driver", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return Uri.fromFile(file)
    }

    private fun icdLibrary(m: LinuxVulkanDriverManager, id: String) =
        JSONObject(File(m.getIcdPath(id)).readText()).getJSONObject("ICD").getString("library_path")

    @Test
    fun maliIsExperimentalWithNoAutomaticPair() {
        val mali = GpuInfo("Mali-G1-Ultra MC12", 0, Family.MALI, "", false)
        assertEquals(GpuInfo.Support.UNTESTED, mali.support)
        assertEquals("Experimental: its drivers are test builds", mali.supportText)
        assertNull(DriverPairs.recommendedKey(mali, emptyList()))
    }

    @Test
    fun panvkLinuxZipGoesIntoThePanfrostSlot() {
        val m = LinuxVulkanDriverManager(context)
        val id = m.installDriver(zip(mapOf(
            "libvulkan_panfrost.so" to library("libc.so.6"),
            "panfrost_icd.aarch64.json" to "{}".toByteArray(),
            "meta.json" to JSONObject().put("name", "PanVK Kbase").put("driverVersion", "26.3.0-devel").toString().toByteArray(),
        )), "panvk.zip")
        assertTrue(m.isInstalled(id))
        assertTrue(m.isPanvk(id))
        assertTrue(m.hasPanvk())
        assertTrue(File(m.getDriverDir(id), LinuxVulkanDriverManager.PANVK_LIB_NAME).isFile)
        assertFalse(File(m.getDriverDir(id), LinuxVulkanDriverManager.LIB_NAME).exists())
        assertTrue(icdLibrary(m, id).endsWith("/" + LinuxVulkanDriverManager.PANVK_LIB_NAME))
        assertEquals("PanVK Kbase", m.getDriverName(id))
    }

    @Test
    fun turnipLinuxZipStillUsesTheFreedrenoSlot() {
        val m = LinuxVulkanDriverManager(context)
        val id = m.installDriver(zip(mapOf("libvulkan_freedreno.so" to library("libc.so.6"))), "turnip-Linux.zip")
        assertTrue(m.isInstalled(id))
        assertFalse(m.isPanvk(id))
        assertFalse(m.hasPanvk())
        assertTrue(icdLibrary(m, id).endsWith("/" + LinuxVulkanDriverManager.LIB_NAME))
    }

    @Test
    fun bundleWithAPanvkLinuxHalfInstallsIt() {
        val manifest = JSONObject()
            .put("package", "PanVK").put("version", "0.1.0").put("tag", "panvk-v0.1.0")
            .put("drivers", JSONObject()
                .put("android", JSONObject().put("path", "android"))
                .put("linux", JSONObject().put("path", "linux")))
        val bundle = DriverBundle.install(context, zip(mapOf(
            "manifest.json" to manifest.toString().toByteArray(),
            "android/meta.json" to JSONObject().put("name", "PanVK").put("libraryName", "libvulkan_panfrost.so").toString().toByteArray(),
            "android/libvulkan_panfrost.so" to library("libc.so"),
            "linux/libvulkan_panfrost.so" to library("libc.so.6"),
        )))
        val m = LinuxVulkanDriverManager(context)
        assertTrue(m.isPanvk(bundle.linuxId))
        assertTrue(icdLibrary(m, bundle.linuxId).endsWith("/" + LinuxVulkanDriverManager.PANVK_LIB_NAME))
    }

    private fun installBridge(): String = LinuxVulkanDriverManager(context).installDriver(zip(mapOf(
        "libvulkan_mali_bridge.so" to library("libc.so.6"),
        "meta.json" to JSONObject().put("name", "Mali system driver (bridge)").put("driverVersion", "v1.r54p1").toString().toByteArray(),
    )), "Mali_Bridge.zip")

    @Test
    fun maliBridgeZipGoesIntoItsOwnSlotAndIsNotPanvk() {
        val m = LinuxVulkanDriverManager(context)
        val id = installBridge()
        assertTrue(m.isInstalled(id))
        assertTrue(m.isBridge(id))
        assertFalse(m.isPanvk(id))
        assertFalse(m.hasPanvk())
        assertTrue(icdLibrary(m, id).endsWith("/" + LinuxVulkanDriverManager.BRIDGE_LIB_NAME))
        assertEquals("v1.r54p1", m.getDriverVersion(id))
    }

    @Test
    fun theBridgeIsOnlyForGamesAndNeedsLibhybrisInTheRuntime() {
        val id = installBridge()
        assertTrue(LinuxVulkanDriver.isBridge(context, id))
        assertEquals("", LinuxVulkanDriver.gameDriver(context, id))
        File(LinuxRuntime.rootDir(context), "usr/lib/hybris").mkdirs()
        File(LinuxRuntime.rootDir(context), "usr/lib/hybris/libhybris-common.so.1").writeText("x")
        assertEquals(id, LinuxVulkanDriver.gameDriver(context, id))
        assertEquals("", LinuxVulkanDriver.gameDriver(context, ""))
        assertEquals("", LinuxVulkanDriver.gameDriver(context, "another"))
    }

    @Test
    fun anExplicitLinuxDriverChoiceIsKept() {
        assertEquals("chosen", LinuxVulkanDriver.effective(context, "chosen"))
    }
}
