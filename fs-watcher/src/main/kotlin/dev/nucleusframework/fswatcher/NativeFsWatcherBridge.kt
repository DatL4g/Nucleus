package dev.nucleusframework.fswatcher

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private const val LIBRARY_NAME = "nucleus_fs_watcher"
internal const val BACKEND_MODE_NATIVE = 1
internal const val BACKEND_MODE_POLLING = 2
internal const val DELIVERY_MODE_RAW = 1
internal const val DELIVERY_MODE_DEBOUNCED = 2

@Suppress("TooManyFunctions")
internal object NativeFsWatcherBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeFsWatcherBridge::class.java)
    private val sinks = ConcurrentHashMap<Long, NativeFsWatcherSink>()
    private val callbackObservers = ConcurrentHashMap<Long, NativeFsWatcherCallbackObserver>()

    @Volatile
    private var nativeCreateInterceptor: NativeCreateInterceptor? = null

    @Volatile
    private var nativeWatchInterceptor: NativeWatchInterceptor? = null

    val isLoaded: Boolean
        get() = loaded

    internal fun registerSink(
        watcherHandle: Long,
        sink: NativeFsWatcherSink,
    ) {
        sinks[watcherHandle] = sink
    }

    internal fun unregisterSink(watcherHandle: Long) {
        sinks.remove(watcherHandle)
        callbackObservers.remove(watcherHandle)
    }

    internal fun setCallbackObserverForTesting(
        watcherHandle: Long,
        observer: NativeFsWatcherCallbackObserver?,
    ) {
        if (observer == null) {
            callbackObservers.remove(watcherHandle)
        } else {
            callbackObservers[watcherHandle] = observer
        }
    }

    @JvmStatic
    external fun nativeIsSupported(): Boolean

    internal fun setNativeCreateInterceptorForTesting(interceptor: NativeCreateInterceptor?) {
        nativeCreateInterceptor = interceptor
    }

    internal fun createWatcher(
        followSymlinks: Boolean,
        backendMode: Int = BACKEND_MODE_NATIVE,
        deliveryMode: Int,
        debounceWindowMillis: Long,
        pollIntervalMillis: Long = 0L,
        compareContents: Boolean = false,
    ): Long =
        nativeCreateInterceptor?.invoke(
            followSymlinks,
            backendMode,
            deliveryMode,
            debounceWindowMillis,
            pollIntervalMillis,
            compareContents,
        ) ?: nativeCreate(
            followSymlinks = followSymlinks,
            backendMode = backendMode,
            deliveryMode = deliveryMode,
            debounceWindowMillis = debounceWindowMillis,
            pollIntervalMillis = pollIntervalMillis,
            compareContents = compareContents,
        )

    @JvmStatic
    external fun nativeCreate(
        followSymlinks: Boolean,
        backendMode: Int,
        deliveryMode: Int,
        debounceWindowMillis: Long,
        pollIntervalMillis: Long,
        compareContents: Boolean,
    ): Long

    @JvmStatic
    external fun nativeClose(watcherHandle: Long)

    internal fun setNativeWatchInterceptorForTesting(interceptor: NativeWatchInterceptor?) {
        nativeWatchInterceptor = interceptor
    }

    internal fun watchRegistration(
        watcherHandle: Long,
        registrationId: Long,
        path: String,
        recursive: Boolean,
        name: String?,
    ): Boolean =
        nativeWatchInterceptor?.invoke(
            watcherHandle,
            registrationId,
            path,
            recursive,
            name,
        ) ?: nativeWatchImpl(
            watcherHandle,
            registrationId,
            path,
            recursive,
            name,
        )

    @JvmStatic
    external fun nativeWatch(
        watcherHandle: Long,
        registrationId: Long,
        path: String,
        recursive: Boolean,
        name: String?,
    ): Boolean

    private fun nativeWatchImpl(
        watcherHandle: Long,
        registrationId: Long,
        path: String,
        recursive: Boolean,
        name: String?,
    ): Boolean =
        nativeWatch(
            watcherHandle = watcherHandle,
            registrationId = registrationId,
            path = path,
            recursive = recursive,
            name = name,
        )

    @JvmStatic
    fun unwatchRegistration(
        watcherHandle: Long,
        registrationId: Long,
    ) {
        nativeUnwatch(watcherHandle, registrationId)
    }

    // Temporary native-side test probes.
    // These exist only to exercise Rust liveness/path-matching gates from JVM tests
    // without introducing a separate native test artifact pipeline.
    // Moved probes stay narrow as well: native validation only checks liveness and
    // requires both transport endpoints; source routing remains Kotlin-owned.
    // Keep them scoped to the existing tests and do not expand them into runtime API.
    internal fun debugEmitPathEventForTesting(
        watcherHandle: Long,
        originNativeRegistrationId: Long,
        eventKind: Int,
        path: Path,
        secondaryPath: Path? = null,
        needsRescan: Boolean = false,
        isDirectory: Int = -1,
    ): Boolean =
        nativeDebugEmitPathEvent(
            watcherHandle = watcherHandle,
            originNativeRegistrationId = originNativeRegistrationId,
            eventKind = eventKind,
            path = path.toString(),
            secondaryPath = secondaryPath?.toString(),
            needsRescan = needsRescan,
            isDirectory = isDirectory,
        )

    internal fun debugEmitPathErrorForTesting(
        watcherHandle: Long,
        originNativeRegistrationId: Long,
        message: String,
        recoverable: Boolean,
        path: Path,
    ): Boolean =
        nativeDebugEmitPathError(
            watcherHandle = watcherHandle,
            originNativeRegistrationId = originNativeRegistrationId,
            message = message,
            recoverable = recoverable,
            path = path.toString(),
        )

    internal fun debugEmitPathlessErrorForTesting(
        watcherHandle: Long,
        originNativeRegistrationId: Long,
        message: String,
        recoverable: Boolean,
    ): Boolean =
        nativeDebugEmitPathlessError(
            watcherHandle = watcherHandle,
            originNativeRegistrationId = originNativeRegistrationId,
            message = message,
            recoverable = recoverable,
        )

    @JvmStatic
    private external fun nativeUnwatch(
        watcherHandle: Long,
        registrationId: Long,
    )

    @JvmStatic
    private external fun nativeDebugEmitPathEvent(
        watcherHandle: Long,
        originNativeRegistrationId: Long,
        eventKind: Int,
        path: String,
        secondaryPath: String?,
        needsRescan: Boolean,
        isDirectory: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeDebugEmitPathError(
        watcherHandle: Long,
        originNativeRegistrationId: Long,
        message: String,
        recoverable: Boolean,
        path: String,
    ): Boolean

    @JvmStatic
    private external fun nativeDebugEmitPathlessError(
        watcherHandle: Long,
        originNativeRegistrationId: Long,
        message: String,
        recoverable: Boolean,
    ): Boolean

    @JvmStatic
    fun onNativeEvent(
        watcherHandle: Long,
        registrationId: Long,
        originNativeRegistrationId: Long,
        eventKind: Int,
        path: String?,
        secondaryPath: String?,
        needsRescan: Boolean,
        isDirectory: Int,
    ) {
        val payload =
            NativeFsWatchEventPayload(
                watcherHandle = watcherHandle,
                registrationId = registrationId,
                originNativeRegistrationId = originNativeRegistrationId.nullIfWatcherLevel(),
                eventKind = eventKind,
                path = path?.let(Path::of),
                secondaryPath = secondaryPath?.let(Path::of),
                needsRescan = needsRescan,
                isDirectory =
                    when (isDirectory) {
                        1 -> true
                        0 -> false
                        else -> null
                    },
            )
        if (payload.isMalformedWatcherLevelPathCallback()) {
            callbackObservers[watcherHandle]?.onRejectedEvent(payload)
            return
        }
        if (payload.isMalformedMovedCallback()) {
            callbackObservers[watcherHandle]?.onRejectedEvent(payload)
            return
        }
        callbackObservers[watcherHandle]?.onAcceptedEvent(payload)
        sinks[watcherHandle]?.emitEvent(payload)
    }

    @JvmStatic
    fun onNativeError(
        watcherHandle: Long,
        registrationId: Long,
        originNativeRegistrationId: Long,
        message: String,
        recoverable: Boolean,
        path: String?,
    ) {
        val payload =
            NativeFsWatchErrorPayload(
                watcherHandle = watcherHandle,
                registrationId = registrationId,
                originNativeRegistrationId = originNativeRegistrationId.nullIfWatcherLevel(),
                message = message,
                recoverable = recoverable,
                path = path?.let(Path::of),
            )
        if (payload.isMalformedWatcherLevelPathCallback()) {
            callbackObservers[watcherHandle]?.onRejectedError(payload)
            return
        }
        callbackObservers[watcherHandle]?.onAcceptedError(payload)
        sinks[watcherHandle]?.emitError(payload)
    }
}

internal typealias NativeCreateInterceptor =
    (
        followSymlinks: Boolean,
        backendMode: Int,
        deliveryMode: Int,
        debounceWindowMillis: Long,
        pollIntervalMillis: Long,
        compareContents: Boolean,
    ) -> Long

internal typealias NativeWatchInterceptor =
    (
        watcherHandle: Long,
        registrationId: Long,
        path: String,
        recursive: Boolean,
        name: String?,
    ) -> Boolean

internal interface NativeFsWatcherCallbackObserver {
    fun onAcceptedEvent(payload: NativeFsWatchEventPayload) {}

    fun onRejectedEvent(payload: NativeFsWatchEventPayload) {}

    fun onAcceptedError(payload: NativeFsWatchErrorPayload) {}

    fun onRejectedError(payload: NativeFsWatchErrorPayload) {}
}
