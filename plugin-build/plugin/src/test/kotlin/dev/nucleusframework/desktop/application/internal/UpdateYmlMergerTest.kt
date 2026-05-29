package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Tests for [UpdateYmlMerger], the fix for the Linux per-format manifest collision. */
class UpdateYmlMergerTest {
    private fun manifest(
        version: String = "1.2.3",
        url: String,
        sha512: String,
        size: Long,
        blockMapSize: Long? = null,
        releaseDate: String = "2026-05-29T00:00:00.000Z",
    ): String =
        buildString {
            appendLine("version: $version")
            appendLine("files:")
            appendLine("  - url: $url")
            appendLine("    sha512: $sha512")
            appendLine("    size: $size")
            if (blockMapSize != null) appendLine("    blockMapSize: $blockMapSize")
            appendLine("path: $url")
            appendLine("sha512: $sha512")
            appendLine("releaseDate: '$releaseDate'")
        }

    private fun urls(manifest: String): List<String> =
        manifest.lineSequence().map { it.trim() }.filter { it.startsWith("- url: ") }.map { it.removePrefix("- url: ") }.toList()

    private fun fieldOf(manifest: String, url: String, field: String): String? {
        val lines = manifest.lines()
        val start = lines.indexOf("  - url: $url")
        if (start < 0) return null
        var i = start + 1
        while (i < lines.size && lines[i].startsWith("    ")) {
            val f = lines[i].trim()
            if (f.startsWith("$field: ")) return f.removePrefix("$field: ")
            i++
        }
        return null
    }

    private fun topLevel(manifest: String, key: String): String? =
        manifest.lines().lastOrNull { it.startsWith("$key: ") }?.removePrefix("$key: ")

    @Test
    fun `merges deb and AppImage manifests into one with both entries`() {
        val appImage = manifest(url = "Example-1.2.3.AppImage", sha512 = "AAAA==", size = 111, blockMapSize = 50)
        val deb = manifest(url = "example_1.2.3_amd64.deb", sha512 = "BBBB==", size = 222)

        val merged = UpdateYmlMerger.merge(listOf(appImage, deb))

        assertEquals(
            listOf("Example-1.2.3.AppImage", "example_1.2.3_amd64.deb"),
            urls(merged).sorted(),
        )
        assertEquals("1.2.3", topLevel(merged, "version"))
        // Per-entry fields are preserved, including the AppImage's blockMapSize.
        assertEquals("AAAA==", fieldOf(merged, "Example-1.2.3.AppImage", "sha512"))
        assertEquals("111", fieldOf(merged, "Example-1.2.3.AppImage", "size"))
        assertEquals("50", fieldOf(merged, "Example-1.2.3.AppImage", "blockMapSize"))
        assertEquals("BBBB==", fieldOf(merged, "example_1.2.3_amd64.deb", "sha512"))
        // The deb has no blockmap, so no blockMapSize line is emitted for it.
        assertEquals(null, fieldOf(merged, "example_1.2.3_amd64.deb", "blockMapSize"))
    }

    @Test
    fun `top-level path and sha512 mirror the first sorted entry`() {
        val appImage = manifest(url = "Example-1.2.3.AppImage", sha512 = "AAAA==", size = 111)
        val deb = manifest(url = "example_1.2.3_amd64.deb", sha512 = "BBBB==", size = 222)

        // Input order reversed to prove ordering is by url, not input order.
        val merged = UpdateYmlMerger.merge(listOf(deb, appImage))

        assertEquals("Example-1.2.3.AppImage", topLevel(merged, "path"))
        assertEquals("AAAA==", topLevel(merged, "sha512"))
    }

    @Test
    fun `releaseDate is the latest across inputs`() {
        val older = manifest(url = "a.AppImage", sha512 = "A==", size = 1, releaseDate = "2026-01-01T00:00:00.000Z")
        val newer = manifest(url = "b.deb", sha512 = "B==", size = 2, releaseDate = "2026-05-29T12:00:00.000Z")

        val merged = UpdateYmlMerger.merge(listOf(older, newer))

        assertEquals("'2026-05-29T12:00:00.000Z'", topLevel(merged, "releaseDate"))
    }

    @Test
    fun `deduplicates entries by url`() {
        val a = manifest(url = "Example-1.2.3.AppImage", sha512 = "AAAA==", size = 111)
        val aAgain = manifest(url = "Example-1.2.3.AppImage", sha512 = "AAAA==", size = 111)

        val merged = UpdateYmlMerger.merge(listOf(a, aAgain))

        assertEquals(listOf("Example-1.2.3.AppImage"), urls(merged))
    }

    @Test
    fun `single manifest round-trips its entry`() {
        val deb = manifest(url = "example_1.2.3_amd64.deb", sha512 = "BBBB==", size = 222)

        val merged = UpdateYmlMerger.merge(listOf(deb))

        assertEquals(listOf("example_1.2.3_amd64.deb"), urls(merged))
        assertEquals("BBBB==", fieldOf(merged, "example_1.2.3_amd64.deb", "sha512"))
        assertEquals("222", fieldOf(merged, "example_1.2.3_amd64.deb", "size"))
    }

    @Test
    fun `empty input is rejected`() {
        try {
            UpdateYmlMerger.merge(emptyList())
            fail("Expected merge(emptyList()) to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
