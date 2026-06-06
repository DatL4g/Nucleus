package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_gl"

/**
 * JNI bridge to the WGL helper that turns a Tao HWND into an OpenGL-rendering
 * surface usable from Skiko. Windows-only counterpart of [NativeMetalBridge].
 *
 * The GL context is bound per-thread (`wglMakeCurrent`). Rendering (Skia
 * commands) happens on the event-loop thread; presentation (`SwapBuffers`,
 * which blocks for vsync) is handed to a dedicated swap thread. The two never
 * hold the context at the same time: the render thread calls
 * [nativeReleaseCurrent] before signalling the swap thread, which then
 * re-binds via [nativeMakeCurrent], presents, and releases again.
 */
internal object NativeTaoGlBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoGlBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Creates an input-transparent render-surface child HWND covering the
     * window's client area, sets the pixel format on it, creates a 3.3
     * compatibility GL context and makes it current on the calling thread.
     * Returns an opaque attachment handle, or 0 on failure.
     *
     * Rendering goes through a child (not the Tao HWND itself) to dodge an
     * Intel driver bug on Windows 10: with the client area extended into the
     * caption via WM_NCCALCSIZE, SwapBuffers on the top-level HWND blits the
     * buffer shifted down by the theoretical caption height (black band +
     * visually miscalibrated pointer). `NUCLEUS_TAO_WIN_GL_DIRECT=1` restores
     * the old direct-on-HWND path.
     */
    @JvmStatic
    external fun nativeAttach(hwnd: Long): Long

    @JvmStatic
    external fun nativeDetach(handle: Long)

    /** Re-binds the GL context on the current thread. Defensive — `attach`
     * already makes it current. Also used by the swap thread to acquire the
     * context before [nativePresent]. */
    @JvmStatic
    external fun nativeMakeCurrent(handle: Long)

    /** Releases the GL context from the current thread (`wglMakeCurrent(NULL,
     * NULL)`). The render thread calls this after `flushAndSubmit` so the swap
     * thread can bind the same context for [nativePresent]. */
    @JvmStatic
    external fun nativeReleaseCurrent(handle: Long)

    /** Stores the new dimensions and updates the GL viewport. Call on resize
     * or scale-factor change before the next render. */
    @JvmStatic
    external fun nativeResize(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        scale: Float,
    )

    /** Pumps the back-buffer to screen via `SwapBuffers`. Must be invoked
     * **after** `Surface.flushAndSubmit`. */
    @JvmStatic
    external fun nativePresent(handle: Long)

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int
}
