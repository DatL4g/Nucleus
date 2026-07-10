package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tests for the pure seams of the per-format manifest merge/publish flow (all platforms). */
class UpdateYmlPublishTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- resolvePublishFlag ---

    @Test
    fun `resolvePublishFlag is never when no provider is enabled`() {
        assertEquals(
            "never",
            UpdateYmlPublish.resolvePublishFlag(
                anyProviderEnabled = false,
                envValue = "always",
                propValue = "always",
                dslValue = "onTag",
            ),
        )
    }

    @Test
    fun `resolvePublishFlag prefers env over property over dsl`() {
        assertEquals(
            "envMode",
            UpdateYmlPublish.resolvePublishFlag(true, envValue = "envMode", propValue = "propMode", dslValue = "onTag"),
        )
        assertEquals(
            "propMode",
            UpdateYmlPublish.resolvePublishFlag(true, envValue = " ", propValue = "propMode", dslValue = "onTag"),
        )
        assertEquals(
            "onTag",
            UpdateYmlPublish.resolvePublishFlag(true, envValue = null, propValue = null, dslValue = "onTag"),
        )
    }

    // --- shouldPublish / hasCiTag ---

    @Test
    fun `shouldPublish always and never ignore the environment`() {
        assertTrue(UpdateYmlPublish.shouldPublish("always", emptyMap()))
        assertFalse(UpdateYmlPublish.shouldPublish("never", mapOf("GITHUB_REF" to "refs/tags/v1")))
    }

    @Test
    fun `shouldPublish onTag uploads only when a CI tag is present`() {
        assertFalse(UpdateYmlPublish.shouldPublish("onTag", emptyMap()))
        assertFalse(UpdateYmlPublish.shouldPublish("onTag", mapOf("GITHUB_REF" to "refs/heads/main")))
        assertTrue(UpdateYmlPublish.shouldPublish("onTag", mapOf("GITHUB_REF" to "refs/tags/v1.2.3")))
        assertTrue(UpdateYmlPublish.shouldPublish("onTag", mapOf("CIRCLE_TAG" to "v1.2.3")))
    }

    @Test
    fun `hasCiTag ignores blank values`() {
        assertFalse(UpdateYmlPublish.hasCiTag(mapOf("TRAVIS_TAG" to "", "GITHUB_REF" to null)))
        assertTrue(UpdateYmlPublish.hasCiTag(mapOf("TRAVIS_TAG" to "v9")))
    }

    // --- s3Key ---

    @Test
    fun `s3Key omits an empty prefix and normalizes slashes`() {
        assertEquals("beta-mac.yml", UpdateYmlPublish.s3Key(null, "beta-mac.yml"))
        assertEquals("latest.yml", UpdateYmlPublish.s3Key("  ", "latest.yml"))
        assertEquals("testing/beta-linux.yml", UpdateYmlPublish.s3Key("testing", "beta-linux.yml"))
        assertEquals("testing/beta-linux.yml", UpdateYmlPublish.s3Key("/testing/", "beta-linux.yml"))
    }

    // --- discoverAndMerge ---

    private fun writeManifest(dir: File, name: String, url: String, sha512: String, size: Long) {
        dir.mkdirs()
        File(dir, name).writeText(
            """
            version: 1.2.3
            files:
              - url: $url
                sha512: $sha512
                size: $size
            path: $url
            sha512: $sha512
            releaseDate: '2026-05-29T00:00:00.000Z'
            """.trimIndent() + "\n",
        )
    }

    private fun filesUrls(content: String): List<String> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- url: ") }
            .map { it.removePrefix("- url: ") }
            .toList()

    @Test
    fun `discoverAndMerge unions same-named manifests across format output dirs (linux)`() {
        val appImageDir = tmp.newFolder("AppImage")
        val debDir = tmp.newFolder("deb")
        writeManifest(appImageDir, "beta-linux.yml", "Example-1.2.3.AppImage", "AAAA==", 111)
        writeManifest(debDir, "beta-linux.yml", "example_1.2.3_amd64.deb", "BBBB==", 222)
        // A non-manifest artifact in the dir must be ignored.
        File(debDir, "example_1.2.3_amd64.deb").writeText("binary")

        val merged = UpdateYmlPublish.discoverAndMerge(listOf(appImageDir, debDir))

        assertEquals(1, merged.size)
        assertEquals("beta-linux.yml", merged.single().fileName)
        assertEquals(
            listOf("Example-1.2.3.AppImage", "example_1.2.3_amd64.deb"),
            filesUrls(merged.single().content).sorted(),
        )
    }

    @Test
    fun `discoverAndMerge unions dmg and zip into one mac manifest`() {
        val dmgDir = tmp.newFolder("dmg")
        val zipDir = tmp.newFolder("zip")
        writeManifest(dmgDir, "latest-mac.yml", "Example-1.2.3.dmg", "AA==", 11)
        writeManifest(zipDir, "latest-mac.yml", "Example-1.2.3-mac.zip", "BB==", 22)

        val merged = UpdateYmlPublish.discoverAndMerge(listOf(dmgDir, zipDir))

        assertEquals("latest-mac.yml", merged.single().fileName)
        assertEquals(
            listOf("Example-1.2.3-mac.zip", "Example-1.2.3.dmg"),
            filesUrls(merged.single().content).sorted(),
        )
    }

    @Test
    fun `discoverAndMerge matches windows manifests and ignores builder config files`() {
        val nsisDir = tmp.newFolder("nsis")
        writeManifest(nsisDir, "latest.yml", "Example Setup 1.2.3.exe", "AA==", 11)
        // electron-builder leaves config files behind; these must NOT be treated as update manifests.
        File(nsisDir, "electron-builder.yml").writeText("appId: x")
        File(nsisDir, "builder-debug.yml").writeText("debug: true")

        val merged = UpdateYmlPublish.discoverAndMerge(listOf(nsisDir))

        assertEquals(1, merged.size)
        assertEquals("latest.yml", merged.single().fileName)
    }

    @Test
    fun `discoverAndMerge returns a lone manifest verbatim, preserving unmodeled fields`() {
        val nsisDir = tmp.newFolder("nsis")
        // Includes fields the line-based merger does not model (releaseName, a `packages:` block).
        val original =
            """
            version: 1.2.3
            releaseName: Shiny
            files:
              - url: App-1.2.3.exe
                sha512: AA==
                size: 11
                blockMapSize: 7
            packages:
              x64:
                file: App-1.2.3.exe.blockmap
            path: App-1.2.3.exe
            sha512: AA==
            releaseDate: '2026-05-29T00:00:00.000Z'
            """.trimIndent() + "\n"
        File(nsisDir, "latest.yml").writeText(original)

        val merged = UpdateYmlPublish.discoverAndMerge(listOf(nsisDir))

        // Single source ⇒ byte-for-byte passthrough (not round-tripped through the merger).
        assertEquals(original, merged.single().content)
    }

    @Test
    fun `discoverAndMerge returns empty when no manifests are present`() {
        val empty = tmp.newFolder("empty")
        assertTrue(UpdateYmlPublish.discoverAndMerge(listOf(empty)).isEmpty())
    }

    @Test
    fun `discoverAndMerge groups distinct channels separately`() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        writeManifest(a, "beta-linux.yml", "x.AppImage", "AA==", 1)
        writeManifest(b, "latest-linux.yml", "y.deb", "BB==", 2)

        val merged = UpdateYmlPublish.discoverAndMerge(listOf(a, b))

        assertEquals(setOf("beta-linux.yml", "latest-linux.yml"), merged.map { it.fileName }.toSet())
    }
}
