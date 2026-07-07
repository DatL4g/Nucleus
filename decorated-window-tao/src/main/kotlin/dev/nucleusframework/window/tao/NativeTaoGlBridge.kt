package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_gl"

/**
 * JNI bridge to the EGL/ANGLE helper that turns a Tao HWND into a
 * GL-ES-rendering surface usable from Skiko. Windows-only counterpart of
 * [NativeMetalBridge]. ANGLE translates the ES calls to Direct3D 11
 * (WARP software fallback included), so this works on RDP/VMs and
 * driverless machines too.
 *
 * The ES context is bound per-thread (`eglMakeCurrent`). Rendering AND
 * presentation both run inline on the event-loop thread: a cross-thread
 * present on ANGLE's shared per-display D3D11 device deadlocks the
 * global display lock (the reason the old WGL backend's swap thread
 * never applied here).
 */
internal object NativeTaoGlBridge {
    init {
        // ANGLE (libEGL + libGLESv2) backs the Direct3D-11 render path.
        // They ship next to the other Windows native libs but are only
        // present on win32-*; load them by absolute path FIRST (libGLESv2
        // before libEGL, which depends on it) so the native
        // `LoadLibraryW("libEGL.dll")` resolves the already-loaded module
        // by base name.
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            NativeLibraryLoader.load("libGLESv2", NativeTaoGlBridge::class.java)
            NativeLibraryLoader.load("libEGL", NativeTaoGlBridge::class.java)
        }
    }

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoGlBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Creates an input-transparent render-surface child HWND covering the
     * window's client area, binds an ANGLE ES context to it and makes it
     * current on the calling thread. Returns an opaque attachment handle,
     * or 0 on failure (ANGLE DLLs missing or D3D11 unavailable).
     *
     * Rendering goes through a child (not the Tao HWND itself), kept at
     * the bottom of the sibling z-order so NativeView children (WebView, …)
     * composite above the Compose canvas.
     */
    @JvmStatic
    external fun nativeAttach(hwnd: Long): Long

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

    /** Re-binds the ES context on the current thread. Defensive — `attach`
     * already makes it current, but overlay/popup renderers re-bind their
     * own pbuffer surfaces on the same thread between host frames. */
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

    /** Pumps the back-buffer to screen via `eglSwapBuffers` (vsync-paced,
     * inline). Must be invoked **after** `Surface.flushAndSubmit`. */
    @JvmStatic
    external fun nativePresent(handle: Long)

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int
}
