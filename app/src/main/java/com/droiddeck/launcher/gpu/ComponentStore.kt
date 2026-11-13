package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.ArchivePaths
import com.droiddeck.launcher.gpu.BlobferryPackage.BlobferryException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The store of a folder-shaped updater package (Blobferry's pattern, release-perf-1111.md § 3.2): `active/` and
 * `previous/` are whole verified package trees with a `package.json` record; `previous/BUNDLED` = "the APK's own"; no
 * `active/` = the bundled package. Every switch writes the journal `applying` first and removes it last; [recover] makes
 * the live files match `active/` again after a crash. The live files are the caller's: [apply] gets the active package
 * folder, or null for the bundled one. Callers hold BlobferryPackage.LOCK. [app, Claude Opus 5.5] 2026-11-13
 */
class ComponentStore(val dir: File) {
    val active get() = File(dir, "active")
    val previous get() = File(dir, "previous")

    fun record(pkg: File): JSONObject? = runCatching { JSONObject(File(pkg, RECORD).readText()) }.getOrNull()
        ?.takeIf { it.optString("version").isNotEmpty() }

    fun activeRecord() = record(active)

    /** "bundled" when previous/ is the APK's package, its version when it is a package, else null. */
    fun previousVersion(): String? = record(previous)?.optString("version") ?: if (File(previous, BUNDLED_MARK).isFile) "bundled" else null

    /** [tree] (a verified, unpacked package) becomes active/ with [record]; the active one before becomes previous/. */
    fun install(tree: File, record: JSONObject, bundledVersion: String?, apply: (File?) -> Unit) {
        val next = File(dir, "new")
        next.deleteRecursively()
        if (!tree.renameTo(next)) throw IOException("cannot move the package into the store")
        writeAtomic(File(next, RECORD), record.toString(2))
        beginJournal()
        previous.deleteRecursively()
        if (activeRecord() != null) {
            if (!active.renameTo(previous)) throw IOException("cannot keep the active package")
        } else {
            active.deleteRecursively()
            bundledVersion?.let { previous.mkdirs(); writeAtomic(File(previous, BUNDLED_MARK), it) }
        }
        if (!next.renameTo(active)) throw IOException("cannot activate the package")
        try {
            apply(active)
        } catch (e: Exception) {
            val failed = File(dir, "failed")
            failed.deleteRecursively()
            active.renameTo(failed)
            if (record(previous) != null) previous.renameTo(active) else previous.deleteRecursively()
            runCatching { apply(active.takeIf { record(it) != null }) }
            failed.deleteRecursively()
            // the journal stays: the next recover() makes the live files match again
            throw BlobferryException("APPLY_FAILED", "could not write the files (${e.message}); the package before it is back")
        }
        endJournal()
    }

    /** Swaps active/ and previous/ (rolling back again goes forward again). */
    fun rollback(bundledVersion: String?, apply: (File?) -> Unit) {
        val previousBundled = File(previous, BUNDLED_MARK).isFile
        if (record(previous) == null && !previousBundled) throw BlobferryException("NO_PREVIOUS", "there is no previous package")
        if (previousBundled && bundledVersion == null) throw BlobferryException("NO_BUNDLED", "this app has no bundled package")
        beginJournal()
        val swap = File(dir, "swap")
        swap.deleteRecursively()
        if (activeRecord() != null) {
            if (!active.renameTo(swap)) throw IOException("cannot move the active package")
        } else {
            active.deleteRecursively()
            bundledVersion?.let { swap.mkdirs(); writeAtomic(File(swap, BUNDLED_MARK), it) }
        }
        if (!previous.renameTo(active)) throw IOException("cannot activate the previous package")
        if (swap.exists() && !swap.renameTo(previous)) swap.deleteRecursively()
        if (File(active, BUNDLED_MARK).isFile) active.deleteRecursively()
        apply(active.takeIf { record(it) != null })
        endJournal()
    }

    /** Back to the bundled package; the active one becomes previous/ (a roll back undoes the reset). */
    fun reset(bundledVersion: String, apply: (File?) -> Unit) {
        beginJournal()
        if (activeRecord() != null) {
            previous.deleteRecursively()
            if (!active.renameTo(previous)) throw IOException("cannot keep the active package")
        }
        active.deleteRecursively()
        apply(null)
        endJournal()
    }

    /** At stage(): a switch a crash interrupted is finished (live files := active/, else the bundled package). */
    fun recover(apply: (File?) -> Unit): Boolean {
        if (!File(dir, JOURNAL).exists()) return false
        for (name in listOf("new", "swap", "failed")) File(dir, name).deleteRecursively()
        try {
            apply(active.takeIf { record(it) != null })
        } catch (e: Exception) {
            active.deleteRecursively()
            apply(null)
        }
        endJournal()
        return true
    }

    /** Removes what an interrupted import left (older than an hour, so a concurrent import keeps its files). */
    fun sweep() {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.filter { (it.name.startsWith("incoming-") || it.name.startsWith("staging-")) && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private fun beginJournal() {
        dir.mkdirs()
        writeAtomic(File(dir, JOURNAL), "sync\n")
    }

    private fun endJournal() {
        File(dir, JOURNAL).delete()
    }

    companion object {
        const val RECORD = "package.json"
        const val BUNDLED_MARK = "BUNDLED"
        const val JOURNAL = "applying"

        fun writeAtomic(target: File, text: String) {
            target.parentFile?.mkdirs()
            val staged = File(target.parentFile, target.name + ".staged")
            try {
                staged.writeText(text)
                if (!staged.renameTo(target)) throw IOException("cannot write $target")
            } finally {
                staged.delete()
            }
        }

        /**
         * Copies [input] into [store] (size cap, sha256 on the way) and unpacks it into a new staging folder: exactly one
         * top folder [prefix]<version>, no links, no unsafe paths, entry / size caps. Returns (sha256, version, staging,
         * root, regular files). The caller deletes staging when it refuses the package.
         */
        fun unpack(
            store: File, input: InputStream, prefix: String, maxZip: Long, maxEntries: Int, maxUnpacked: Long, expectedSha256: String?,
        ): Unpacked {
            store.mkdirs()
            val id = System.nanoTime()
            val zip = File(store, "incoming-$id.zip")
            val staging = File(store, "staging-$id")
            try {
                val sha = MessageDigest.getInstance("SHA-256")
                var total = 0L
                input.use { inp ->
                    zip.outputStream().use { out ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = inp.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxZip) throw BlobferryException("TOO_LARGE", "the package is larger than ${maxZip shr 20} MB")
                            sha.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    }
                }
                val zipSha = sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (expectedSha256 != null && !expectedSha256.equals(zipSha, ignoreCase = true)) {
                    throw BlobferryException("BAD_CHECKSUM", "sha256 $zipSha does not match the expected $expectedSha256")
                }
                val entries = BlobferryPackage.centralDirectory(zip)
                if (entries.size > maxEntries) throw BlobferryException("TOO_LARGE", "the package has more than $maxEntries entries")
                if (entries.map { it.name }.toSet().size != entries.size) throw BlobferryException("BAD_STRUCTURE", "duplicate zip entries")
                val top = entries.map { it.name.substringBefore('/') }.toSet().singleOrNull()
                if (top == null || !top.startsWith(prefix) || entries.any { !it.name.startsWith("$top/") }) {
                    throw BlobferryException("NOT_A_PACKAGE", "not a package with one $prefix<version>/ folder")
                }
                if (!staging.mkdirs()) throw IOException("cannot create $staging")
                val root = File(staging, top)
                val regular = LinkedHashMap<String, File>()
                var unpacked = 0L
                ZipFile(zip).use { z ->
                    if (z.size() != entries.size) throw BlobferryException("BAD_STRUCTURE", "the zip directory and its entries disagree")
                    for (e in entries) {
                        if (e.directory) continue
                        if (e.symlink) throw BlobferryException("UNSAFE_PATH", "symbolic link in the package: ${e.name}")
                        val rel = e.name.removePrefix("$top/")
                        if (!ComponentMeta.safePath(rel)) throw BlobferryException("UNSAFE_PATH", "unsafe path in the package: ${e.name}")
                        val target = ArchivePaths.inside(root, rel) ?: throw BlobferryException("UNSAFE_PATH", "path leaves the package: ${e.name}")
                        target.parentFile?.mkdirs()
                        z.getInputStream(z.getEntry(e.name) ?: throw BlobferryException("BAD_STRUCTURE", "missing entry ${e.name}")).use { inp ->
                            target.outputStream().use { out ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    val n = inp.read(buffer)
                                    if (n < 0) break
                                    unpacked += n
                                    if (unpacked > maxUnpacked) throw BlobferryException("TOO_LARGE", "the package unpacks to more than ${maxUnpacked shr 20} MB")
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        regular[rel] = target
                    }
                }
                return Unpacked(zipSha, top.removePrefix(prefix), staging, root, regular)
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw e
            } finally {
                zip.delete()
            }
        }
    }

    class Unpacked(val zipSha256: String, val version: String, val staging: File, val root: File, val files: Map<String, File>)
}
