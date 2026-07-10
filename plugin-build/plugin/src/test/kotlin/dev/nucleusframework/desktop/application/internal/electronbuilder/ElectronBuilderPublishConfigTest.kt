package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.PublishSettings
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `publishAutoUpdate: false` switch the per-format manifest fix relies on: electron-builder
 * must always stop uploading the S3 `<channel><osSuffix>.yml` (so the plugin publishes a single merged
 * manifest), while artifacts and other providers are untouched.
 */
class ElectronBuilderPublishConfigTest {
    private fun publishSettings(): PublishSettings =
        ProjectBuilder.builder().build().objects.newInstance(PublishSettings::class.java)

    private fun render(publish: PublishSettings): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generatePublishConfig(yaml, publish)
        return yaml.toString()
    }

    @Test
    fun `s3 publish always emits publishAutoUpdate false`() {
        val publish = publishSettings()
        publish.s3.enabled = true
        publish.s3.bucket = "my-bucket"

        val yaml = render(publish)

        assertTrue(yaml, yaml.contains("- provider: s3"))
        assertTrue(yaml, yaml.contains("publishAutoUpdate: false"))
    }

    @Test
    fun `github-only publish never emits publishAutoUpdate`() {
        val publish = publishSettings()
        publish.github.enabled = true
        publish.github.owner = "owner"
        publish.github.repo = "repo"

        val yaml = render(publish)

        assertTrue(yaml, yaml.contains("- provider: github"))
        assertFalse(yaml, yaml.contains("publishAutoUpdate"))
    }

    @Test
    fun `mixed providers suppress only the s3 manifest`() {
        val publish = publishSettings()
        publish.github.enabled = true
        publish.github.owner = "owner"
        publish.github.repo = "repo"
        publish.s3.enabled = true
        publish.s3.bucket = "my-bucket"

        val yaml = render(publish)

        // publishAutoUpdate: false appears exactly once (under the s3 entry, not the github entry).
        assertTrue(yaml, yaml.contains("- provider: github"))
        assertTrue(yaml, yaml.contains("- provider: s3"))
        assertEquals(1, Regex("publishAutoUpdate: false").findAll(yaml).count())
    }
}
