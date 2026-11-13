package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The guest-tools component (release-perf-1111.md § 3.3, the agent updater): `guest-tools-<V>/` with `meta.json` (§ 3.1,
 * lists every other file), `rootfs/<path>` for each file of [ALLOWLIST] it replaces (now exactly
 * `rootfs/usr/local/bin/droiddeck-agent`), `LICENSE` (GPL-3, DroidDeck) and `SOURCE` (tools/make-guest-tools-package.py).
 * Any other file, a link, an absolute or `..` path is refused. The agent must be a python3 script (shebang, size bound,
 * UTF-8) whose `AGENT_RELEASE` is the package version and which has `--selftest`.
 *
 * Store `files/guest-tools/` (ComponentStore: active/, previous/, journal). SessionFiles.stage() stages the APK's files
 * first, then [restage] copies active/'s files over them ("active wins over bundled", so a session start no longer wipes
 * an updated agent) and writes `usr/local/lib/droiddeck-guest-tools/active.json`; droiddeck-guest-tools-check (guest,
 * before the agent starts) runs py-compile + `droiddeck-agent --selftest` on them and puts the APK's copy (staged to
 * [BUNDLED_COPY]) back when they fail, logged `refused`; the app then keeps the bundled files for that package.
 *
 * APK-upgrade rule: an installed package is used only while its version is not older than the release of the agent the
 * APK bundles (its `AGENT_RELEASE` line, compared as PanvkPackage.compareVersions; equal = the package wins). At the
 * first stage() of a newer APK an older active package goes to previous/ (logged `upgrade-at-start`), the bundled agent
 * stays; install and roll back refuse a package older than the bundled agent (OLDER_THAN_BUNDLED). A corrupt or
 * unreadable active package never stops a session start: the bundled files stay, logged refused CORRUPT_ACTIVE.
 * Switches only while no session runs. [app, Claude Opus 5.5] 2026-11-13
 */
object GuestToolsPackage {
    private const val TAG = "GuestToolsPackage"
    const val COMPONENT = "guest-tools"
    const val STORE = "guest-tools"
    const val PREFIX = "guest-tools-"
    const val AGENT = "usr/local/bin/droiddeck-agent"
    /** The rootfs files a package may replace (decision D3 adds usr/local/bin/droiddeck-mali-defaults later). */
    val ALLOWLIST = listOf(AGENT)
    const val ROOTFS = "rootfs/"
    /** In the rootfs: active.json (what [restage] put over the APK's files) and bundled/ (the APK's own copies). */
    const val STATE_DIR = "usr/local/lib/droiddeck-guest-tools"
    const val ACTIVE_JSON = "$STATE_DIR/active.json"
    const val BUNDLED_COPY = "$STATE_DIR/bundled"
    /** Written by droiddeck-guest-tools-check when an active package failed its check (rootfs-relative). */
    const val GUEST_REFUSED = "root/.local/share/droiddeck-components/guest-tools-refused.json"
    /** The guest's guest-tools events in its install.log (moved into the app's log with the fex ones). */
    val GUEST_OPS = setOf("selftest", "refused")
    const val SOURCE_BUNDLED = "bundled"
    const val MAX_ZIP_BYTES = 4L shl 20
    const val MAX_ENTRIES = 16
    const val MAX_UNPACKED_BYTES = 8L shl 20
    const val MAX_SCRIPT_BYTES = 2L shl 20
    private val TOP_FILES = setOf("LICENSE", "SOURCE")
    private val RELEASE = Regex("""(?m)^AGENT_RELEASE = "([A-Za-z0-9._+-]{1,48})"\s*$""")
    private val SHEBANG = Regex("""^#!\s*/usr/bin/(?:env\s+)?python3(?:\s+-[A-Za-z]+)*\s*\n""")
    private val VERSION = Regex("[A-Za-z0-9._+-]{1,48}")
    private val HEX64 = Regex("(?i)[0-9a-f]{64}")

    class Layout(val store: File, val rootfs: File)

    /** The APK's own files: [release] = its agent's AGENT_RELEASE ("" without one), [open] reads one by rootfs path. */
    class Bundled(val release: String, val agentMd5: String, val open: (String) -> ByteArray?)

    interface Host {
        val sessionRunning: Boolean
        val bundled: Bundled?
    }

    class Prepared internal constructor(
        val version: String, val meta: ComponentMeta, val files: Map<String, String>, val zipSha256: String,
        internal val staging: File, internal val root: File,
    ) {
        val agentMd5 get() = files.getValue(AGENT)
    }

    class Status(
        val source: String, val version: String, val commit: String, val date: String, val changelog: String,
        /** md5 of the agent of the package in force (the active package, else the bundled one). */
        val agentMd5: String,
        /** md5 of usr/local/bin/droiddeck-agent in the rootfs now ("" without one). */
        val liveAgentMd5: String,
        val previous: String?, val bundledVersion: String?, val canSwitch: Boolean,
        /** Why the installed package is not used: older than the bundled agent, refused by the guest check; null = used. */
        val shadowed: String?,
    ) {
        fun json(): JSONObject = JSONObject().put("source", source).put("version", version).put("commit", commit).put("date", date)
            .put("changelog", changelog).put("agentMd5", agentMd5).put("liveAgentMd5", liveAgentMd5)
            .put("hasPrevious", previous != null).put("previous", previous ?: JSONObject.NULL)
            .put("bundledVersion", bundledVersion ?: JSONObject.NULL).put("canSwitch", canSwitch).put("shadowed", shadowed ?: JSONObject.NULL)
    }

    // ---- context entry points ----

    fun layout(context: Context) = Layout(File(context.filesDir, STORE), LinuxRuntime.rootDir(context))

    fun host(context: Context): Host = object : Host {
        override val sessionRunning get() = BlobferryPackage.sessionBusy()
        override val bundled get() = bundled(context)
    }

    /** The APK's agent (asset linuxfs/[AGENT]) and its AGENT_RELEASE; null without one. */
    fun bundled(context: Context): Bundled? = runCatching {
        val agent = context.assets.open("linuxfs/$AGENT").use { it.readBytes() }
        Bundled(release(agent).orEmpty(), md5(agent)) { rel ->
            if (rel in ALLOWLIST) runCatching { context.assets.open("linuxfs/$rel").use { it.readBytes() } }.getOrNull() else null
        }
    }.onFailure { Log.w(TAG, "no bundled agent", it) }.getOrNull()

    fun prepare(context: Context, input: InputStream, expectedSha256: String? = null) = prepare(layout(context), input, expectedSha256)
    fun install(context: Context, prepared: Prepared, source: String) = install(layout(context), host(context), prepared, source)
    fun rollback(context: Context) = rollback(layout(context), host(context))
    fun reset(context: Context) = reset(layout(context), host(context))
    fun status(context: Context) = status(layout(context), host(context))

    /** stage(), after the APK's files are staged: see the pure [restage]. Never throws: the bundled files then stay. */
    fun restage(context: Context, rootfs: File): String {
        val layout = Layout(File(context.filesDir, STORE), rootfs)
        return runCatching { restage(layout, host(context)) }
            .onFailure { Log.w(TAG, "guest-tools restage failed; the bundled agent stays", it) }
            .getOrDefault("failed").also { Log.i(TAG, "guest-tools: $it") }
    }

    /** AGENT_RELEASE of an agent script, null without one. */
    fun release(agent: ByteArray): String? = RELEASE.find(String(agent, Charsets.UTF_8))?.groupValues?.get(1)

    // ---- verify ----

    fun prepare(layout: Layout, input: InputStream, expectedSha256: String? = null): Prepared {
        if (expectedSha256 != null && !HEX64.matches(expectedSha256)) throw BlobferryException("BAD_CHECKSUM", "the expected sha256 is not 64 hex digits")
        ComponentStore(layout.store).sweep()
        var unpacked: ComponentStore.Unpacked? = null
        try {
            unpacked = ComponentStore.unpack(layout.store, input, PREFIX, MAX_ZIP_BYTES, MAX_ENTRIES, MAX_UNPACKED_BYTES, expectedSha256)
            return verify(unpacked)
        } catch (e: BlobferryException) {
            unpacked?.staging?.deleteRecursively()
            ComponentLog.append(ComponentLog.forStore(layout.store), COMPONENT, ComponentLog.REFUSED, null, unpacked?.version, unpacked?.zipSha256, null, e.code)
            throw e
        } catch (e: Exception) {
            unpacked?.staging?.deleteRecursively()
            throw e
        }
    }

    fun discard(prepared: Prepared) {
        prepared.staging.deleteRecursively()
    }

    internal fun verify(u: ComponentStore.Unpacked, appVersionCode: Long = BuildConfig.VERSION_CODE.toLong()): Prepared {
        if (!VERSION.matches(u.version)) throw BlobferryException("BAD_STRUCTURE", "invalid package version '${u.version}'")
        val metaFile = u.files[ComponentMeta.NAME] ?: throw BlobferryException("BAD_META", "meta.json is missing")
        val meta = ComponentMeta.parse(metaFile.readText())
        if (meta.component != COMPONENT) throw BlobferryException("BAD_META", "meta.json is for '${meta.component}', not $COMPONENT")
        if (meta.version != u.version) throw BlobferryException("BAD_META", "meta.json says version ${meta.version}, the package is ${u.version}")
        // the allowlist first: a package carries meta.json, LICENSE, SOURCE and rootfs/<allowed path> only
        for (rel in u.files.keys) {
            if (rel == ComponentMeta.NAME || rel in TOP_FILES) continue
            if (!rel.startsWith(ROOTFS) || rel.removePrefix(ROOTFS) !in ALLOWLIST) {
                throw BlobferryException("NOT_ALLOWED", "$rel is not a guest-tools file (allowed: ${ALLOWLIST.joinToString { ROOTFS + it }})")
            }
        }
        // strict: every file, LICENSE and SOURCE included, must be listed and match
        meta.verifyTree(u.root, appVersionCode) { false }
        for (name in TOP_FILES) if (name !in meta.files) throw BlobferryException("BAD_STRUCTURE", "$name is missing")
        val rootfs = meta.files.keys.filter { it.startsWith(ROOTFS) }
        if (ROOTFS + AGENT !in rootfs) throw BlobferryException("BAD_STRUCTURE", "$ROOTFS$AGENT is missing")
        val agent = File(u.root, ROOTFS + AGENT)
        checkScript(agent)
        val bytes = agent.readBytes()
        val release = release(bytes) ?: throw BlobferryException("BAD_SCRIPT", "the agent has no AGENT_RELEASE line")
        if (release != meta.version) throw BlobferryException("BAD_META", "the agent says AGENT_RELEASE $release, meta.json ${meta.version}")
        if (!String(bytes, Charsets.UTF_8).contains("\"--selftest\"")) throw BlobferryException("BAD_SCRIPT", "the agent has no --selftest")
        val files = rootfs.associate { it.removePrefix(ROOTFS) to md5(File(u.root, it)) }
        return Prepared(u.version, meta, files, u.zipSha256, u.staging, u.root)
    }

    /** A python3 script: non-empty, at most [MAX_SCRIPT_BYTES], a python3 shebang, UTF-8 without NUL. */
    private fun checkScript(file: File) {
        val size = file.length()
        if (size == 0L || size > MAX_SCRIPT_BYTES) throw BlobferryException("BAD_SCRIPT", "${file.name}: size $size is not 1..$MAX_SCRIPT_BYTES bytes")
        val bytes = file.readBytes()
        val decoder = Charsets.UTF_8.newDecoder()
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
            ?: throw BlobferryException("BAD_SCRIPT", "${file.name} is not UTF-8 text")
        if ('\u0000' in text) throw BlobferryException("BAD_SCRIPT", "${file.name} is not text")
        if (!SHEBANG.containsMatchIn(text.substring(0, minOf(text.length, 200))) || !text.startsWith("#!")) {
            throw BlobferryException("BAD_SCRIPT", "${file.name} does not start with a python3 shebang")
        }
    }

    // ---- switch ----

    fun install(layout: Layout, host: Host, prepared: Prepared, source: String): Status = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val from = currentVersion(store, host)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            if (!prepared.root.isDirectory) throw BlobferryException("STALE", "the prepared package is gone; import it again")
            requireNotOlder(prepared.version, host)
            val record = JSONObject().put("version", prepared.version).put("commit", prepared.meta.commit).put("zipSha256", prepared.zipSha256)
                .put("agentMd5", prepared.agentMd5).put("files", JSONObject(prepared.files)).put("source", source)
                .put("installedAt", System.currentTimeMillis()).put("meta", prepared.meta.record())
            // a refusal by the guest check is for the package it named; a new install is tried again
            File(layout.rootfs, GUEST_REFUSED).delete()
            store.install(prepared.root, record, host.bundled?.release) { sync(layout, host, it) }
            Log.i(TAG, "guest-tools ${prepared.version} installed from $source (agent ${prepared.agentMd5})")
            log(layout, ComponentLog.INSTALL, from, prepared.version, prepared.zipSha256, source, ComponentLog.OK)
            status(layout, host)
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, prepared.version, prepared.zipSha256, source, e.code)
            throw e
        } finally {
            prepared.staging.deleteRecursively()
        }
    }

    fun rollback(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val from = currentVersion(store, host)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            store.record(store.previous)?.let { requireNotOlder(it.optString("version"), host) }
            store.rollback(host.bundled?.release) { sync(layout, host, it) }
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, null, null, null, e.code, ComponentLog.ROLLBACK)
            throw e
        }
        status(layout, host).also { log(layout, ComponentLog.ROLLBACK, from, it.version, store.activeRecord()?.optString("zipSha256"), it.source, ComponentLog.OK) }
    }

    /** Back to the APK's agent; the installed package is kept as previous/ (a roll back undoes it). */
    fun reset(layout: Layout, host: Host): Status = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        val from = currentVersion(store, host)
        try {
            if (host.sessionRunning) throw BlobferryException("SESSION_RUNNING", "Stop the session first")
            val bundled = host.bundled ?: throw BlobferryException("NO_BUNDLED", "this app has no bundled agent")
            store.reset(bundled.release) { sync(layout, host, it) }
        } catch (e: BlobferryException) {
            log(layout, ComponentLog.REFUSED, from, null, null, null, e.code, ComponentLog.RESET)
            throw e
        }
        status(layout, host).also { log(layout, ComponentLog.RESET, from, it.version, null, it.source, ComponentLog.OK) }
    }

    /**
     * At every stage(), after the APK's files are staged: finishes an interrupted switch; an active package older than the
     * bundled agent goes to previous/ (`upgrade-at-start`); a corrupt one too (refused CORRUPT_ACTIVE); one the guest check
     * refused stays in the store unused; otherwise active/'s files go over the APK's ones. Without a usable active package
     * the APK's files stay and active.json is removed. Returns what it did.
     */
    fun restage(layout: Layout, host: Host): String = synchronized(BlobferryPackage.LOCK) {
        val store = ComponentStore(layout.store)
        store.sweep()
        val recovered = store.recover { sync(layout, host, it) }
        if (recovered) log(layout, ComponentLog.RESTAGE, null, currentVersion(store, host), null, null, ComponentLog.OK)
        val record = store.activeRecord()
        val bundled = host.bundled
        if (record == null) {
            sync(layout, host, null)
            return@synchronized if (recovered) "recovered" else "bundled"
        }
        val version = record.optString("version")
        if (bundled != null && store.upgradeAtStart(bundled.release) { sync(layout, host, it) } != null) {
            log(layout, ComponentLog.UPGRADE_AT_START, version, "${bundled.release} (bundled)", null, SOURCE_BUNDLED, ComponentLog.OK,
                "the APK's agent is newer; $version kept as previous")
            return@synchronized "upgrade-at-start"
        }
        if (guestRefused(layout, record) != null) {
            sync(layout, host, null)
            return@synchronized "refused-by-guest"
        }
        try {
            sync(layout, host, store.active)
            if (recovered) "recovered" else "active"
        } catch (e: Exception) {
            Log.w(TAG, "the active guest-tools package $version is not usable; the bundled agent stays", e)
            runCatching { store.reset(bundled?.release.orEmpty()) { sync(layout, host, it) } }
                .onFailure { runCatching { sync(layout, host, null) } }
            log(layout, ComponentLog.REFUSED, version, "${bundled?.release ?: "?"} (bundled)", null, SOURCE_BUNDLED, "CORRUPT_ACTIVE",
                (e.message ?: e.javaClass.simpleName) + "; bundled agent kept")
            "corrupt-fallback"
        }
    }

    /** [version] is older than the bundled agent's release (a bundled agent without a release never wins). */
    private fun older(version: String, bundled: Bundled): Boolean = ComponentStore.olderThanBundled(version, bundled.release)

    private fun requireNotOlder(version: String, host: Host) {
        val bundled = host.bundled ?: return
        if (older(version, bundled)) throw BlobferryException(
            "OLDER_THAN_BUNDLED", "guest-tools $version is older than the agent this app bundles (${bundled.release}); it would never be used",
        )
    }

    /** The guest check's refusal of [record]'s agent (its reason), null when it did not refuse this one. */
    private fun guestRefused(layout: Layout, record: JSONObject): String? = runCatching {
        JSONObject(File(layout.rootfs, GUEST_REFUSED).readText()).takeIf { it.optString("agentMd5") == record.optString("agentMd5") }
            ?.optString("reason")?.ifEmpty { "refused" }
    }.getOrNull()

    /**
     * The rootfs files := [pkg]'s (each copied beside its target, md5-checked against the record, made executable, renamed
     * over it) + active.json, or for null the APK's files where they differ, and no active.json.
     */
    private fun sync(layout: Layout, host: Host, pkg: File?) {
        val activeJson = File(layout.rootfs, ACTIVE_JSON)
        if (pkg == null) {
            activeJson.delete()
            val bundled = host.bundled ?: return
            for (rel in ALLOWLIST) {
                val bytes = bundled.open(rel) ?: continue
                val target = File(layout.rootfs, rel)
                if (target.isFile && md5(target) == md5(bytes)) continue
                put(target, bytes, md5(bytes))
            }
            return
        }
        val record = JSONObject(File(pkg, ComponentStore.RECORD).readText())
        val files = record.getJSONObject("files")
        val wanted = files.keys().asSequence().associateWith { files.getString(it) }
        if (wanted.isEmpty() || wanted.keys.any { it !in ALLOWLIST }) throw IOException("the package record lists files outside the allowlist")
        // active.json first: if a crash leaves a file half switched, the guest check sees an md5 that is not the listed one
        // and puts the APK's copy back (an unlisted live agent would run unchecked)
        ComponentStore.writeAtomic(activeJson, JSONObject().put("version", record.optString("version")).put("files", JSONObject(wanted)).toString(2))
        for ((rel, md5) in wanted) {
            val source = File(pkg, ROOTFS + rel)
            if (!source.isFile || md5(source) != md5) throw IOException("$rel changed in the store")
            val target = File(layout.rootfs, rel)
            if (target.isFile && md5(target) == md5) continue
            put(target, source.readBytes(), md5)
        }
    }

    private fun put(target: File, bytes: ByteArray, md5: String) {
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".staged")
        try {
            staged.writeBytes(bytes)
            if (md5(staged) != md5) throw IOException("${target.name}: the staged copy does not match")
            if (!staged.setExecutable(true, false) || !staged.renameTo(target)) throw IOException("cannot write $target")
        } finally {
            staged.delete()
        }
    }

    // ---- status ----

    fun status(layout: Layout, host: Host): Status {
        val store = ComponentStore(layout.store)
        val record = store.activeRecord()
        val bundled = host.bundled
        val meta = record?.optJSONObject("meta")
        val live = File(layout.rootfs, AGENT).let { if (it.isFile) md5(it) else "" }
        val shadowed = record?.let { r ->
            val v = r.optString("version")
            when {
                bundled != null && older(v, bundled) -> "older than the bundled agent ${bundled.release}; the next session start keeps it as previous"
                else -> guestRefused(layout, r)?.let { "refused by the guest check: $it" }
            }
        }
        return Status(
            source = record?.optString("source") ?: if (bundled != null) SOURCE_BUNDLED else "none",
            version = record?.optString("version") ?: bundled?.release.orEmpty(),
            commit = record?.optString("commit").orEmpty(),
            date = meta?.optString("date").orEmpty(), changelog = meta?.optString("changelog").orEmpty(),
            agentMd5 = record?.optString("agentMd5") ?: bundled?.agentMd5.orEmpty(), liveAgentMd5 = live,
            previous = store.previousVersion(), bundledVersion = bundled?.release, canSwitch = !host.sessionRunning, shadowed = shadowed,
        )
    }

    // ---- helpers ----

    /** A guest-tools event of the guest's install.log (droiddeck-guest-tools-check). */
    fun guestEvent(fields: List<String>): Boolean = fields[1] == COMPONENT && fields[2] in GUEST_OPS

    private fun currentVersion(store: ComponentStore, host: Host): String =
        store.activeRecord()?.optString("version") ?: host.bundled?.release?.let { "${it.ifEmpty { "?" }} (bundled)" } ?: "none"

    private fun log(layout: Layout, op: String, from: String?, to: String?, sha256: String?, source: String?, result: String, note: String? = null) =
        ComponentLog.append(ComponentLog.forStore(layout.store), COMPONENT, op, from, to, sha256, source, result, note)

    internal fun md5(file: File): String = md5(file.readBytes())

    internal fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
