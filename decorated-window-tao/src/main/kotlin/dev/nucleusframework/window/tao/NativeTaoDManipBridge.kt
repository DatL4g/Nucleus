package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the DirectManipulation helper compiled into
 * `nucleus_tao_windows_deco.dll` (see `nucleus_tao_dmanip.cpp`).
 *
 * DirectManipulation is the OS-computed precision-touchpad pipeline Chrome
 * uses on Windows: pan, pinch and post-lift inertia are calculated by
 * Windows itself and reported as content-transform deltas. Contacts owned
 * by the viewport stop producing synthesized `WM_MOUSEWHEEL`, so the wheel
 * path automatically remains mice-only while this is attached.
 *
 * Poll model: the native side accumulates deltas; the render loop drains
 * them once per frame via [nativeFetch]. All calls must run on the Tao
 * event-loop thread.
 */
internal object NativeTaoDManipBridge {
    private const val LIBRARY_NAME = "nucleus_tao_windows_deco"

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoDManipBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /** Viewport idle — no gesture, no inertia. */
    const val STATUS_IDLE: Int = 0

    /** Fingers on the pad, manipulation running. */
    const val STATUS_RUNNING: Int = 1

    /** Fingers lifted, OS inertia still producing deltas. */
    const val STATUS_INERTIA: Int = 2

    /** Not attached (or fetch called with a bad buffer). */
    const val STATUS_UNAVAILABLE: Int = -1

    /**
     * Registers a DirectManipulation viewport on [hwnd]. Returns false when
     * the OS can't provide one (pre-Win8, no pointer API, COM failure) —
     * callers keep the wheel-path fallback in that case.
     */
    @JvmStatic
    external fun nativeAttach(hwnd: Long): Boolean

    @JvmStatic
    external fun nativeDetach(hwnd: Long)

    /**
     * Drains accumulated manipulation deltas into [out] (size >= 3):
     * `out[0]`/`out[1]` = pan delta X/Y in physical px since the last fetch,
     * `out[2]` = multiplicative scale delta (1.0 = no zoom). Returns the
     * current `STATUS_*`.
     */
    @JvmStatic
    external fun nativeFetch(
        hwnd: Long,
        out: FloatArray,
    ): Int
}
