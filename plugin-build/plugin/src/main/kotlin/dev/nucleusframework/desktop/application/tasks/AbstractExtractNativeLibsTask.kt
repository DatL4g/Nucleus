/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeArch
import dev.nucleusframework.desktop.application.internal.NativeLibArchDetector.NativeOs
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts native libraries from dependency JARs and places them in an output directory
 * so they can be included in app resources (and signed automatically by the packaging plugin).
 *
 * This is required for macOS App Sandbox compatibility: sandboxed apps cannot load
 * unsigned native code extracted to temp directories at runtime.
 */
@DisableCachingByDefault(because = "Extracts native libs from JARs; output depends on JAR content order")
abstract class AbstractExtractNativeLibsTask : AbstractNucleusTask() {
    private companion object {
        /** Bytes read from each native lib to reach the PE/ELF/Mach-O machine field for arch detection. */
        const val HEADER_BYTES = 4096
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputJars: ConfigurableFileCollection

    @get:Input
    abstract val targetOs: Property<String>

    @get:Input
    abstract val targetArch: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @Suppress("NestedBlockDepth")
    @TaskAction
    fun extract() {
        val outDir = outputDir.get().asFile
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
        outDir.mkdirs()

        val expectedOs = mapOs(targetOs.get()) ?: return
        val expectedArch = mapArch(targetArch.get()) ?: return

        val extractedFiles = mutableSetOf<String>()

        for (jarFile in inputJars.files) {
            if (!jarFile.name.endsWith(".jar") || !jarFile.exists()) continue

            ZipInputStream(BufferedInputStream(jarFile.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && NativeLibArchDetector.isNativeLib(entry.name)) {
                        val info = detectInfo(entry.name, zis)
                        if (shouldExtract(info, expectedOs, expectedArch)) {
                            val fileName = entry.name.substringAfterLast('/')
                            // Always flatten to the output root. We already filter by
                            // target OS/arch so there are no naming conflicts, and
                            // flattening is required for:
                            //  1. macOS universal builds – lipo needs the same relative
                            //     path in both the arm64 and x64 .app bundles.
                            //  2. JNA boot library loading – jna.boot.library.path
                            //     looks directly in the listed directories.
                            //  3. Simplicity – a single java.library.path entry is
                            //     enough for all native libs.
                            val relativePath = fileName
                            if (fileName in extractedFiles) {
                                logger.warn(
                                    "Sandboxing: skipping duplicate native lib" +
                                        " '{}' from {} (already extracted from another JAR)",
                                    fileName,
                                    jarFile.name,
                                )
                            } else {
                                val destFile = outDir.resolve(relativePath)
                                destFile.parentFile.mkdirs()
                                // Re-open the JAR to read the entry bytes
                                // (we may have consumed some for header detection)
                                extractEntry(jarFile, entry.name, destFile)
                                extractedFiles.add(fileName)
                                logger.lifecycle("Sandboxing: extracted '{}' from {}", relativePath, jarFile.name)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        if (extractedFiles.isEmpty()) {
            logger.lifecycle("Sandboxing: no native libraries found to extract from dependency JARs")
        } else {
            logger.lifecycle("Sandboxing: extracted {} native lib(s) to {}", extractedFiles.size, outDir)
        }
    }

    private fun detectInfo(
        entryName: String,
        zis: ZipInputStream,
    ): NativeLibArchDetector.NativeInfo {
        val pathInfo = NativeLibArchDetector.detectFromPath(entryName)
        // Path detection is authoritative once it fully resolves the platform.
        if (pathInfo.os != NativeOs.UNKNOWN && pathInfo.arch != NativeArch.UNKNOWN) {
            return pathInfo
        }
        // Otherwise complement — not replace — the path result with binary header detection.
        // Layouts like zstd-kmp's "jni/aarch64/foo.dll" encode the arch in the path but carry
        // no OS token, while the PE/ELF/Mach-O header supplies the OS. Discarding the
        // path-detected arch here previously let an ARM64 .dll be extracted for an x64 target
        // (its arch reads as UNKNOWN and passes the filter), see issue #399.
        val headerInfo = detectFromHeaderBytes(zis)
        return NativeLibArchDetector.NativeInfo(
            os = if (pathInfo.os != NativeOs.UNKNOWN) pathInfo.os else headerInfo.os,
            arch = if (pathInfo.arch != NativeArch.UNKNOWN) pathInfo.arch else headerInfo.arch,
        )
    }

    private fun detectFromHeaderBytes(zis: ZipInputStream): NativeLibArchDetector.NativeInfo {
        // Must be large enough to reach the PE machine field: it sits at e_lfanew (a 4-byte
        // value at offset 0x3C) + 4, which for real DLLs is routinely past the first 64 bytes.
        val header = ByteArray(HEADER_BYTES)
        val bytesRead = readFully(zis, header)
        if (bytesRead <= 0) {
            return NativeLibArchDetector.NativeInfo(NativeOs.UNKNOWN, NativeArch.UNKNOWN)
        }
        return NativeLibArchDetector.detectFromHeader(header.copyOf(bytesRead))
    }

    private fun shouldExtract(
        info: NativeLibArchDetector.NativeInfo,
        expectedOs: NativeOs,
        expectedArch: NativeArch,
    ): Boolean {
        // Must match target OS
        if (info.os != expectedOs && info.os != NativeOs.UNKNOWN) return false
        // Must match target arch (or be universal/unknown)
        if (info.arch != expectedArch &&
            info.arch != NativeArch.UNIVERSAL &&
            info.arch != NativeArch.UNKNOWN
        ) {
            return false
        }
        // Reject exotic OS/arch
        if (info.os == NativeOs.OTHER || info.arch == NativeArch.OTHER) return false
        // If both OS and arch are unknown, we can't determine — skip to be safe
        if (info.os == NativeOs.UNKNOWN && info.arch == NativeArch.UNKNOWN) return false
        return true
    }

    @Suppress("NestedBlockDepth")
    private fun extractEntry(
        jarFile: java.io.File,
        entryName: String,
        destFile: java.io.File,
    ) {
        ZipInputStream(BufferedInputStream(jarFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    destFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    return
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun mapOs(os: String): NativeOs? =
        when (os) {
            "windows" -> NativeOs.WINDOWS
            "linux" -> NativeOs.LINUX
            "macos" -> NativeOs.MACOS
            else -> null
        }

    private fun mapArch(arch: String): NativeArch? =
        when (arch) {
            "x64" -> NativeArch.X64
            "arm64" -> NativeArch.ARM64
            else -> null
        }

    private fun readFully(
        input: java.io.InputStream,
        buffer: ByteArray,
    ): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }
}
