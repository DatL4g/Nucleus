/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import java.text.Normalizer

/**
 * Last-resort bundle name, used when every candidate sanitizes down to an empty string
 * (e.g. `packageName = "..."`, which `sanitize-filename` strips entirely).
 */
private const val FALLBACK_BUNDLE_NAME = "app"

/**
 * Resolves the macOS `.app` bundle directory name (without the `.app` suffix).
 *
 * The same value must be used by every macOS packaging backend. electron-builder stages the bundle
 * inside a DMG under `${productFilename}.app` while its ZIP target archives the prepackaged
 * directory as-is, so the two artifacts only agree when the prepackaged directory is already named
 * `${productFilename}.app`. Feeding this single resolution into jpackage's output, the GraalVM
 * bundle and electron-builder's `productName` keeps that invariant true by construction — an app
 * installed from the DMG can then be updated from the ZIP and vice versa.
 *
 * The result is normalized to NFD because a DMG carries an HFS+ volume, and HFS+ decomposes every
 * file name it stores. Naming the bundle in NFD up front is what makes the DMG entry and the ZIP
 * entry — which preserves whatever the build produced — byte-identical rather than merely equivalent.
 *
 * Precedence: explicit `macOS.bundleName` > `appName` > `macOS.packageName` / `packageName` >
 * [fallback] (normally the Gradle project name).
 */
internal fun resolveMacBundleName(
    bundleName: String?,
    appName: String?,
    packageName: String?,
    fallback: String,
): String =
    macBundleNameCandidates(bundleName, appName, packageName, fallback)
        .firstNotNullOfOrNull { candidate ->
            sanitizeFileName(Normalizer.normalize(candidate, Normalizer.Form.NFD)).takeIf { it.isNotBlank() }
        }
        ?: FALLBACK_BUNDLE_NAME

/** Bundle name candidates in precedence order, blanks removed. */
private fun macBundleNameCandidates(
    bundleName: String?,
    appName: String?,
    packageName: String?,
    fallback: String,
): List<String> = listOfNotNull(bundleName, appName, packageName, fallback).filter { it.isNotBlank() }

/**
 * Reports configurations where the resolved macOS bundle name is not the one the build script most
 * likely intended, so the ambiguity is settled explicitly via `macOS.bundleName` instead of silently.
 *
 * Two cases are reported:
 *  - `macOS.packageName` is set but loses to `appName`. Before the bundle name was unified, that
 *    property controlled the `.app` directory name for the raw app image, so a project relying on it
 *    would see the bundle renamed.
 *  - the requested name contains characters that are illegal in a filename and had to be stripped.
 */
internal fun macBundleNameWarnings(
    bundleName: String?,
    appName: String?,
    macPackageName: String?,
    resolved: String,
): List<String> =
    buildList {
        val requested = bundleName?.takeIf { it.isNotBlank() } ?: appName?.takeIf { it.isNotBlank() }
        // Compared in NFD: the resolved name is decomposed to match what HFS+ stores, and that
        // difference alone must not be reported as a sanitization.
        if (requested != null && Normalizer.normalize(requested, Normalizer.Form.NFD) != resolved) {
            add(
                "w: macOS bundle name \"$requested\" contains characters that are illegal in a file name; " +
                    "the .app bundle will be named \"$resolved.app\". " +
                    "Set macOS.bundleName to choose the name explicitly.",
            )
        }
        val macName = macPackageName?.takeIf { it.isNotBlank() }
        if (bundleName == null && macName != null && Normalizer.normalize(macName, Normalizer.Form.NFD) != resolved) {
            add(
                "w: macOS.packageName (\"$macName\") no longer names the .app bundle directory; " +
                    "every macOS artifact now ships \"$resolved.app\" (from appName) so the DMG and the ZIP " +
                    "stay interchangeable for auto-update. Set macOS.bundleName = \"$macName\" to keep the " +
                    "previous bundle name.",
            )
        }
    }

// Port of the npm `sanitize-filename` package (v1.6.x), which electron-builder applies to
// `productName` to derive `AppInfo.productFilename`. Keeping the two in sync matters: the DMG target
// names the staged bundle after `productFilename`, so any divergence here reintroduces the very
// desynchronization this resolution exists to prevent.
private val ILLEGAL_CHARS = Regex("""[/?<>\\:*|"]""")
private val CONTROL_CHARS = Regex("[\\x00-\\x1F\\x80-\\x9F]")
private val ONLY_DOTS = Regex("""^\.+$""")
private val WINDOWS_RESERVED = Regex("""^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$""", RegexOption.IGNORE_CASE)
private val WINDOWS_TRAILING = Regex("""[. ]+$""")
private const val MAX_FILE_NAME_BYTES = 255
private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_MARKER = 0x80

/** Kotlin equivalent of electron-builder's `sanitizeFileName`, used to derive `productFilename`. */
internal fun sanitizeFileName(input: String): String {
    val sanitized =
        input
            .replace(ILLEGAL_CHARS, "")
            .replace(CONTROL_CHARS, "")
            .replace(ONLY_DOTS, "")
            .replace(WINDOWS_RESERVED, "")
            .replace(WINDOWS_TRAILING, "")
    return truncateUtf8Bytes(sanitized, MAX_FILE_NAME_BYTES)
}

/** Truncates to at most [maxBytes] UTF-8 bytes without splitting a multi-byte character. */
private fun truncateUtf8Bytes(
    value: String,
    maxBytes: Int,
): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_MARKER) {
        end--
    }
    return String(bytes, 0, end, Charsets.UTF_8)
}
