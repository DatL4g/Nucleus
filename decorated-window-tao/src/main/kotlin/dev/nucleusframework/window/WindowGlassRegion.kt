package dev.nucleusframework.window

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalRequestedGlassBackground
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Kind of system pane rendered by [windowGlassRegion] — mapping directly to
 * the public `NSSplitViewItem` factories, whose panes AppKit backs with the
 * desktop-tinted system material (the wallpaper shows through; windows
 * behind never do — exactly like System Settings).
 */
public enum class WindowGlassRegionKind {
    /** Leading sidebar pane — what System Settings' menu column uses. */
    Sidebar,

    /** Content-list pane (the middle column of a three-column layout). */
    ContentList,

    /**
     * Trailing inspector pane (macOS 14+; falls back to the content-list
     * material on older systems).
     */
    Inspector,
}

/**
 * Renders Apple's wallpaper-tinted system material behind this component
 * only — the rest of the window keeps its opaque background, exactly like
 * System Settings where only the sidebar column shows the desktop through.
 *
 * Implementation is the genuine Apple pattern: a real `NSSplitViewController`
 * with a [kind] pane (`NSSplitViewItem.sidebarWithViewController` & co.) is
 * hosted below the Compose surface, so AppKit applies the same
 * desktop-tinted backdrop as its own apps — the desktop wallpaper shows
 * through, intervening windows never do, and light/dark, inactive-window
 * desaturation, Space-specific wallpapers and the "Reduce transparency"
 * accessibility setting all behave natively. Public API only.
 *
 * The component itself must not paint an opaque background: whatever it
 * leaves unpainted shows the material. Siblings outside the region are
 * unaffected as long as they paint their own backgrounds (the Compose
 * surface is cleared to transparent while at least one region is active,
 * but the window itself stays opaque).
 *
 * The native pane follows this component's window-relative bounds across
 * layout changes and resizes. macOS only (Tao backend); a no-op elsewhere.
 */
public fun Modifier.windowGlassRegion(
    kind: WindowGlassRegionKind = WindowGlassRegionKind.Sidebar,
    cornerRadius: Dp = 0.dp,
): Modifier =
    composed {
        val window = LocalTaoWindow.current
        val glassState = LocalRequestedGlassBackground.current
        if (window == null ||
            Platform.Current != Platform.MacOS ||
            !NativeMetalBridge.isLoaded
        ) {
            return@composed Modifier
        }

        val density = LocalDensity.current
        var regionPtr by remember { mutableStateOf(0L) }
        var lastBounds by remember { mutableStateOf(Rect.Zero) }

        DisposableEffect(window, kind) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            if (nsView != 0L) {
                regionPtr = NativeMetalBridge.nativeAddGlassRegion(nsView, kind.ordinal)
                if (regionPtr != 0L) WindowTransparencyMode.acquire(window, glassState)
            }
            onDispose {
                if (regionPtr != 0L) {
                    NativeMetalBridge.nativeRemoveGlassRegion(regionPtr)
                    regionPtr = 0L
                    WindowTransparencyMode.release(window, glassState)
                }
            }
        }

        Modifier.onGloballyPositioned { coordinates ->
            val ptr = regionPtr
            if (ptr == 0L) return@onGloballyPositioned
            val bounds = coordinates.boundsInWindow()
            if (bounds != lastBounds) {
                lastBounds = bounds
                val scale = density.density
                NativeMetalBridge.nativeSetGlassRegionFrame(
                    ptr,
                    bounds.left / scale,
                    bounds.top / scale,
                    bounds.width / scale,
                    bounds.height / scale,
                    cornerRadius.value,
                )
            }
        }
    }

/**
 * Tracks active glass regions per window: the first region ever flips the
 * window into transparent mode (and the render loop into alpha-0 clears).
 *
 * The mode is deliberately LATCHED — it is never turned back off when the
 * count drops to zero. Regions come and go with composition (sidebar
 * collapse/expand animations recreate them constantly), and flipping a live
 * `CAMetalLayer` back and forth between opaque and non-opaque leaves stale
 * opaque drawables behind: the first frames after re-enabling render alpha-0
 * pixels as solid black. Keeping the layer non-opaque is visually free (the
 * window itself stays opaque and themed) and avoids the churn entirely.
 * Runs on the Tao main thread only — no synchronization needed.
 */
internal object WindowTransparencyMode {
    private val latched = HashSet<Long>()

    fun acquire(
        window: TaoWindow,
        glassState: MutableState<Boolean>?,
    ) {
        if (latched.add(window.handle)) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            if (nsView != 0L) NativeMetalBridge.nativeSetWindowTransparencyMode(nsView, true)
            glassState?.value = true
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun release(
        window: TaoWindow,
        glassState: MutableState<Boolean>?,
    ) {
        // Latched on purpose — see the class KDoc. The set only holds one
        // Long per window that ever had a region; not worth reclaiming.
    }
}
