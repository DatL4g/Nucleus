/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.sampletao;

import com.squareup.zstd.Zstd;
import com.squareup.zstd.ZstdCompressor;
import com.squareup.zstd.ZstdDecompressor;

/**
 * Issue #317 end-to-end probe: drives the extract-and-load zstd-kmp native library through the
 * sandboxed packaging pipeline.
 *
 * {@link Zstd#loadNativeLibrary()} is Kotlin-{@code internal} (public at the JVM level), so it is
 * unreachable from Kotlin in another module — hence this Java helper. It extracts
 * {@code libzstd-kmp.<ext>} from the JAR to a temp file and calls {@code System.load(temp)}; that
 * is the exact call site the sandboxed pipeline rewrites to {@code NucleusSandboxLoader}, which
 * loads the signed bundled copy via the marker manifest. A successful roundtrip proves the whole
 * chain works with no upstream library changes.
 */
public final class ZstdSandboxProbe {
    private ZstdSandboxProbe() {}

    /** Runs a minimal compress/decompress roundtrip; returns a short status string. */
    public static String roundtrip() {
        try {
            Zstd.loadNativeLibrary();
            byte[] input = "hello zstd sandbox roundtrip 0123456789".getBytes();
            byte[] compressed = new byte[input.length * 2 + 128];
            int compLen;
            try (ZstdCompressor c = Zstd.zstdCompressor()) {
                c.compressStream2(compressed, compressed.length, 0, input, input.length, 0, Zstd.ZSTD_e_end);
                compLen = c.outputBytesProcessed;
            }
            byte[] decompressed = new byte[input.length * 4];
            int decLen;
            try (ZstdDecompressor d = Zstd.zstdDecompressor()) {
                d.decompressStream(decompressed, decompressed.length, 0, compressed, compLen, 0);
                decLen = d.outputBytesProcessed;
            }
            byte[] out = new byte[decLen];
            System.arraycopy(decompressed, 0, out, 0, decLen);
            return "roundtrip=" + java.util.Arrays.equals(input, out) + " compLen=" + compLen + " decLen=" + decLen;
        } catch (Throwable t) {
            return "FAILED: " + t.getClass().getName() + ": " + t.getMessage();
        }
    }
}