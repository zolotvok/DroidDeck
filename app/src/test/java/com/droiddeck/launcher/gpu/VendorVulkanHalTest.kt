package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorVulkanHalTest {
    private val vivo = mapOf("ro.hardware.vulkan" to "mali", "ro.board.platform" to "mt6993", "ro.hardware" to "mt6993")

    @Test fun namesKeepLoaderOrderAndSkipEmptyOrUnsafeValues() {
        assertEquals("ro.hardware.vulkan=mali,ro.board.platform=mt6993,ro.hardware=mt6993",
            VendorVulkanHal.names { vivo[it].orEmpty() })
        val odd = mapOf("ro.hardware.vulkan" to "", "ro.board.platform" to " mt6993 ", "ro.hardware" to "../evil,x=y")
        assertEquals("ro.board.platform=mt6993", VendorVulkanHal.names { odd[it].orEmpty() })
        assertEquals("", VendorVulkanHal.names { "" })
    }

    @Test fun reportListsOnlyVulkanHalFileNames() {
        val dirs = mapOf("/vendor/lib64/hw" to listOf("vulkan.mali.so", "gralloc.default.so", "vulkan.mt6993.so"), "/odm/lib64/hw" to emptyList())
        assertEquals("vendor: vulkan.mali.so, vulkan.mt6993.so; odm: none", VendorVulkanHal.files { dirs[it] })
        assertEquals("vendor: not readable; odm: not readable", VendorVulkanHal.files { null })
        assertEquals("unset", VendorVulkanHal.properties { "" }.first().second)
    }

    @Test fun resolveFollowsTheIcdOrder() {
        val vivoFiles = setOf("/vendor/lib64/hw/vulkan.mali.so", "/vendor/lib64/hw/vulkan.mt6993.so")
        assertEquals("/vendor/lib64/hw/vulkan.mali.so (via ro.hardware.vulkan=mali)",
            VendorVulkanHal.resolve({ vivo[it].orEmpty() }, { it in vivoFiles }, { null }))
        assertEquals("/vendor/lib64/hw/vulkan.mali.so (via built-in default)",
            VendorVulkanHal.resolve({ "" }, { it in vivoFiles }, { null }))
        val platformOnly = setOf("/odm/lib64/hw/vulkan.mt6993.so")
        assertEquals("/odm/lib64/hw/vulkan.mt6993.so (via ro.board.platform=mt6993)",
            VendorVulkanHal.resolve({ vivo[it].orEmpty() }, { it in platformOnly }, { null }))
        val scan = mapOf("/vendor/lib64/hw" to listOf("vulkan.zz.so", "vulkan.oplus.so", "libvulkan.x.so"))
        val scanFiles = setOf("/vendor/lib64/hw/vulkan.zz.so", "/vendor/lib64/hw/vulkan.oplus.so")
        assertEquals("/vendor/lib64/hw/vulkan.oplus.so (via scan of /vendor/lib64/hw)",
            VendorVulkanHal.resolve({ "" }, { it in scanFiles }, { scan[it] }))
        assertNull(VendorVulkanHal.resolve({ "" }, { false }, { null }))
    }
}
