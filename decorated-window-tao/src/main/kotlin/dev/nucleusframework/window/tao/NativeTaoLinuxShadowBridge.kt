package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.NativeLibraryLoader

// The shadow helper is compiled into the widget helper library (see
// linux/build.sh) — one .so, two JNI bridge classes.
private const val LIBRARY_NAME = "nucleus_tao_linux_widget"

/**
 * JNI bridge to `linux/nucleus_tao_linux_shadow.c` — GTK client-side
 * decoration shadows for the Tao backend's undecorated Linux windows.
 *
 * Replicates GTK's own CSD shadow mechanism: the live theme's
 * `window.csd > decoration` node is rendered off-screen (normal +
 * `:backdrop` states) and the invisible margin is declared to the WM via
 * `gdk_window_set_shadow_width()` (`_GTK_FRAME_EXTENTS` on X11,
 * xdg_surface window-geometry margins on Wayland). See
 * [dev.nucleusframework.window.tao.render.TaoWindowShadowLinux] for the
 * drawing/animation half.
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose dispatcher thread).
 */
internal object NativeTaoLinuxShadowBridge {
    val isLoaded: Boolean =
        NativeLibraryLoader.load(
            LIBRARY_NAME,
            NativeTaoLinuxShadowBridge::class.java,
        )

    /**
     * True when client-side shadows can work here — mirrors GTK3's
     * `gtk_window_supports_client_shadow`: compositor running, RGBA visual
     * available, and on X11 ([kind] = 1) a WM advertising
     * `_GTK_FRAME_EXTENTS` in `_NET_SUPPORTED`. [kind] = 2 for Wayland.
     */
    @JvmStatic
    external fun nativeShadowSupported(kind: Int): Boolean

    /**
     * Renders the themed decoration node into an off-screen ARGB surface:
     * shadow + 1px outline around a `visibleW`×`visibleH` logical frame
     * with the given logical margins, at [scale]. Corner radii (logical
     * px) are forced so the shadow hugs the same rounded shape as the
     * content carve.
     *
     * Returns `[widthPx, heightPx, pixels…]` — row-major premultiplied
     * ARGB32 (= Skia BGRA_8888 PREMUL on little-endian) — or null.
     */
    @JvmStatic
    @Suppress("LongParameterList")
    external fun nativeShadowRender(
        backdrop: Boolean,
        tiled: Boolean,
        visibleW: Int,
        visibleH: Int,
        marginL: Int,
        marginT: Int,
        marginR: Int,
        marginB: Int,
        scale: Float,
        radiusTopLeft: Float,
        radiusTopRight: Float,
        radiusBottomRight: Float,
        radiusBottomLeft: Float,
    ): IntArray?

    /**
     * Declares the invisible shadow margin (logical px) to the WM —
     * GTK's own `gdk_window_set_shadow_width()`: `_GTK_FRAME_EXTENTS` on
     * X11, window-geometry margins on Wayland (where GDK also grows the
     * surface to keep the visible geometry stable). Zeros clear it.
     */
    @JvmStatic
    external fun nativeShadowApply(
        gtkWindowPtr: Long,
        marginL: Int,
        marginT: Int,
        marginR: Int,
        marginB: Int,
    )

    /**
     * Restricts the window input region to the given logical rect (visible
     * frame + 12 px resize ring, GTK4's `RESIZE_HANDLE_SIZE`) so clicks in
     * the outer shadow fall through. `width < 0` resets to full surface.
     */
    @JvmStatic
    external fun nativeShadowSetInputShape(
        gtkWindowPtr: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    /**
     * Cache key for rendered shadow images: `"<gtk-theme-name>|<dark>"`.
     * Changes when the user switches theme or toggles dark preference.
     */
    @JvmStatic
    external fun nativeShadowThemeStamp(): String?
}
