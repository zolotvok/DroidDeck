package com.droiddeck.launcher.gpu

import android.content.Context
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GuestToolsPackageTest {
    companion object {
        /** The bundled agent's AGENT_RELEASE and md5 (also PINNED_AGENT in tools/tests/test_guest_tools.py). */
        val PINNED_AGENT = "2026.11.13.1" to "02372e72ff59e56d161a5d3e1629bc2e"
    }

    private lateinit var tmp: File
    private lateinit var layout: GuestToolsPackage.Layout
    private val commit = "b9e89a427" + "0".repeat(31)
    private var bundledRelease = "2026.11.13.1"
    private var bundledAgent = agent("2026.11.13.1", "bundled")
    private val host = object : GuestToolsPackage.Host {
        var running = false
        var hasBundled = true
        override val sessionRunning get() = running
        override val bundled get() = if (!hasBundled) null else GuestToolsPackage.Bundled(bundledRelease, md5(bundledAgent)) { rel ->
            if (rel == GuestToolsPackage.AGENT) bundledAgent else null
        }
    }

    @Before fun setUp() {
        tmp = Files.createTempDirectory("guest-tools").toFile()
        layout = GuestToolsPackage.Layout(File(tmp, "files/guest-tools"), File(tmp, "rootfs"))
        stageBundled()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun md5(b: ByteArray) = hex(MessageDigest.getInstance("MD5").digest(b))

    private fun agent(release: String, tag: String = release) = (
        "#!/usr/bin/python3\nimport sys\nVERSION = 1\nAGENT_RELEASE = \"$release\"\n# $tag\n" +
            "def main(argv):\n    if argv == [\"--selftest\"]:\n        return 0\n    return 2\n"
        ).toByteArray()

    /** What SessionFiles.stage() does before restage(): the APK's agent into the rootfs. */
    private fun stageBundled() {
        live().parentFile!!.mkdirs()
        live().writeBytes(bundledAgent)
    }

    private fun live() = File(layout.rootfs, GuestToolsPackage.AGENT)
    private fun activeJson() = File(layout.rootfs, GuestToolsPackage.ACTIVE_JSON)

    private fun files(version: String, agent: ByteArray = agent(version)): MutableMap<String, ByteArray> = linkedMapOf(
        "rootfs/usr/local/bin/droiddeck-agent" to agent, "LICENSE" to "GPL-3".toByteArray(), "SOURCE" to "DroidDeck $commit".toByteArray(),
    )

    private fun meta(files: Map<String, ByteArray>, version: String, change: (JSONObject) -> Unit = {}): ByteArray {
        val list = JSONObject()
        for ((rel, data) in files) list.put(rel, JSONObject().put("sha256", sha256(data)).put("md5", md5(data)))
        return JSONObject().put("component", "guest-tools").put("version", version).put("date", "2026-11-13").put("changelog", "agent")
            .put("source", JSONObject().put("repo", "https://github.com/zolotvok/DroidDeck").put("commit", commit))
            .put("files", list).put("minApp", 20).also(change).toString(2).toByteArray()
    }

    private fun zip(version: String, content: Map<String, ByteArray> = files(version), top: String = "guest-tools-$version/",
                    metaChange: (JSONObject) -> Unit = {}, extra: Map<String, ByteArray> = emptyMap(), links: Set<String> = emptySet()): ByteArray {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for ((name, data) in content + mapOf("meta.json" to meta(content, version, metaChange)) + extra) {
                    z.putNextEntry(ZipEntry(top + name)); z.write(data); z.closeEntry()
                }
            }
        }.toByteArray()
        return if (links.isEmpty()) bytes else markLinks(bytes, links.map { top + it }.toSet())
    }

    /** Marks central-directory entries as unix symlinks (as BlobferryPackageTest). */
    private fun markLinks(b: ByteArray, names: Set<String>): ByteArray {
        var p = 0
        while (p + 46 <= b.size) {
            if (b[p] == 0x50.toByte() && b[p + 1] == 0x4b.toByte() && b[p + 2] == 1.toByte() && b[p + 3] == 2.toByte()) {
                val n = (b[p + 28].toInt() and 0xff) or ((b[p + 29].toInt() and 0xff) shl 8)
                if (String(b, p + 46, n) in names) {
                    b[p + 5] = 3
                    val mode = 0xa1ff
                    b[p + 38] = 0; b[p + 39] = 0; b[p + 40] = (mode and 0xff).toByte(); b[p + 41] = (mode shr 8).toByte()
                }
                p += 46 + n
            } else p++
        }
        return b
    }

    private fun prepare(bytes: ByteArray) = GuestToolsPackage.prepare(layout, bytes.inputStream())
    private fun install(version: String, agent: ByteArray = agent(version)) =
        GuestToolsPackage.install(layout, host, prepare(zip(version, files(version, agent))), "adb")

    private fun events() = ComponentLog.tail(ComponentLog.forStore(layout.store), 200).map { it.split('\t') }

    private fun refused(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: BlobferryException) {
            assertEquals(e.message, code, e.code)
        }
        assertTrue("staging left behind", layout.store.listFiles().orEmpty().none { it.name.startsWith("staging-") || it.name.startsWith("incoming-") })
    }

    @Test fun installRollbackResetSwitchTheLiveAgentAndActiveJson() {
        val newer = agent("2026.11.14.1")
        val status = install("2026.11.14.1", newer)
        assertEquals(md5(newer), md5(live().readBytes()))
        assertTrue(live().canExecute())
        assertEquals("2026.11.14.1", status.version)
        assertEquals("adb", status.source)
        assertEquals(md5(newer), status.agentMd5)
        assertEquals(md5(newer), status.liveAgentMd5)
        assertEquals("bundled", status.previous)
        assertNull(status.shadowed)
        val record = JSONObject(activeJson().readText())
        assertEquals("2026.11.14.1", record.getString("version"))
        assertEquals(md5(newer), record.getJSONObject("files").getString(GuestToolsPackage.AGENT))

        val back = GuestToolsPackage.rollback(layout, host)
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertFalse(activeJson().exists())
        assertEquals("bundled", back.source)
        assertEquals("2026.11.13.1", back.version)
        assertEquals("2026.11.14.1", back.previous)
        GuestToolsPackage.rollback(layout, host)
        assertEquals(md5(newer), md5(live().readBytes()))

        val reset = GuestToolsPackage.reset(layout, host)
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertEquals("2026.11.14.1", reset.previous)
        assertEquals(listOf("install", "rollback", "rollback", "reset"), events().map { it[2] })
        assertTrue(events().all { it[1] == "guest-tools" && it[7] == "ok" })
    }

    @Test fun noSwitchWhileASessionRuns() {
        install("2026.11.14.1")
        host.running = true
        val prepared = prepare(zip("2026.11.15.1"))
        refused("SESSION_RUNNING") { GuestToolsPackage.install(layout, host, prepared, "adb") }
        refused("SESSION_RUNNING") { GuestToolsPackage.rollback(layout, host) }
        refused("SESSION_RUNNING") { GuestToolsPackage.reset(layout, host) }
        assertEquals(md5(agent("2026.11.14.1")), md5(live().readBytes()))
        assertEquals(listOf("SESSION_RUNNING", "SESSION_RUNNING", "SESSION_RUNNING"), events().filter { it[2] == "refused" }.map { it[7] })
    }

    @Test fun onlyTheAllowlistedAgentLicenseSourceAndMetaAreAccepted() {
        val v = "2026.11.14.1"
        refused("NOT_ALLOWED") { prepare(zip(v, files(v) + ("rootfs/usr/local/bin/droiddeck-mali-defaults" to "x".toByteArray()))) }
        refused("NOT_ALLOWED") { prepare(zip(v, files(v) + ("rootfs/usr/local/bin/droiddeck-agent.bak" to "x".toByteArray()))) }
        refused("NOT_ALLOWED") { prepare(zip(v, files(v) + ("NOTES" to "x".toByteArray()))) }
        refused("NOT_ALLOWED") { prepare(zip(v, files(v) + ("usr/local/bin/droiddeck-agent" to agent(v)))) }
        // traversal, absolute-looking and empty segments, links
        refused("UNSAFE_PATH") { prepare(zip(v, extra = mapOf("rootfs/../../usr/local/bin/droiddeck-agent" to agent(v)))) }
        refused("UNSAFE_PATH") { prepare(zip(v, extra = mapOf("rootfs//usr/local/bin/droiddeck-agent" to agent(v)))) }
        refused("NOT_A_PACKAGE") { prepare(zip(v, top = "/guest-tools-$v/")) }
        refused("UNSAFE_PATH") { prepare(zip(v, links = setOf("rootfs/usr/local/bin/droiddeck-agent"))) }
        refused("UNSAFE_PATH") { prepare(zip(v, metaChange = { it.getJSONObject("files").put("../LICENSE", JSONObject().put("sha256", "0".repeat(64))) })) }
        // every file listed in meta.json and matching
        refused("BAD_META") { prepare(zip(v, metaChange = { it.getJSONObject("files").remove("SOURCE") })) }
        refused("BAD_CHECKSUM") { prepare(zip(v, metaChange = { it.getJSONObject("files").getJSONObject("LICENSE").put("sha256", "0".repeat(64)) })) }
        refused("BAD_STRUCTURE") { prepare(zip(v, files(v).apply { remove("SOURCE") })) }
        refused("BAD_STRUCTURE") { prepare(zip(v, files(v).apply { remove("rootfs/usr/local/bin/droiddeck-agent") })) }
        refused("BAD_META") { prepare(zip(v, metaChange = { it.put("component", "fex-arm64ec") })) }
        refused("APP_TOO_OLD") { prepare(zip(v, metaChange = { it.put("minApp", 100000) })) }
        assertTrue(events().all { it[2] == "refused" })
        assertFalse(File(layout.store, "active").exists())
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
    }

    @Test fun theAgentMustBeAPython3ScriptOfThePackageRelease() {
        val v = "2026.11.14.1"
        refused("BAD_SCRIPT") { prepare(zip(v, files(v, ByteArray(0)))) }
        refused("BAD_SCRIPT") { prepare(zip(v, files(v, agent(v).toString(Charsets.UTF_8).replace("#!/usr/bin/python3", "#!/bin/sh").toByteArray()))) }
        refused("BAD_SCRIPT") { prepare(zip(v, files(v, agent(v) + byteArrayOf(0xff.toByte(), 0xfe.toByte())))) }
        refused("BAD_SCRIPT") { prepare(zip(v, files(v, agent(v).toString(Charsets.UTF_8).replace("--selftest", "--other").toByteArray()))) }
        refused("BAD_SCRIPT") { prepare(zip(v, files(v, agent(v).toString(Charsets.UTF_8).replace("AGENT_RELEASE", "RELEASE").toByteArray()))) }
        refused("BAD_META") { prepare(zip(v, files(v, agent("2026.11.15.1")))) }
        refused("BAD_SCRIPT") { prepare(zip(v, files(v, "#!/usr/bin/python3\n".toByteArray() + ByteArray(GuestToolsPackage.MAX_SCRIPT_BYTES.toInt()) { 'a'.code.toByte() }))) }
        // env python3 is a python3 shebang too
        GuestToolsPackage.discard(prepare(zip(v, files(v, agent(v).toString(Charsets.UTF_8).replace("#!/usr/bin/python3", "#!/usr/bin/env python3").toByteArray()))))
        // M1: the session execs the agent through its shebang: no \r, no flags, nothing else on that line
        for (line in listOf("#!/usr/bin/python3\r", "#!/usr/bin/env python3 -I", "#! /usr/bin/python3", "#!/usr/bin/python3 -I")) {
            refused("BAD_SCRIPT") { prepare(zip(v, files(v, agent(v).toString(Charsets.UTF_8).replace("#!/usr/bin/python3", line).toByteArray()))) }
        }
    }

    @Test fun theVersionIsADateAndASerial() {
        // M2: anything else would never be older than an APK release (a letter token sorts above digits)
        for (v in listOf("dev", "20261.11.13.1", "2026.11.13.1-rc1", "2026.11.13", "2026.11.13.1000")) {
            refused("BAD_META") { prepare(zip(v, files(v))) }
        }
        GuestToolsPackage.discard(prepare(zip("2026.1.2.3", files("2026.1.2.3"))))
    }

    @Test fun restageAfterTheApkFilesPutsTheActiveAgentBackEveryStart() {
        val newer = agent("2026.11.14.1")
        install("2026.11.14.1", newer)
        // a session start: stage() writes the APK's agent first, restage() then puts the active one over it
        stageBundled()
        assertEquals("active", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(newer), md5(live().readBytes()))
        assertTrue(activeJson().isFile)
        assertEquals("active", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(newer), md5(live().readBytes()))
        // without a package the APK's agent stays and no active.json is left
        GuestToolsPackage.reset(layout, host)
        stageBundled()
        assertEquals("bundled", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertFalse(activeJson().exists())
    }

    @Test fun anOlderActivePackageNeverShadowsTheAgentOfANewerApk() {
        // equal release: only the identical agent is accepted (and wins: it is the same file)
        refused("SAME_VERSION_DIFFERENT") { install("2026.11.13.1", agent("2026.11.13.1", "hand-built")) }
        install("2026.11.13.1", bundledAgent)
        assertEquals("active", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertNull(GuestToolsPackage.status(layout, host).shadowed)
        install("2026.11.14.1")
        // the APK is upgraded: its agent is release 2026.11.20.1
        bundledRelease = "2026.11.20.1"
        bundledAgent = agent("2026.11.20.1", "new apk")
        stageBundled()
        assertEquals("older than the bundled agent 2026.11.20.1; the next session start keeps it as previous", GuestToolsPackage.status(layout, host).shadowed)
        assertEquals("upgrade-at-start", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertFalse(activeJson().exists())
        val status = GuestToolsPackage.status(layout, host)
        assertEquals("bundled", status.source)
        assertEquals("2026.11.20.1", status.version)
        assertEquals("2026.11.14.1", status.previous)
        assertEquals(listOf("upgrade-at-start", "2026.11.14.1", "2026.11.20.1 (bundled)", "ok"), events().last().let { listOf(it[2], it[3], it[4], it[7]) })
        // and it cannot come back by a roll back or an install
        refused("OLDER_THAN_BUNDLED") { GuestToolsPackage.rollback(layout, host) }
        refused("OLDER_THAN_BUNDLED") { install("2026.11.19.9") }
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertEquals("bundled", GuestToolsPackage.restage(layout, host))
        // a newer package than the APK's still wins
        install("2026.11.21.1")
        assertEquals("active", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(agent("2026.11.21.1")), md5(live().readBytes()))
    }

    @Test fun anApkAgentEditedWithoutAReleaseBumpWinsOverAPackageOfThatRelease() {
        // M3: the installed package is the APK's agent of 2026.11.13.1; the next APK edits its agent but keeps the release
        install("2026.11.13.1", bundledAgent)
        bundledAgent = agent("2026.11.13.1", "edited, no bump")
        stageBundled()
        assertEquals("same release as the bundled agent 2026.11.13.1 but another agent; the next session start keeps it as previous",
            GuestToolsPackage.status(layout, host).shadowed)
        assertEquals("upgrade-at-start", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertFalse(activeJson().exists())
        assertEquals("bundled", GuestToolsPackage.status(layout, host).source)
        assertTrue(events().last().let { it[2] == "upgrade-at-start" && it[8].contains("same release 2026.11.13.1 with another agent md5") })
        refused("SAME_VERSION_DIFFERENT") { GuestToolsPackage.rollback(layout, host) }
        assertEquals("bundled", GuestToolsPackage.restage(layout, host))
    }

    @Test fun aCorruptActivePackageFallsBackToTheBundledAgentAndIsLogged() {
        install("2026.11.14.1")
        File(layout.store, "active/rootfs/usr/local/bin/droiddeck-agent").writeText("#!/usr/bin/python3\nbroken\n")
        stageBundled()
        assertEquals("corrupt-fallback", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertFalse(activeJson().exists())
        assertEquals(listOf("refused", "CORRUPT_ACTIVE"), events().last().let { listOf(it[2], it[7]) })
        assertEquals("bundled", GuestToolsPackage.status(layout, host).source)
        assertEquals("bundled", GuestToolsPackage.restage(layout, host))

        // an unreadable package record: no active package, the APK's agent stays
        install("2026.11.15.1")
        File(layout.store, "active/package.json").writeText("{not json")
        stageBundled()
        assertEquals("bundled", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))

        // the context entry point never throws, whatever the store holds
        val context = RuntimeEnvironment.getApplication() as Context
        val store = File(context.filesDir, GuestToolsPackage.STORE)
        try {
            store.mkdirs()
            File(store, "active").writeText("a file where a folder should be")
            File(store, "applying").writeText("sync\n")
            val rootfs = File(tmp, "ctx-rootfs").apply { mkdirs() }
            GuestToolsPackage.restage(context, rootfs)
        } finally {
            store.deleteRecursively()
        }
    }

    @Test fun anInterruptedSwitchIsFinishedAtStage() {
        install("2026.11.14.1")
        // a crash after the journal was written and before the live file was replaced
        File(layout.store, "applying").writeText("sync\n")
        stageBundled()
        assertEquals("recovered", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(agent("2026.11.14.1")), md5(live().readBytes()))
        assertFalse(File(layout.store, "applying").exists())
        assertEquals("restage", events().last()[2])
    }

    @Test fun aPackageTheGuestCheckRefusedStaysUnusedUntilAnotherInstall() {
        val bad = agent("2026.11.14.1", "fails selftest")
        install("2026.11.14.1", bad)
        val refusal = File(layout.rootfs, GuestToolsPackage.GUEST_REFUSED)
        refusal.parentFile!!.mkdirs()
        refusal.writeText(JSONObject().put("version", "2026.11.14.1").put("agentMd5", md5(bad)).put("reason", "--selftest failed (exit 1)").toString())
        stageBundled()
        assertEquals("refused-by-guest", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bundledAgent), md5(live().readBytes()))
        assertFalse(activeJson().exists())
        assertEquals("refused by the guest check: --selftest failed (exit 1)", GuestToolsPackage.status(layout, host).shadowed)
        // the guest's event moves into the app's install log with the fex ones
        val guestLog = File(layout.rootfs, FexArm64ecPackage.GUEST_LOG)
        guestLog.writeText("2026-11-13T12:00:00Z\tguest-tools\trefused\t2026.11.14.1\tbundled\t-\tguest\tSELFTEST_FAILED\t--selftest failed\n" +
            "2026-11-13T12:00:00Z\tguest-tools\tinstall\tx\ty\t-\tguest\tok\n")
        assertEquals(1, FexArm64ecPackage.ingestGuestLog(FexArm64ecPackage.Layout(File(tmp, "files/fex-arm64ec"), layout.rootfs)))
        assertEquals(listOf("guest-tools", "refused", "SELFTEST_FAILED"), events().last().let { listOf(it[1], it[2], it[7]) })
        // M4: a reset and a roll back try it afresh too (the refusal and the transient count go)
        val attempts = File(layout.rootfs, GuestToolsPackage.GUEST_ATTEMPTS)
        attempts.writeText("{\"key\": \"x\", \"count\": 2}")
        GuestToolsPackage.reset(layout, host)
        assertFalse(refusal.exists())
        assertFalse(attempts.exists())
        refusal.writeText(JSONObject().put("version", "2026.11.14.1").put("agentMd5", md5(bad)).put("reason", "x").toString())
        attempts.writeText("{}")
        GuestToolsPackage.rollback(layout, host)
        assertFalse(refusal.exists())
        assertFalse(attempts.exists())
        stageBundled()
        assertEquals("active", GuestToolsPackage.restage(layout, host))
        assertEquals(md5(bad), md5(live().readBytes()))
        // a new package is tried again
        refusal.writeText(JSONObject().put("version", "2026.11.14.1").put("agentMd5", md5(bad)).put("reason", "x").toString())
        install("2026.11.15.1")
        assertFalse(refusal.exists())
        stageBundled()
        assertEquals("active", GuestToolsPackage.restage(layout, host))
    }

    @Test fun theApkBundlesAnAgentWithAReleaseAndTheGuestCheck() {
        val context = RuntimeEnvironment.getApplication() as Context
        val bundled = GuestToolsPackage.bundled(context)!!
        val repoAgent = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/linuxfs/overlay/usr/local/bin/droiddeck-agent") }.first { it.isFile }
        assertEquals(GuestToolsPackage.release(repoAgent.readBytes()), bundled.release)
        assertEquals(md5(repoAgent.readBytes()), bundled.agentMd5)
        assertTrue(bundled.release.matches(GuestToolsPackage.VERSION))
        // M3 pin: a package of an equal release wins only with the identical md5, so every agent edit must raise
        // AGENT_RELEASE. Changed the agent? Raise AGENT_RELEASE in tools/linuxfs/overlay/usr/local/bin/droiddeck-agent and
        // update this pin and PINNED_AGENT in tools/tests/test_guest_tools.py.
        assertEquals("the bundled agent changed without an AGENT_RELEASE bump (or the pin is stale): raise AGENT_RELEASE and update the pin",
            PINNED_AGENT, bundled.release to bundled.agentMd5)
        assertTrue(context.assets.open("linuxfs/usr/local/bin/droiddeck-guest-tools-check").use { it.readBytes() }.isNotEmpty())
        assertNull(bundled.open("usr/local/bin/droiddeck-session"))
    }

    @Test fun installedComponentsRow() {
        install("2026.11.14.1")
        val row = InstalledComponents.guestTools(GuestToolsPackage.status(layout, host)).json()
        assertEquals("guest-tools", row.getString("component"))
        assertEquals("2026.11.14.1", row.getString("version"))
        assertEquals(md5(agent("2026.11.14.1")), row.getString("keyFileMd5"))
        assertEquals(md5(agent("2026.11.14.1")), row.getString("liveAgentMd5"))
        assertEquals("bundled", row.getString("previous"))
        assertEquals("2026.11.13.1", row.getString("bundledVersion"))
        assertTrue(row.getBoolean("canRollback"))
    }
}
