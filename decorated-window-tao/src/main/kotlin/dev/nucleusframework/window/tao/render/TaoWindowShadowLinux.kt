@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxShadowBridge
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GTK-style client-side drop shadow for the Tao backend's undecorated Linux
 * windows — the drawing/animation half of the mechanism GTK uses for its own
 * CSD windows (the WM half — `_GTK_FRAME_EXTENTS` / xdg window geometry — is
 * [NativeTaoLinuxShadowBridge.nativeShadowApply]).
 *
 * The shadow pixels come from the *live* GTK theme: the `window.csd >
 * decoration` CSS node is rendered off-screen by GTK itself (so Adwaita,
 * Yaru, Breeze-gtk… all look native, including the 1px window outline), then
 * nine-sliced into the window margins each frame. GTK's blurred shadow is
 * constant along each edge, so the nine-slice stretch is exact — the same
 * trick as GTK's own cached corner masks + repeated side strips
 * (gtkcssshadowvalue.c `_gtk_css_shadow_value_paint_box`).
 *
 * Focus loss cross-fades to the theme's `decoration:backdrop` rendering over
 * 200 ms ease-out, mirroring Adwaita's `transition: $backdrop_transition`.
 * Shadow margins are identical in both states (themes keep a transparent
 * placeholder shadow for exactly this reason), so the window never jumps.
 *
 * States, mirroring GTK's `get_shadow_width`:
 *  - floating: full margins, normal/backdrop shadow;
 *  - tiled/snapped: margins kept (they are the resize grip area — GTK themes
 *    render only the 1px outline plus a transparent ring, see GNOME/gtk#3670);
 *  - maximized/fullscreen: margins dropped to zero, no shadow.
 *
 * Threading: everything runs on the GTK main thread (= render thread).
 */
internal class TaoWindowShadowLinux(
    private val requestRedraw: () -> Unit,
) {
    /** Logical margins measured from the theme, min 12 px (GTK4's RESIZE_HANDLE_SIZE). */
    var marginLeft = 0
        private set
    var marginTop = 0
        private set
    var marginRight = 0
        private set
    var marginBottom = 0
        private set

    /**
     * Margins the WM currently knows about (zeroed while maximized /
     * fullscreen). Exposed to Compose as content padding — logical px = dp.
     * Written only from the GTK main thread.
     */
    val insetsState: MutableState<ShadowInsets> = mutableStateOf(ShadowInsets.ZERO)

    data class ShadowInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val isZero: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0

        companion object {
            val ZERO = ShadowInsets(0, 0, 0, 0)
        }
    }

    private var gtkWindowPtr: Long = 0
    private var active = false

    /** Corner radii (logical px, TL/TR/BR/BL) the content carve uses — the shadow hugs the same shape. */
    private var radiusTopLeft = 0f
    private var radiusTopRight = 0f
    private var radiusBottomRight = 0f
    private var radiusBottomLeft = 0f

    private var appliedInsets = ShadowInsets.ZERO
    private var appliedInputW = -1
    private var appliedInputH = -1

    /** True once the desired margins were handed to the native side. */
    private var nativeArmed = false

    /** 0 = fully normal, 1 = fully backdrop. */
    private var backdropFraction = 0f
    private var animStartNanos = 0L
    private var animFrom = 0f
    private var animTarget = 0f

    private var themeStamp: String? = null

    private class CachedImage(
        val image: Image,
        val centerSlice: IRect,
        val scale: Float,
    )

    /** Keyed by backdrop(1)/tiled(2) bits; invalidated on scale/theme change. */
    private val images = HashMap<Int, CachedImage>()

    /**
     * Measures the theme's shadow extents and arms the controller. Must be
     * called on the GTK main thread once the window is realized. Returns
     * false when CSD shadows can't work here (no compositor / no ARGB
     * visual / WM without `_GTK_FRAME_EXTENTS` — GTK falls back to its
     * "solid-csd" look in the same situation).
     *
     * [kind] is the EGL attachment kind: 1 = X11, 2 = Wayland.
     */
    fun initialize(
        gtkWindowPtr: Long,
        kind: Int,
        radiusTopLeft: Float,
        radiusTopRight: Float,
        radiusBottomRight: Float,
        radiusBottomLeft: Float,
    ): Boolean {
        if (active) return true
        if (gtkWindowPtr == 0L) return false
        if (!NativeTaoLinuxShadowBridge.isLoaded) return false
        if (!NativeTaoLinuxShadowBridge.nativeShadowSupported(kind)) return false

        this.gtkWindowPtr = gtkWindowPtr
        this.radiusTopLeft = radiusTopLeft
        this.radiusTopRight = radiusTopRight
        this.radiusBottomRight = radiusBottomRight
        this.radiusBottomLeft = radiusBottomLeft

        val measured = measureExtents() ?: return false
        // GTK4 clamps every side to RESIZE_HANDLE_SIZE so the invisible
        // margin always fits a resize grip, even for themes with tiny shadows.
        marginLeft = max(measured[0], RESIZE_HANDLE_SIZE)
        marginTop = max(measured[1], RESIZE_HANDLE_SIZE)
        marginRight = max(measured[2], RESIZE_HANDLE_SIZE)
        marginBottom = max(measured[3], RESIZE_HANDLE_SIZE)
        themeStamp = NativeTaoLinuxShadowBridge.nativeShadowThemeStamp()
        active = true
        return true
    }

    val isActive: Boolean get() = active

    /** Margins currently declared to the WM (zero while maximized/fullscreen). */
    val effectiveInsets: ShadowInsets get() = appliedInsets

    /**
     * Renders both shadow states at scale 1 with a generous probe margin and
     * takes the per-side alpha bounding box. Extents ignore shadow *color* in
     * GTK (transparent placeholder shadows keep both states the same size),
     * so the max over the two scans is taken — a backdrop-only larger shadow
     * would otherwise be clipped.
     */
    private fun measureExtents(): IntArray? {
        var l = 0
        var t = 0
        var r = 0
        var b = 0
        for (backdrop in booleanArrayOf(false, true)) {
            val data =
                NativeTaoLinuxShadowBridge.nativeShadowRender(
                    backdrop = backdrop,
                    tiled = false,
                    visibleW = PROBE_CORE,
                    visibleH = PROBE_CORE,
                    marginL = PROBE_MARGIN,
                    marginT = PROBE_MARGIN,
                    marginR = PROBE_MARGIN,
                    marginB = PROBE_MARGIN,
                    scale = 1f,
                    radiusTopLeft = radiusTopLeft,
                    radiusTopRight = radiusTopRight,
                    radiusBottomRight = radiusBottomRight,
                    radiusBottomLeft = radiusBottomLeft,
                ) ?: return null
            val bounds = alphaBounds(data) ?: return null
            // Fully transparent decoration (shadow-less theme) → keep zeros.
            if (bounds[2] < 0) continue
            l = max(l, PROBE_MARGIN - bounds[0])
            t = max(t, PROBE_MARGIN - bounds[1])
            r = max(r, bounds[2] - (PROBE_MARGIN + PROBE_CORE - 1))
            b = max(b, bounds[3] - (PROBE_MARGIN + PROBE_CORE - 1))
        }
        return intArrayOf(max(l, 0), max(t, 0), max(r, 0), max(b, 0))
    }

    /**
     * Bounding box of non-zero alpha in a `[w, h, pixels…]` payload as
     * `[minX, minY, maxX, maxY]` (`maxX = -1` when fully transparent), or
     * null when the payload is malformed.
     */
    private fun alphaBounds(data: IntArray): IntArray? {
        val w = data[0]
        val h = data[1]
        if (w <= 0 || h <= 0 || data.size < 2 + w * h) return null
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val rowBase = 2 + y * w
            for (x in 0 until w) {
                if (data[rowBase + x] ushr 24 != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Reconciles the WM-declared margins and input shape with the current
     * window state. Cheap when nothing changed — called once per rendered
     * frame, exactly like GTK re-evaluating `get_shadow_width` on state
     * transitions.
     *
     * [surfaceWLogical]/[surfaceHLogical] are the full surface logical size
     * (margins included); the visible frame for the input-shape rect is
     * derived by subtracting the margins.
     */
    fun reconcile(
        suspended: Boolean,
        surfaceWLogical: Int,
        surfaceHLogical: Int,
    ) {
        if (!active) return
        val target =
            if (suspended) {
                ShadowInsets.ZERO
            } else {
                ShadowInsets(marginLeft, marginTop, marginRight, marginBottom)
            }
        val visibleWLogical = surfaceWLogical - target.left - target.right
        val visibleHLogical = surfaceHLogical - target.top - target.bottom
        val insetsChanged = target != appliedInsets
        if (insetsChanged) {
            // The native side owns the WM declaration: the desired margins
            // are handed over once, and a window-state-event handler zeroes/
            // restores them *synchronously* on maximize/fullscreen/tile
            // transitions — GTK's exact timing (a frame-later update from
            // here makes mutter fight the window during a snap). This state
            // only mirrors the effective value for Compose padding, the
            // carve and the shadow drawing.
            if (!nativeArmed) {
                NativeTaoLinuxShadowBridge.nativeShadowApply(
                    gtkWindowPtr,
                    marginLeft,
                    marginTop,
                    marginRight,
                    marginBottom,
                )
                nativeArmed = true
            }
            appliedInsets = target
            insetsState.value = target
        }
        // Input region = visible frame + 12 px resize ring (GTK4's
        // RESIZE_HANDLE_SIZE) so clicks in the outer shadow fall through —
        // mirrors gtkwindow.c `update_realized_window_properties`.
        if (target.isZero) {
            if (appliedInputW != -1 || insetsChanged) {
                NativeTaoLinuxShadowBridge.nativeShadowSetInputShape(gtkWindowPtr, 0, 0, -1, -1)
                appliedInputW = -1
                appliedInputH = -1
            }
        } else if (insetsChanged || visibleWLogical != appliedInputW || visibleHLogical != appliedInputH) {
            NativeTaoLinuxShadowBridge.nativeShadowSetInputShape(
                gtkWindowPtr,
                target.left - RESIZE_HANDLE_SIZE,
                target.top - RESIZE_HANDLE_SIZE,
                visibleWLogical + 2 * RESIZE_HANDLE_SIZE,
                visibleHLogical + 2 * RESIZE_HANDLE_SIZE,
            )
            appliedInputW = visibleWLogical
            appliedInputH = visibleHLogical
        }
    }

    /**
     * Retargets the focus cross-fade. Mirrors the theme's
     * `decoration:backdrop { transition: 200ms ease-out; }`.
     */
    fun onFocusChanged(focused: Boolean) {
        if (!active) return
        val target = if (focused) 0f else 1f
        if (target == animTarget && animStartNanos != 0L) return
        if (target == backdropFraction) {
            animTarget = target
            return
        }
        animFrom = backdropFraction
        animTarget = target
        animStartNanos = System.nanoTime()
        // A theme/dark-mode switch is invisible while unfocused-idle; the
        // focus edge is a natural, cheap moment to revalidate the cache.
        val stamp = NativeTaoLinuxShadowBridge.nativeShadowThemeStamp()
        if (stamp != themeStamp) {
            themeStamp = stamp
            clearImages()
        }
        requestRedraw()
    }

    /** True while the backdrop cross-fade needs more frames. */
    val isAnimating: Boolean get() = active && backdropFraction != animTarget

    /**
     * Draws the shadow into the margin area of the frame surface. Call after
     * the content carve — the carve cleared everything outside the visible
     * rounded frame, and the shadow's own geometry never overlaps the frame
     * interior (GTK paints outset shadows with an even-odd path around the
     * border box), so plain src-over layering is correct.
     */
    fun draw(
        canvas: Canvas,
        surfaceWPx: Int,
        surfaceHPx: Int,
        scale: Float,
        nowNanos: Long,
    ) {
        if (!active || appliedInsets.isZero) return
        stepAnimation(nowNanos)

        val fraction = backdropFraction
        when {
            fraction <= 0f -> drawState(canvas, surfaceWPx, surfaceHPx, scale, backdrop = false, alpha = 1f)
            fraction >= 1f -> drawState(canvas, surfaceWPx, surfaceHPx, scale, backdrop = true, alpha = 1f)
            else -> {
                drawState(canvas, surfaceWPx, surfaceHPx, scale, backdrop = false, alpha = 1f - fraction)
                drawState(canvas, surfaceWPx, surfaceHPx, scale, backdrop = true, alpha = fraction)
            }
        }
        if (isAnimating) requestRedraw()
    }

    private fun stepAnimation(nowNanos: Long) {
        if (backdropFraction == animTarget) return
        val elapsed = (nowNanos - animStartNanos) / 1_000_000f
        val progress = (elapsed / BACKDROP_TRANSITION_MS).coerceIn(0f, 1f)
        backdropFraction =
            if (progress >= 1f) {
                animTarget
            } else {
                animFrom + (animTarget - animFrom) * easeOut(progress)
            }
    }

    /** CSS `ease-out` = cubic-bezier(0, 0, 0.58, 1); a close even-power approximation. */
    private fun easeOut(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv
    }

    private fun drawState(
        canvas: Canvas,
        surfaceWPx: Int,
        surfaceHPx: Int,
        scale: Float,
        backdrop: Boolean,
        alpha: Float,
    ) {
        val cached = imageFor(backdrop, scale) ?: return
        // Nine-slice needs the destination to fit both un-stretched corner
        // blocks; skip the shadow for windows smaller than that (the WM
        // margin is still honoured, there's just nothing worth drawing).
        if (surfaceWPx <= cached.centerSlice.left + (cached.image.width - cached.centerSlice.right) ||
            surfaceHPx <= cached.centerSlice.top + (cached.image.height - cached.centerSlice.bottom)
        ) {
            return
        }
        Paint().use { paint ->
            paint.setAlphaf(alpha.coerceIn(0f, 1f))
            canvas.drawImageNine(
                cached.image,
                cached.centerSlice,
                Rect.makeWH(surfaceWPx.toFloat(), surfaceHPx.toFloat()),
                // NEAREST: the stretched regions of a GTK shadow are constant
                // strips; linear filtering would only soften the crisp 1px
                // theme outline.
                FilterMode.NEAREST,
                paint,
            )
        }
    }

    private fun imageFor(
        backdrop: Boolean,
        scale: Float,
    ): CachedImage? {
        val key = if (backdrop) 1 else 0
        val cached = images[key]
        if (cached != null && cached.scale == scale) return cached
        images.remove(key)?.image?.close()

        // Canonical render: big enough that the four corner blocks (margin +
        // corner radius + 1px safety) never overlap, leaving a genuine
        // centre strip to stretch.
        val maxRadius =
            ceil(
                maxOf(radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft).toDouble(),
            ).toInt()
        val core = 2 * (maxRadius + SLICE_SAFETY) + CENTER_STRIP
        val data =
            NativeTaoLinuxShadowBridge.nativeShadowRender(
                backdrop = backdrop,
                tiled = false,
                visibleW = core,
                visibleH = core,
                marginL = marginLeft,
                marginT = marginTop,
                marginR = marginRight,
                marginB = marginBottom,
                scale = scale,
                radiusTopLeft = radiusTopLeft,
                radiusTopRight = radiusTopRight,
                radiusBottomRight = radiusBottomRight,
                radiusBottomLeft = radiusBottomLeft,
            ) ?: return null
        val w = data[0]
        val h = data[1]
        if (w <= 0 || h <= 0 || data.size < 2 + w * h) return null

        val bytes = ByteArray(w * h * 4)
        var src = 2
        var dst = 0
        while (dst < bytes.size) {
            val px = data[src++]
            // Cairo ARGB32 is native-endian packed ARGB; emit little-endian
            // BGRA bytes to match ColorType.BGRA_8888.
            bytes[dst++] = (px and 0xFF).toByte()
            bytes[dst++] = ((px ushr 8) and 0xFF).toByte()
            bytes[dst++] = ((px ushr 16) and 0xFF).toByte()
            bytes[dst++] = ((px ushr 24) and 0xFF).toByte()
        }
        val image =
            Image.makeRaster(
                ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
                bytes,
                rowBytes = w * 4,
            )
        val sliceL = ((marginLeft + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val sliceT = ((marginTop + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val sliceR = w - ((marginRight + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val sliceB = h - ((marginBottom + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val result = CachedImage(image, IRect.makeLTRB(sliceL, sliceT, sliceR, sliceB), scale)
        images[key] = result
        return result
    }

    private fun clearImages() {
        for (cached in images.values) cached.image.close()
        images.clear()
    }

    fun dispose() {
        clearImages()
        active = false
    }

    private companion object {
        /** GTK4 gtkwindow.c: `#define RESIZE_HANDLE_SIZE 12`. */
        const val RESIZE_HANDLE_SIZE = 12

        /** Adwaita `$backdrop_transition: 200ms ease-out`. */
        const val BACKDROP_TRANSITION_MS = 200f

        /** Extents probe: visible core size and per-side room, logical px. */
        const val PROBE_CORE = 40
        const val PROBE_MARGIN = 100

        /** Corner-block padding and stretchable centre strip, logical px. */
        const val SLICE_SAFETY = 2
        const val CENTER_STRIP = 4
    }
}
