package dev.nucleusframework.fswatcher

import java.nio.file.Path

data class FsWatchSource(
    val root: Path,
    val recursive: Boolean,
    val name: String? = null,
)

sealed interface FsWatchEvent {
    val source: FsWatchSource?
    val needsRescan: Boolean

    data class Created(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Modified(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Removed(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Moved(
        val from: Path,
        val to: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Overflow(
        override val source: FsWatchSource? = null,
        override val needsRescan: Boolean = true,
    ) : FsWatchEvent

    data class Other(
        val paths: List<Path> = emptyList(),
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent
}
