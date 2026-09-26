package dev.cerbos.epdp

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Stores the latest Hub bundle response per cache key, so the PDP can start offline. The bridge
 * replays it only when the first download fails.
 */
public class PolicyBundleCache(
    public val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Stores bundles in [defaultDirectory], which is excluded from backups. */
    public constructor(context: Context) : this(defaultDirectory(context))

    @Serializable
    public data class Entry(
        val key: String,
        val bundleId: String,
        val ruleRevision: String,
        @Serializable(with = InstantSerializer::class) val savedAt: Instant,
        val byteCount: Int,
    )

    public class Cached(public val entry: Entry, public val body: ByteArray)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** The cached bundle for [key], or `null` if there is none or it is corrupt. */
    public suspend fun load(key: String): Cached? =
        withContext(ioDispatcher) { mutex.withLock { loadLocked(key) } }

    private fun loadLocked(key: String): Cached? {
        val files = files(key)
        val entry = readEntry(files.entry)
        val body =
            try {
                files.body.readFully()
            } catch (_: IOException) {
                null
            }
        return if (
            entry == null || entry.key != key || body == null || body.size != entry.byteCount
        ) {
            removeFiles(files)
            null
        } else {
            Cached(entry, body)
        }
    }

    /** Writes the bundle atomically. Throws [CerbosException.Cache] on failure. */
    public suspend fun save(
        key: String,
        body: ByteArray,
        bundleId: String,
        ruleRevision: String,
    ): Entry =
        withContext(ioDispatcher) {
            val entry =
                Entry(
                    key = key,
                    bundleId = bundleId,
                    ruleRevision = ruleRevision,
                    savedAt = Instant.now(),
                    byteCount = body.size,
                )
            val files = files(key)
            mutex.withLock {
                try {
                    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                        throw IOException("could not create ${directory.path}")
                    }
                    files.body.write(body)
                    files.entry.write(
                        json.encodeToString(Entry.serializer(), entry).encodeToByteArray()
                    )
                } catch (error: IOException) {
                    throw CerbosException.Cache(error.message ?: error.toString())
                }
            }
            entry
        }

    public suspend fun remove(key: String) {
        withContext(ioDispatcher) { mutex.withLock { removeFiles(files(key)) } }
    }

    public suspend fun removeAll() {
        withContext(ioDispatcher) { mutex.withLock { directory.deleteRecursively() } }
    }

    /** All cached entries, newest first. */
    public suspend fun entries(): List<Entry> =
        withContext(ioDispatcher) {
            (directory.listFiles() ?: emptyArray())
                .filter { it.extension == "json" }
                .mapNotNull { readEntry(AtomicFile(it)) }
                .sortedByDescending { it.savedAt }
        }

    private fun readEntry(file: AtomicFile): Entry? =
        try {
            json.decodeFromString(Entry.serializer(), file.readFully().decodeToString())
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private class Files(val body: AtomicFile, val entry: AtomicFile)

    private fun files(key: String): Files {
        val name = fileName(key)
        return Files(
            body = AtomicFile(File(directory, "$name.bundle")),
            entry = AtomicFile(File(directory, "$name.json")),
        )
    }

    private fun removeFiles(files: Files) {
        files.body.delete()
        files.entry.delete()
    }

    private fun AtomicFile.write(bytes: ByteArray) {
        val stream = startWrite()
        try {
            stream.write(bytes)
            finishWrite(stream)
        } catch (error: IOException) {
            failWrite(stream)
            throw error
        }
    }

    public companion object {
        /** `<no-backup files dir>/CerbosEmbeddedPDP/policy-bundles`. */
        public fun defaultDirectory(context: Context): File =
            File(File(context.noBackupFilesDir, "CerbosEmbeddedPDP"), "policy-bundles")

        /** Stable, filesystem-safe name for a cache key (FNV-1a 64-bit). */
        internal fun fileName(key: String): String {
            var hash = 0xcbf29ce484222325uL
            for (byte in key.encodeToByteArray()) {
                hash = hash xor byte.toUByte().toULong()
                hash *= 0x100000001b3uL
            }
            return hash.toString(16)
        }
    }
}
