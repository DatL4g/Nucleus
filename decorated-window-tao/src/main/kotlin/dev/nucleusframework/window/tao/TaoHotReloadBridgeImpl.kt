package dev.nucleusframework.window.tao

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.consumeAsFlow
import org.jetbrains.compose.devtools.api.WindowsState
import org.jetbrains.compose.reload.agent.orchestration
import org.jetbrains.compose.reload.agent.sendAsync
import org.jetbrains.compose.reload.core.WindowId
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import kotlin.math.roundToInt

/**
 * Concrete [TaoHotReloadBridge], loaded reflectively by [TaoHotReloadIntegration]
 * only when Compose Hot Reload is active (`compose.reload.isActive == "true"`),
 * which is also when the hot-reload artifacts land on the runtime classpath.
 *
 * Mirrors `org.jetbrains.compose.reload.jvm.startWindowManager` (the AWT-based
 * tracker in `hot-reload-runtime-jvm`), but driven by Tao window callbacks
 * instead of `java.awt.Window` listeners — the Tao backend has no AWT windows
 * for the agent's auto-instrumentation to attach to.
 *
 * Coordinates are converted from Tao's **physical** pixels to the **logical**
 * pixels the dev-tools sidecar expects (matching AWT's `window.x/width`, which
 * `startWindowManager` sends verbatim). Fractional HiDPI scales may drift a
 * few pixels; integer scales are exact.
 *
 * Version-skew safety: this class compiles against the catalog's hot-reload
 * version, but the runtime classes come from whatever agent the user's Compose
 * plugin launched — and the orchestration API is not binary-stable across
 * releases (e.g. `WindowsState.WindowState` gained a `title` parameter in
 * 1.2.0 and dropped the old constructor). Every entry point is guarded: the
 * first [LinkageError] (or any other failure) permanently disables tracking
 * for that window, so the sidecar merely stops following it — the app itself
 * must never be taken down by dev tooling.
 */
internal class TaoHotReloadBridgeImpl : TaoHotReloadBridge {
    override fun trackWindow(
        window: TaoWindow,
        title: String?,
        alwaysOnTop: Boolean,
    ) {
        // [title] is unused: WindowsState.WindowState has no title field in the
        // hot-reload version bundled by Compose 1.11.x. Kept in the bridge
        // surface so the call sites don't change when a newer API gains one.
        val windowId = WindowId.create()
        // Conflated channel: coalesce bursts of move/resize events (GTK fires
        // one per pixel of a drag) into a single WindowsState update, exactly
        // like the AWT tracker's `Channel.CONFLATED` + `conflate()`.
        val windowState = Channel<WindowsState.WindowState?>(Channel.CONFLATED)

        // One-shot kill switch: flipped on the first failure inside a Tao
        // callback (see class KDoc). Closing the channel also ends the pump.
        var broken = false

        fun guarded(block: () -> Unit) {
            if (broken) return
            try {
                block()
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable, // kill switch: any failure disables the bridge
            ) {
                broken = true
                windowState.close()
                hotReloadLogger.log(java.util.logging.Level.FINE, "Tao hot-reload bridge disabled after failure", t)
            }
        }

        // Pump channel → orchestration WindowsState. Runs on the orchestration
        // task's dispatcher (off the Tao main thread). `null` removes the
        // window (minimize/hide/destroy) — matches AWT's `broadcastGone`.
        orchestration.subtask {
            windowState.consumeAsFlow().conflate().collect { state ->
                orchestration.update(WindowsState) { current ->
                    val windows =
                        if (state == null) {
                            current.windows - windowId
                        } else {
                            current.windows + (windowId to state)
                        }
                    WindowsState(windows)
                }
            }
        }

        fun toLogical(physical: Int): Int = (physical / window.scaleFactor).roundToInt()

        /** Reads the live outer rect and pushes a conflated state update. */
        fun broadcastActiveState() {
            val bounds = window.outerBoundsPx() ?: return
            // [x, y, width, height] physical px → logical.
            val x = toLogical(bounds[BOUNDS_X].toInt())
            val y = toLogical(bounds[BOUNDS_Y].toInt())
            val w = toLogical(bounds[BOUNDS_WIDTH].toInt())
            val h = toLogical(bounds[BOUNDS_HEIGHT].toInt())
            if (w <= 0 || h <= 0) return
            windowState.trySendBlocking(
                WindowsState.WindowState(
                    x = x,
                    y = y,
                    width = w,
                    height = h,
                    isAlwaysOnTop = alwaysOnTop,
                ),
            )
            OrchestrationMessage
                .ApplicationWindowPositioned(
                    windowId,
                    x,
                    y,
                    w,
                    h,
                    isAlwaysOnTop = alwaysOnTop,
                ).sendAsync()
        }

        fun broadcastGone() {
            windowState.trySendBlocking(null)
            OrchestrationMessage.ApplicationWindowGone(windowId).sendAsync()
        }

        // Initial state: outerBoundsPx is null until the window is mapped, so
        // the first meaningful broadcast happens via onResized (fires right
        // after onWindowReady with the mapped size). Still try once in case
        // geometry is already available.
        guarded { broadcastActiveState() }

        window.onMoved { _, _ -> guarded { broadcastActiveState() } }
        window.onResized { _, _ -> guarded { broadcastActiveState() } }
        window.onFocusChanged { focused ->
            if (focused) {
                guarded {
                    // Sidecar brings itself toFront() on this.
                    OrchestrationMessage.ApplicationWindowGainedFocus(windowId).sendAsync()
                    broadcastActiveState()
                }
            }
        }
        window.onMinimizedChanged { minimized ->
            guarded { if (minimized) broadcastGone() else broadcastActiveState() }
        }
        window.onDestroyed { guarded { broadcastGone() } }
    }
}

private val hotReloadLogger: java.util.logging.Logger =
    java.util.logging.Logger
        .getLogger("dev.nucleusframework.window.tao.hotReload")

// Indices into the [x, y, width, height] array returned by TaoWindow.outerBoundsPx().
private const val BOUNDS_X = 0
private const val BOUNDS_Y = 1
private const val BOUNDS_WIDTH = 2
private const val BOUNDS_HEIGHT = 3
