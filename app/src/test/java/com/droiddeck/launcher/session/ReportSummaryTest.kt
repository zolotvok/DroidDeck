package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.ReportSummary.Category
import com.droiddeck.launcher.session.ReportSummary.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSummaryTest {
    private fun info(
        vulkanError: Boolean = false, env: Map<String, String?> = mapOf("MALI_COMPAT_WLS_CLAMP" to "all", "DXVK_HUD" to null),
        source: String = "bundled", commit: String = "a28a42d9", devFiles: List<String> = emptyList(), base: String = "",
        panvk: String = "", envDefaults: String = "",
    ) =
        ReportSummary.Info(
            written = "2026-11-05 12:00 CET", appVersion = "0.6.9-mali.1 (18)", appId = "com.droiddeck.launcher.mali",
            buildType = "release", sourceCommit = "a5b0e689f49b06bb", device = "vivo X200", android = "15 (API 35)",
            soc = "Mediatek MT6991", gpu = "Mali-G925", vendorDriver = "Mali r51p0", blobferryVersion = "1.0.0-rc1",
            blobferryCommit = commit, blobferrySource = source, blobferryShimMd5 = "0123abcd", fexDllMd5 = "a2448b03ffff",
            proton = "Proton 10.0", displayDriver = "auto", runtimeDriver = "Mali_Bridge", sessionDriver = "panvk", gameDriver = "bridge",
            game = "Watch_Dogs 2 (447040)", gameEnv = env, focusGuard = "on (bridge game)", boost = "boost off",
            logs = "session logs on", protonLog = "found (steam-447040.log)", lastExit = "crash", vulkanError = vulkanError,
            blobferryDevFiles = devFiles, blobferryBaseCommit = base, panvk = panvk, envDefaults = envDefaults,
        )

    @Test fun aDevBuildSaysItHasNoCommitAndWhatItChanged() {
        val text = ReportSummary.table(info(source = "dev", commit = "",
            devFiles = listOf("usr/lib/mali_bridge/libVkLayer_droiddeck_mali_compat.so"), base = "a28a42d6aaaa"))
        assertTrue(text, text.contains("| Blobferry | 1.0.0-rc1, dev build (no commit): hand-deployed libVkLayer_droiddeck_mali_compat.so; " +
            "the rest is the bundled package @ a28a42d, shim md5 0123abcd |"))
        assertFalse(text.contains("@ ?"))
        // a .dev tree whose files all match the bundled package has the bundled commit
        assertTrue(ReportSummary.table(info(source = "dev", commit = "a28a42d6aaaa")).contains("| Blobferry | 1.0.0-rc1 @ a28a42d (dev), shim"))
    }

    @Test fun categoriesPickTheRepoTheUserDecided() {
        for (c in listOf(Category.GRAPHICS, Category.GPU_FAULT, Category.DRIVER_LAYER, Category.BRIDGE_CRASH, Category.MALI_PERF)) {
            assertEquals(c.key, Repo.BLOBFERRY, ReportSummary.suggestRepo(c, vulkanError = false))
        }
        for (c in listOf(Category.APP_UI, Category.STORE_LAUNCH, Category.STEAM_WINDOW, Category.SESSION_START, Category.UPDATER_REPORT)) {
            assertEquals(c.key, Repo.FORK, ReportSummary.suggestRepo(c, vulkanError = true))
        }
    }

    @Test fun unsureAndNoStartFollowTheLogs() {
        for (c in listOf(Category.UNSURE, Category.GAME_NO_START)) {
            assertEquals(Repo.BLOBFERRY, ReportSummary.suggestRepo(c, vulkanError = true))
            assertEquals(Repo.FORK, ReportSummary.suggestRepo(c, vulkanError = false))
        }
    }

    @Test fun vulkanErrorsAreRecognised() {
        assertTrue(ReportSummary.vulkanDriverError(sequenceOf("ok", "err: vkQueueSubmit failed: VK_ERROR_DEVICE_LOST")))
        assertTrue(ReportSummary.vulkanDriverError(sequenceOf("[gamescope] vulkan: failed to find physical device")))
        assertTrue(ReportSummary.vulkanDriverError(sequenceOf("kbase: GROUP_ERROR_FATAL TRANSLATION_FAULT at 0x7f00")))
        assertFalse(ReportSummary.vulkanDriverError(sequenceOf("Steam started", "== steam exited rc=0")))
    }

    @Test fun issueCarriesTheVersionTableAndTheZipName() {
        val text = ReportSummary.issue(info(), Category.GPU_FAULT, "DroidDeck-report-20261105-1200.zip")
        assertTrue(text.contains("| Blobferry | 1.0.0-rc1 @ a28a42d (bundled), shim md5 0123abcd |"))
        assertTrue(text.contains("| FEX DLL md5 | a2448b03ffff |"))
        assertTrue(text.contains("| DroidDeck | 0.6.9-mali.1 (18) (com.droiddeck.launcher.mali), release @ a5b0e689f4 |"))
        assertTrue(text.contains("MALI_COMPAT_WLS_CLAMP=all -DXVK_HUD"))
        assertTrue(text.contains("**Category** gpu-fault"))
        assertTrue(text.contains("attach DroidDeck-report-20261105-1200.zip"))
        assertTrue(ReportSummary.issueTitle(Category.GPU_FAULT, info()).startsWith("[gpu-fault] Watch_Dogs 2:"))
    }

    @Test fun theMaliRowsNamePanvkAndTheEnvDefaultsVersion() {
        val mali = info(panvk = "v26.3.1-234-237 (download) @ 10a845b, so md5 bda2be8e18da9e6eb0e439c7df645e34",
            envDefaults = "2026.11.06.1 (bundled), 11 games")
        val text = ReportSummary.table(mali)
        assertTrue(text, text.contains("| PanVK | v26.3.1-234-237 (download) @ 10a845b, so md5 bda2be8e18da9e6eb0e439c7df645e34 |"))
        assertTrue(text.contains("| Env defaults | 2026.11.06.1 (bundled), 11 games |"))
        assertFalse(ReportSummary.table(info()).contains("| PanVK |"))
    }

    @Test fun summaryMentionsAVulkanError() {
        assertTrue(ReportSummary.markdown(info(vulkanError = true)).contains("Vulkan/driver error"))
        assertFalse(ReportSummary.markdown(info()).contains("Vulkan/driver error"))
    }

    @Test fun tableCellsCannotBreakTheTable() {
        val text = ReportSummary.table(info(env = mapOf("X" to "a|b")))
        assertTrue(text.contains("X=a\\|b"))
    }

    @Test fun issueUrlDropsALongBody() {
        val short = ReportSummary.issueUrl(Repo.BLOBFERRY, "t 1", "body")
        assertEquals("https://github.com/zolotvok/blobferry/issues/new?title=t%201&body=body", short)
        val long = ReportSummary.issueUrl(Repo.FORK, "t", "x".repeat(7000))
        assertEquals("https://github.com/zolotvok/DroidDeck/issues/new?title=t", long)
    }
}
