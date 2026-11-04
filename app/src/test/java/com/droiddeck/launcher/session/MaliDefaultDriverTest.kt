package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MaliDefaultDriverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val bridge = LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, LinuxVulkanDriverManager.DIR_NAME).deleteRecursively()
    }

    private fun installBridge() {
        val dir = LinuxVulkanDriverManager(context).getDriverDir(bridge).apply { mkdirs() }
        File(dir, LinuxVulkanDriverManager.BRIDGE_LIB_NAME).writeBytes(ByteArray(4))
        File(dir, LinuxVulkanDriverManager.ICD_NAME).writeText("{}")
    }

    @Test fun maliWithTheBundledBridgeAndNoChoicePicksTheBridge() {
        installBridge()
        assertEquals(bridge, SessionPrefs.linuxDriver(context, mali = true))
        assertEquals("", SessionPrefs.linuxDriver(context, mali = false))
    }

    @Test fun noBridgeInstalledKeepsTheRuntimeDefault() {
        assertEquals("", SessionPrefs.linuxDriver(context, mali = true))
    }

    @Test fun aStoredChoiceWinsRuntimeDefaultIncluded() {
        installBridge()
        SessionPrefs.setLinuxDriver(context, "")
        assertEquals("", SessionPrefs.linuxDriver(context, mali = true))
        SessionPrefs.setLinuxDriver(context, "PanVK_Kbase")
        assertEquals("PanVK_Kbase", SessionPrefs.linuxDriver(context, mali = true))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().putString("linuxDriver.desktop", "old").commit()
        assertEquals("old", SessionPrefs.linuxDriver(context, mali = true))
    }

    @Test fun theAutoPickDoesNotCountAsAManualDriverChoice() {
        installBridge()
        SessionPrefs.settleGpuDriverMode(context)
        assertEquals(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.gpuDriverMode(context))
    }
}
