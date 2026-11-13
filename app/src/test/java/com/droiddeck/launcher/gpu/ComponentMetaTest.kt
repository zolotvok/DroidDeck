package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentMetaTest {
    private lateinit var root: File
    private val commit = "10a845b24cce8278838951dbc603fd9c6d8d9f8f"

    @Before fun setUp() {
        root = Files.createTempDirectory("meta").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun md5(b: ByteArray) = hex(MessageDigest.getInstance("MD5").digest(b))

    private fun meta(files: Map<String, ByteArray>, minApp: Int = 20, change: (JSONObject) -> Unit = {}): JSONObject {
        val list = JSONObject()
        for ((rel, data) in files) list.put(rel, JSONObject().put("sha256", sha256(data)).put("md5", md5(data)))
        return JSONObject().put("component", "panvk").put("version", "v26.3.1-234-237").put("date", "2026-11-13")
            .put("changelog", "MSRTSS fix").put("source", JSONObject().put("repo", "https://github.com/zolotvok/blobferry").put("commit", commit))
            .put("files", list).put("minApp", minApp).also(change)
    }

    private fun write(files: Map<String, ByteArray>) = files.forEach { (rel, data) -> File(root, rel).apply { parentFile?.mkdirs() }.writeBytes(data) }

    private fun refused(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: BlobferryException) {
            assertEquals(e.message, code, e.code)
        }
    }

    private val tree = mapOf("libvulkan_panfrost.so" to "so".toByteArray(), "patches/237.patch" to "diff".toByteArray())

    @Test fun parsesAndVerifiesAMatchingTree() {
        write(tree + mapOf("LICENSE" to "MIT".toByteArray(), "SHA256SUMS" to "x".toByteArray(), "SOURCE.txt" to "s".toByteArray()))
        val json = meta(tree)
        File(root, "meta.json").writeText(json.toString())
        val m = ComponentMeta.parse(json.toString())
        assertEquals("panvk", m.component)
        assertEquals(commit, m.commit)
        assertEquals(20L, m.minApp)
        m.verifyTree(root, 20)
        assertEquals("2026-11-13", m.record().getString("date"))
        assertTrue(ComponentMeta.isComponentMeta(json))
        assertTrue(!ComponentMeta.isComponentMeta(JSONObject().put("kind", "linux-vulkan-icd")))
    }

    @Test fun refusesAChangedMissingOrUnlistedFile() {
        write(tree)
        val m = ComponentMeta.parse(meta(tree).toString())
        File(root, "patches/237.patch").writeText("changed")
        refused("BAD_CHECKSUM") { m.verifyTree(root, 20) }
        write(tree)
        File(root, "smuggled.so").writeText("x")
        refused("BAD_META") { m.verifyTree(root, 20) }
        File(root, "smuggled.so").delete()
        File(root, "patches/237.patch").delete()
        refused("BAD_META") { m.verifyTree(root, 20) }
        // an unlisted LICENSE deeper than the top is not a license file of the package
        write(tree)
        File(root, "patches/LICENSE").writeText("x")
        refused("BAD_META") { m.verifyTree(root, 20) }
    }

    @Test fun refusesUnsafePathsAndBadFields() {
        for (bad in listOf("../evil", "/abs/x", "a//b", "a/./b", "a\\b", "", "a/\u0000b")) {
            refused("UNSAFE_PATH") { ComponentMeta.parse(meta(mapOf(bad to "x".toByteArray())).toString()) }
        }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.getJSONObject("source").put("commit", "abc") }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.getJSONObject("source").put("commit", commit.uppercase()) }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.put("component", "steam") }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.put("date", "13.11.2026") }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.put("changelog", "two\nlines") }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.getJSONObject("source").put("repo", "http://evil.example/x") }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.put("files", JSONObject()) }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(tree) { it.put("minApp", "twenty") }.toString()) }
        refused("BAD_META") { ComponentMeta.parse(meta(mapOf("meta.json" to "x".toByteArray())).toString()) }
        refused("BAD_META") { ComponentMeta.parse("not json") }
    }

    @Test fun refusesAPackageForANewerApp() {
        write(tree)
        val m = ComponentMeta.parse(meta(tree, minApp = 21).toString())
        refused("APP_TOO_OLD") { m.verifyTree(root, 20) }
        m.verifyTree(root, 21)
    }

    @Test fun refusesALinkInTheTree() {
        write(tree)
        Files.createSymbolicLink(File(root, "link.so").toPath(), File(root, "libvulkan_panfrost.so").toPath())
        refused("UNSAFE_PATH") { ComponentMeta.parse(meta(tree).toString()).verifyTree(root, 20) }
    }
}
