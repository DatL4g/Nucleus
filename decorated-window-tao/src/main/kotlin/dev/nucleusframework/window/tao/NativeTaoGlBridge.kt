package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_gl"

/**
 * JNI bridge to the WGL helper that turns a Tao HWND into an OpenGL-rendering
 * surface usable from Skiko. Windows-only counterpart of [NativeMetalBridge].
 *
 * All methods must be invoked from the thread that owns the Tao event loop
 * (the OpenGL context is bound there and `wglMakeCurrent` is per-thread).
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
     * already makes it current. */
    @JvmStatic
    external fun nativeMakeCurrent(handle: Long)

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
