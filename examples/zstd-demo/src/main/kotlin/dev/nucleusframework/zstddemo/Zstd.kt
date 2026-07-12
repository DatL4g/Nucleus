package dev.nucleusframework.zstddemo

import com.squareup.zstd.ZSTD_e_continue
import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.zstdCompressor
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streaming zstd compression built on the zstd-kmp streaming API.
 *
 * The zstd-kmp JVM backend loads its native library lazily the first time a
 * compressor/decompressor is created (via `System.load` on a temp-extracted
 * copy). In a sandboxed store bundle (macOS Pkg / Windows AppX / Linux Flatpak)
 * that extract-and-load pattern is exactly what the Nucleus packaging pipeline
 * rewrites — this demo exercises the whole redirect end to end.
 */
private const val CHUNK = 1 shl 16 // 64 KiB streaming window

/** Compresses [input] into a `.zst` file, streaming in 64 KiB chunks. Returns the produced file. */
fun compressFile(
    input: File,
    output: File,
): File {
    input.inputStream().buffered().use { source ->
        output.outputStream().buffered().use { sink ->
            compressStream(source, sink)
        }
    }
    return output
}

/**
 * Zips a directory tree into a single `.zip` then zstd-compresses it into [output]
 * (a `.zip.zst`), streaming throughout so large trees never materialise in memory.
 */
fun compressDirectory(
    dir: File,
    output: File,
): File {
    val tempZip = File.createTempFile("zstd-demo-", ".zip")
    try {
        ZipOutputStream(tempZip.outputStream().buffered()).use { zip ->
            val root = dir.toPath()
            dir
                .walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = root.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        tempZip.inputStream().buffered().use { source ->
            output.outputStream().buffered().use { sink ->
                compressStream(source, sink)
            }
        }
    } finally {
        tempZip.delete()
    }
    return output
}

/**
 * Drives the zstd streaming compressor, mirroring the canonical libzstd loop: read [source] in
 * chunks and, per chunk, call `compressStream2` until the input is consumed (non-final chunks) or
 * the frame is fully flushed (final chunk, signalled by a 0 return value).
 */
private fun compressStream(
    source: InputStream,
    sink: OutputStream,
) {
    // Creating the compressor triggers the native library load on first use.
    zstdCompressor().use { compressor ->
        val inBuf = ByteArray(CHUNK)
        val outBuf = ByteArray(CHUNK)
        while (true) {
            val read = source.read(inBuf)
            val lastChunk = read <= 0
            val inputLen = if (lastChunk) 0 else read
            val mode = if (lastChunk) ZSTD_e_end else ZSTD_e_continue

            var inputConsumed = 0
            var finished: Boolean
            do {
                val remaining =
                    compressor.compressStream2(outBuf, outBuf.size, 0, inBuf, inputLen, inputConsumed, mode)
                sink.write(outBuf, 0, compressor.outputBytesProcessed)
                inputConsumed += compressor.inputBytesProcessed
                finished = if (lastChunk) remaining == 0L else inputConsumed >= inputLen
            } while (!finished)

            if (lastChunk) break
        }
    }
}
