package dev.cerbos.epdp

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Persists the most recent Cerbos Hub policy bundle response per rule, so the embedded PDP can
 * start offline with the last known policies. Bodies are stored as received from Hub (opaque
 * binary); the bridge replays them only when the initial download fails.
 *
 * All operations run on [ioDispatcher] and are safe to call from any thread.
 */
public class PolicyBundleCache(
    public val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Uses [defaultDirectory]: the app's no-backup files directory, so bundles never leave the
     * device.
     */
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
    /**
     * Serialises writes and the discard-on-corruption path so concurrent saves cannot interleave.
     */
    private val mutex = Mutex()

    /**
     * Returns the cached response body for the key, or `null` if there is none or it is corrupt.
     */
    public suspend fun load(key: String): Cached? =
        withContext(ioDispatcher) { mutex.withLock { loadLocked(key) } }

    private fun loadLocked(key: String): Cached? {
        run {
            val files = files(key)
            val entry =
                try {
                    json.decodeFromString(Entry.serializer(), files.entry.readText())
                } catch (_: IOException) {
                    null
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            val body =
                try {
                    if (files.body.isFile) files.body.readBytes() else null
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
    }

    /**
     * Writes the bundle atomically. Throws [CerbosException.Cache] when the files cannot be
     * written.
     */
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
                    writeAtomically(files.body) { it.writeBytes(body) }
                    writeAtomically(files.entry) {
                        it.writeText(json.encodeToString(Entry.serializer(), entry))
                    }
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
                .mapNotNull { file ->
                    try {
                        json.decodeFromString(Entry.serializer(), file.readText())
                    } catch (_: IOException) {
                        null
                    } catch (_: SerializationException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                .sortedByDescending { it.savedAt }
        }

    private class Files(val body: File, val entry: File)

    private fun files(key: String): Files {
        val name = fileName(key)
        return Files(body = File(directory, "$name.bundle"), entry = File(directory, "$name.json"))
    }

    private fun removeFiles(files: Files) {
        files.body.delete()
        files.entry.delete()
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val temporary = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        write(temporary)
        if (!temporary.renameTo(target)) {
            // `renameTo` does not replace on every filesystem; fall back to delete-then-rename.
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw IOException("could not move ${temporary.name} into place")
            }
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
