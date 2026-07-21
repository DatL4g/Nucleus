@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.deco

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxShadowBridge
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GTK-style client-side drop shadow controller for the Tao backend's
 * undecorated **Wayland** windows (approach B — dedicated shadow subsurface,
 * see `docs/linux-csd-shadow-subsurface.md`).
 *
 * This is the measurement + state half. It:
 *  - measures the live theme's shadow extents (`window.csd > decoration`),
 *  - declares the invisible margin to the WM (`gdk_window_set_shadow_width`),
 *  - tracks the 200 ms focus↔backdrop cross-fade, and
 *  - produces the shadow pixels as a small canonical nine-slice **tile**.
 *
 * The compositing half lives natively in `nucleus_tao_egl.c`: the tile is
 * uploaded into a wl_shm ARGB buffer backing a second `wl_subsurface` placed
 * *below* the content subsurface, so the compositor always alpha-blends it —
 * unlike #361, the shadow never rides in the (possibly opaque-presented) EGL
 * content buffer.
 *
 * X11 is out of scope: [initialize] no-ops for any [kind] other than 2
 * (Wayland), so X11 windows stay flat/shadowless.
 *
 * Threading: every entry point runs on the GTK main thread (= render thread).
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
     * fullscreen / tiled). Exposed to Compose so the content subsurface offset
     * and layout padding agree — logical px = dp. Written only from the GTK
     * main thread.
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

    /** A canonical nine-slice shadow tile handed to the native SHM compositor. */
    class ShadowTile(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val sliceLeft: Int,
        val sliceTop: Int,
        val sliceRight: Int,
        val sliceBottom: Int,
    )

    private var gtkWindowPtr: Long = 0
    private var active = false

    /** Corner radii (logical px, TL/TR/BR/BL) the content carve uses — the shadow hugs the same shape. */
    private var radiusTopLeft = 0f
    private var radiusTopRight = 0f
    private var radiusBottomRight = 0f
    private var radiusBottomLeft = 0f

    private var appliedInsets = ShadowInsets.ZERO

    /** 0 = fully normal (focused), 1 = fully backdrop (unfocused). */
    private var backdropFraction = 0f
    private var animStartNanos = 0L
    private var animFrom = 0f
    private var animTarget = 0f

    private var themeStamp: String? = null

    /** Per-state canonical tile cache (0 = normal, 1 = backdrop); invalidated on scale/theme change. */
    private class CachedTile(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val sliceLeft: Int,
        val sliceTop: Int,
        val sliceRight: Int,
        val sliceBottom: Int,
        val scale: Float,
    )

    private val tiles = HashMap<Int, CachedTile>()

    /**
     * Measures the theme's shadow extents and arms the controller. Must be
     * called on the GTK main thread once the window is realized. Returns false
     * when CSD shadows can't work here — including any non-Wayland [kind]
     * (X11 = 1 stays flat) or when GTK reports no compositor / RGBA visual.
     *
     * [kind] is the EGL attachment kind: 1 = X11 (unsupported), 2 = Wayland.
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
        // Wayland only — approach B is a wl_subsurface shadow.
        if (kind != WAYLAND_KIND) return false
        if (gtkWindowPtr == 0L) return false
        if (!NativeTaoLinuxShadowBridge.isLoaded) return false
        if (!NativeTaoLinuxShadowBridge.nativeShadowSupported(kind)) return false

        this.gtkWindowPtr = gtkWindowPtr
        this.radiusTopLeft = radiusTopLeft
        this.radiusTopRight = radiusTopRight
        this.radiusBottomRight = radiusBottomRight
        this.radiusBottomLeft = radiusBottomLeft

        val measured = measureExtents() ?: return false
        // GTK4 clamps every side to RESIZE_HANDLE_SIZE so the invisible margin
        // always fits a resize grip, even for themes with tiny shadows.
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

    /** 0 = focused shadow, 1 = backdrop shadow; used by the SHM tile blend. */
    val backdropAmount: Float get() = backdropFraction

    /**
     * Reconciles the effective margins with the current window state — full
     * margins when floating, zero while maximized/fullscreen/tiled (those sit
     * flush against a screen edge, no shadow). Cheap when nothing changed.
     *
     * Unlike a real GTK CSD window, approach B does **not** declare the margin
     * to the WM (`gdk_window_set_shadow_width`) or grow the surface: the shadow
     * rides a sibling subsurface at a negative offset that overflows the parent
     * bounds, so the content subsurface — and therefore the input/resize
     * coordinate system — is left exactly as the flat window.
     */
    fun reconcile(suspended: Boolean) {
        if (!active) return
        val target =
            if (suspended) {
                ShadowInsets.ZERO
            } else {
                ShadowInsets(marginLeft, marginTop, marginRight, marginBottom)
            }
        if (target == appliedInsets) return
        appliedInsets = target
        insetsState.value = target
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
        // A theme/dark-mode switch is invisible while unfocused-idle; the focus
        // edge is a natural, cheap moment to revalidate the tile cache.
        val stamp = NativeTaoLinuxShadowBridge.nativeShadowThemeStamp()
        if (stamp != themeStamp) {
            themeStamp = stamp
            tiles.clear()
        }
        requestRedraw()
    }

    /** True while the backdrop cross-fade needs more frames. */
    val isAnimating: Boolean get() = active && backdropFraction != animTarget

    /**
     * Produces the current shadow as a canonical nine-slice tile (normal and
     * backdrop states blended by the live focus fraction), for the native SHM
     * compositor to expand into the full-window shadow subsurface. Returns null
     * when inactive, suspended (no margins), or the theme render failed.
     */
    fun currentTile(
        scale: Float,
        nowNanos: Long,
    ): ShadowTile? {
        if (!active || appliedInsets.isZero) return null
        stepAnimation(nowNanos)
        if (isAnimating) requestRedraw()

        val fraction = backdropFraction
        val normal = tileFor(backdrop = false, scale = scale) ?: return null
        if (fraction <= 0f) return normal.toTile()
        val backdrop = tileFor(backdrop = true, scale = scale) ?: return normal.toTile()
        if (fraction >= 1f) return backdrop.toTile()
        return blend(normal, backdrop, fraction)
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

    /**
     * Renders both shadow states at scale 1 with a generous probe margin and
     * takes the per-side alpha bounding box. Extents ignore shadow *color* in
     * GTK (transparent placeholder shadows keep both states the same size), so
     * the max over the two scans is taken.
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
     * `[minX, minY, maxX, maxY]` (`maxX = -1` when fully transparent), or null
     * when the payload is malformed.
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

    private fun tileFor(
        backdrop: Boolean,
        scale: Float,
    ): CachedTile? {
        val key = if (backdrop) 1 else 0
        val cached = tiles[key]
        if (cached != null && cached.scale == scale) return cached

        // Canonical render: big enough that the four corner blocks (margin +
        // corner radius + 1px safety) never overlap, leaving a genuine centre
        // strip to stretch.
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

        val pixels = data.copyOfRange(2, 2 + w * h)
        val sliceL = ((marginLeft + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val sliceT = ((marginTop + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val sliceR = w - ((marginRight + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val sliceB = h - ((marginBottom + maxRadius + SLICE_SAFETY) * scale).roundToInt()
        val tile = CachedTile(pixels, w, h, sliceL, sliceT, sliceR, sliceB, scale)
        tiles[key] = tile
        return tile
    }

    private fun CachedTile.toTile() = ShadowTile(pixels, width, height, sliceLeft, sliceTop, sliceRight, sliceBottom)

    /**
     * Per-pixel alpha-composited blend of two canonical tiles by [fraction]
     * (0 → normal, 1 → backdrop). The tiles are small (nine-slice canonical
     * size), so this is cheap even every fade frame. Both inputs are
     * premultiplied ARGB, so the blend stays premultiplied.
     */
    private fun blend(
        a: CachedTile,
        b: CachedTile,
        fraction: Float,
    ): ShadowTile {
        // Guard against a scale race producing mismatched sizes: fall back.
        if (a.width != b.width || a.height != b.height || a.pixels.size != b.pixels.size) {
            return if (fraction >= 0.5f) b.toTile() else a.toTile()
        }
        val fb = (fraction.coerceIn(0f, 1f) * 255f).roundToInt()
        val fa = 255 - fb
        val out = IntArray(a.pixels.size)
        for (i in out.indices) {
            val pa = a.pixels[i]
            val pb = b.pixels[i]
            val alpha = ((pa ushr 24) * fa + (pb ushr 24) * fb) / 255
            val red = (((pa ushr 16) and 0xFF) * fa + ((pb ushr 16) and 0xFF) * fb) / 255
            val green = (((pa ushr 8) and 0xFF) * fa + ((pb ushr 8) and 0xFF) * fb) / 255
            val blue = ((pa and 0xFF) * fa + (pb and 0xFF) * fb) / 255
            out[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return ShadowTile(out, a.width, a.height, a.sliceLeft, a.sliceTop, a.sliceRight, a.sliceBottom)
    }

    fun dispose() {
        tiles.clear()
        active = false
    }

    private companion object {
        /** EGL attachment kind for Wayland (see NativeTaoBridge.nativeLinuxHandles). */
        const val WAYLAND_KIND = 2

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
