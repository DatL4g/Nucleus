/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

/**
 * Merges several electron-builder auto-update manifests into a single manifest whose `files:` list
 * is the union of all inputs.
 *
 * Nucleus packages each Linux [dev.nucleusframework.desktop.application.dsl.TargetFormat]
 * (Deb, AppImage, …) with its own electron-builder invocation, and electron-builder writes a
 * single-artifact `<channel>-linux.yml` per run. Because the manifest name is keyed only on the OS
 * (see `TargetFormat.updateYmlFilename`, which returns `<channel>-linux.yml` for *every* Linux
 * format), those per-format manifests collide on one key — and whichever format is published last
 * (the Deb run sorts after the AppImage run) overwrites the others, leaving the manifest with only
 * the `.deb`. An AppImage client then fetches `<channel>-linux.yml`, finds no AppImage entry, and
 * the in-app updater throws `NoMatchingFileException`.
 *
 * Merging the per-format manifests keeps every format's entry, so each client finds its artifact.
 */
internal object UpdateYmlMerger {
    private data class Entry(
        val url: String,
        val sha512: String?,
        val size: String?,
        val blockMapSize: String?,
    )

    private data class ParsedManifest(
        val version: String?,
        val releaseDate: String?,
        val entries: List<Entry>,
    )

    /**
     * Merges [manifests] (raw electron-builder `<channel>-*.yml` contents) into one.
     *
     * The result lists every distinct `files[].url` across all inputs (deduplicated by url, sorted
     * for stable output). `version` is taken from the first manifest that declares one; the
     * top-level `path`/`sha512` mirror the first (sorted) entry, and `releaseDate` is the latest
     * across inputs.
     */
    fun merge(manifests: List<String>): String {
        require(manifests.isNotEmpty()) { "Cannot merge an empty list of manifests" }
        val parsed = manifests.map(::parse)

        val version = parsed.firstNotNullOfOrNull { it.version }
            ?: error("None of the manifests declares a version")

        // Union by url, keeping the first occurrence; then sort for deterministic output.
        val byUrl = LinkedHashMap<String, Entry>()
        for (manifest in parsed) {
            for (entry in manifest.entries) byUrl.putIfAbsent(entry.url, entry)
        }
        val entries = byUrl.values.sortedBy { it.url }
        require(entries.isNotEmpty()) { "None of the manifests lists any files" }

        val releaseDate = parsed.mapNotNull { it.releaseDate }.maxOrNull()
        val first = entries.first()

        return buildString {
            appendLine("version: $version")
            appendLine("files:")
            for (entry in entries) {
                appendLine("  - url: ${entry.url}")
                entry.sha512?.let { appendLine("    sha512: $it") }
                entry.size?.let { appendLine("    size: $it") }
                entry.blockMapSize?.let { appendLine("    blockMapSize: $it") }
            }
            appendLine("path: ${first.url}")
            first.sha512?.let { appendLine("sha512: $it") }
            releaseDate?.let { appendLine("releaseDate: $it") }
        }
    }

    private fun parse(manifest: String): ParsedManifest {
        var version: String? = null
        var releaseDate: String? = null
        val entries = mutableListOf<Entry>()

        var inFiles = false
        var url: String? = null
        var sha512: String? = null
        var size: String? = null
        var blockMapSize: String? = null

        fun flush() {
            val current = url ?: return
            entries += Entry(current, sha512, size, blockMapSize)
            url = null
            sha512 = null
            size = null
            blockMapSize = null
        }

        for (line in manifest.lines()) {
            when {
                line.startsWith("version: ") -> version = line.removePrefix("version: ").trim()
                line.startsWith("releaseDate: ") -> releaseDate = line.removePrefix("releaseDate: ").trim()
                line == "files:" -> inFiles = true
                inFiles && line.startsWith("  - url: ") -> {
                    flush()
                    url = line.removePrefix("  - url: ").trim()
                }
                inFiles && url != null && line.startsWith("    ") -> {
                    val field = line.trim()
                    when {
                        field.startsWith("sha512: ") -> sha512 = field.removePrefix("sha512: ")
                        field.startsWith("size: ") -> size = field.removePrefix("size: ")
                        field.startsWith("blockMapSize: ") -> blockMapSize = field.removePrefix("blockMapSize: ")
                    }
                }
                // A new top-level (column-0) key ends the files section.
                line.isNotBlank() && !line.startsWith(" ") -> {
                    flush()
                    inFiles = false
                }
            }
        }
        flush()
        return ParsedManifest(version, releaseDate, entries)
    }
}
