package dev.nucleusframework.fswatcher

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal const val WATCHER_LEVEL_REGISTRATION_ID: Long = 0L

internal const val EVENT_KIND_CREATED: Int = 1
internal const val EVENT_KIND_MODIFIED: Int = 2
internal const val EVENT_KIND_REMOVED: Int = 3
internal const val EVENT_KIND_OVERFLOW: Int = 4
internal const val EVENT_KIND_MOVED: Int = 5

private val nextRegistrationId = AtomicLong(1)

internal fun nextNativeFsWatcherRegistrationId(): Long = nextRegistrationId.getAndIncrement()

// The native bridge transports "no origin" as watcher-level registration id 0.
// Real native registration ids start at 1, so decoding 0 back to null is safe.
internal fun Long.nullIfWatcherLevel(): Long? = if (this == WATCHER_LEVEL_REGISTRATION_ID) null else this

internal data class NativeFsWatchEventPayload(
    val watcherHandle: Long,
    val registrationId: Long,
    val originNativeRegistrationId: Long?,
    val eventKind: Int,
    val path: Path?,
    val secondaryPath: Path?,
    val needsRescan: Boolean,
    val isDirectory: Boolean?,
)

internal fun NativeFsWatchEventPayload.isMalformedWatcherLevelPathCallback(): Boolean =
    registrationId == WATCHER_LEVEL_REGISTRATION_ID &&
        path != null &&
        eventKind != EVENT_KIND_OVERFLOW &&
        originNativeRegistrationId == null

internal fun NativeFsWatchEventPayload.isMalformedMovedCallback(): Boolean =
    eventKind == EVENT_KIND_MOVED && (path == null || secondaryPath == null)

internal data class NativeFsWatchErrorPayload(
    val watcherHandle: Long,
    val registrationId: Long,
    val originNativeRegistrationId: Long?,
    val message: String,
    val recoverable: Boolean,
    val path: Path?,
)

internal fun NativeFsWatchErrorPayload.isMalformedWatcherLevelPathCallback(): Boolean =
    registrationId == WATCHER_LEVEL_REGISTRATION_ID &&
        path != null &&
        originNativeRegistrationId == null

internal interface NativeFsWatcherSink {
    fun emitEvent(payload: NativeFsWatchEventPayload)

    fun emitError(payload: NativeFsWatchErrorPayload)
}
