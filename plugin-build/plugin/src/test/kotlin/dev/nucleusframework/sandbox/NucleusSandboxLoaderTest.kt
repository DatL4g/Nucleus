/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.sandbox

import dev.nucleusframework.desktop.application.internal.SandboxMarkers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests the runtime shim's resolution logic ([NucleusSandboxLoader.resolveBundled]) without
 * invoking `System.load` (which would require a real loadable native library). The shim ships as
 * an embedded JAR resource and is added to the test classpath via the `sandboxShim` source set.
 */
class NucleusSandboxLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var savedManifestProp: String? = null
    private var savedLibPath: String? = null
    private var savedAppResources: String? = null

    @Before
    fun saveProps() {
        savedManifestProp = System.getProperty(NucleusSandboxLoader.MANIFEST_PATH_PROPERTY)
        savedLibPath = System.getProperty("java.library.path")
        savedAppResources = System.getProperty(NucleusSandboxLoader.APP_RESOURCES_DIR_PROPERTY)
    }

    @After
    fun restoreProps() {
        restore(NucleusSandboxLoader.MANIFEST_PATH_PROPERTY, savedManifestProp)
        restore("java.library.path", savedLibPath)
        restore(NucleusSandboxLoader.APP_RESOURCES_DIR_PROPERTY, savedAppResources)
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    @Test
    fun `marker path resolves to the signed bundled copy`() {
        val bundleDir = tmp.newFolder("bundle")
        // The real bundled library (any bytes — resolveBundled does not load it).
        val bundled = File(bundleDir, "libfoo.dylib").apply { writeBytes(ByteArray(16)) }

        // A marker temp file (what the third-party loader would extract).
        val markerBytes = SandboxMarkers.markerBytes("input-abcdef.jar", "jni/aarch64/libfoo.dylib")
        val markerFile = tmp.newFile("marker.tmp").apply { writeBytes(markerBytes) }
        val sha = SandboxMarkers.sha256Hex(markerBytes)

        // Manifest mapping sha -> bundled filename, placed in the bundle dir.
        val manifest = File(bundleDir, SandboxMarkers.MANIFEST_FILENAME).apply {
            writeText("$sha=libfoo.dylib\n")
        }

        System.setProperty(NucleusSandboxLoader.MANIFEST_PATH_PROPERTY, manifest.absolutePath)
        System.setProperty("java.library.path", bundleDir.absolutePath)

        val resolved = NucleusSandboxLoader.resolveBundled(markerFile.absolutePath)
        assertEquals(bundled.absoluteFile, resolved!!.absoluteFile)
    }

    @Test
    fun `unknown path falls through to null passthrough`() {
        val bundleDir = tmp.newFolder("bundle")
        val manifest = File(bundleDir, SandboxMarkers.MANIFEST_FILENAME).apply {
            writeText("deadbeef=libfoo.dylib\n")
        }
        val other = tmp.newFile("other.tmp").apply { writeBytes(ByteArray(8)) }

        System.setProperty(NucleusSandboxLoader.MANIFEST_PATH_PROPERTY, manifest.absolutePath)
        System.setProperty("java.library.path", bundleDir.absolutePath)

        assertNull(NucleusSandboxLoader.resolveBundled(other.absolutePath))
    }

    @Test
    fun `missing manifest falls through to null`() {
        val other = tmp.newFile("x.tmp").apply { writeBytes(ByteArray(8)) }
        System.clearProperty(NucleusSandboxLoader.MANIFEST_PATH_PROPERTY)
        System.setProperty("java.library.path", tmp.root.absolutePath)
        assertNull(NucleusSandboxLoader.resolveBundled(other.absolutePath))
    }

    @Test
    fun `nonexistent requested path returns null without throwing`() {
        assertNull(NucleusSandboxLoader.resolveBundled("/does/not/exist/libfoo.dylib"))
        assertNull(NucleusSandboxLoader.resolveBundled(null))
    }
}