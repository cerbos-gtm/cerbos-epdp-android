package dev.cerbos.epdp

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyBundleCacheTest {
    private fun makeCache(): PolicyBundleCache {
        val directory = Files.createTempDirectory("cerbos-cache-tests").toFile()
        directory.deleteOnExit()
        // A dedicated subdirectory that does not exist yet, so `save` must create it.
        return PolicyBundleCache(File(directory, "policy-bundles"), Dispatchers.Unconfined)
    }

    @Test
    fun `save, load, remove`() = runTest {
        val cache = makeCache()
        try {
            val key = "https://api.cerbos.cloud|RULE|"
            val body = ByteArray(1024) { (it % 251).toByte() }

            assertNull(cache.load(key))

            val entry = cache.save(key, body, bundleId = "B1", ruleRevision = "7")
            assertEquals(1024, entry.byteCount)

            val loaded = checkNotNull(cache.load(key))
            assertArrayEquals(body, loaded.body)
            assertEquals("B1", loaded.entry.bundleId)
            assertEquals("7", loaded.entry.ruleRevision)
            assertEquals(listOf(key), cache.entries().map { it.key })

            // A different key is isolated.
            assertNull(cache.load(key + "other"))

            // Overwrite keeps the latest.
            cache.save(key, byteArrayOf(1, 2, 3), bundleId = "B2", ruleRevision = "8")
            assertEquals("B2", cache.load(key)?.entry?.bundleId)
            assertEquals(1, cache.entries().size)

            cache.remove(key)
            assertNull(cache.load(key))
        } finally {
            cache.removeAll()
        }
    }

    @Test
    fun `corrupt entries are discarded`() = runTest {
        val cache = makeCache()
        try {
            val key = "k"
            cache.save(key, ByteArray(100) { 7 }, bundleId = "B", ruleRevision = "1")

            // Truncate the body behind the cache's back.
            val bodyFile = File(cache.directory, PolicyBundleCache.fileName(key) + ".bundle")
            bodyFile.writeBytes(ByteArray(10) { 7 })

            assertNull(cache.load(key))
            assertFalse("corrupt entries are removed", bodyFile.exists())
        } finally {
            cache.removeAll()
        }
    }

    @Test
    fun `file names are stable and safe`() {
        val name = PolicyBundleCache.fileName("https://api.cerbos.cloud|AVGB9RP6HFBL|a,b")
        assertEquals(name, PolicyBundleCache.fileName("https://api.cerbos.cloud|AVGB9RP6HFBL|a,b"))
        assertNotEquals(name, PolicyBundleCache.fileName("https://api.cerbos.cloud|AVGB9RP6HFBL|a"))
        assertTrue(name.all { it in '0'..'9' || it in 'a'..'f' })
        // FNV-1a 64 of the empty string is the offset basis; matches the Swift implementation.
        assertEquals("cbf29ce484222325", PolicyBundleCache.fileName(""))
    }
}
