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
    init {
        // ANGLE (libEGL + libGLESv2) backs the preferred Direct3D-11 render path
        // (nucleus_tao_gl.c tries it before WGL). They ship next to the other
        // Windows native libs but are only present on win32-*; load them by
        // absolute path FIRST (libGLESv2 before libEGL, which depends on it) so
        // the native `LoadLibraryW("libEGL.dll")` resolves the already-loaded
        // module by base name. Best-effort: if absent or non-Windows, the native
        // side simply falls back to WGL.
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            NativeLibraryLoader.load("libGLESv2", NativeTaoGlBridge::class.java)
            NativeLibraryLoader.load("libEGL", NativeTaoGlBridge::class.java)
        }
    }

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

    /**
     * Forces the WGL backend. Fallback used when [nativeAttach] produced an
     * EGL/ANGLE attachment but Skia couldn't build a [org.jetbrains.skia.DirectContext]
     * on it — viability is only known after `makeGLWithInterface`, on the JVM side.
     */
    @JvmStatic
    external fun nativeAttachWgl(hwnd: Long): Long

    /** Backend of an attachment: 0 = WGL, 1 = EGL/ANGLE, -1 = invalid handle. */
    @JvmStatic
    external fun nativeBackend(handle: Long): Int

    /**
     * Address of the native GrGLGetProc trampoline routing to ANGLE's
     * `eglGetProcAddress`. Passed to
     * [org.jetbrains.skia.GLAssembledInterface.createFromNativePointers] so
     * `DirectContext.makeGLWithInterface` can assemble an EGL/ES GL interface
     * (the default `makeGL()` uses WGL and fails on ANGLE).
     */
    @JvmStatic
    external fun nativeEglGetProcFn(): Long

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
