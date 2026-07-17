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
 */
internal class TaoHotReloadBridgeImpl : TaoHotReloadBridge {

    override fun trackWindow(window: TaoWindow, title: String?, alwaysOnTop: Boolean) {
        val windowId = WindowId.create()
        // Conflated channel: coalesce bursts of move/resize events (GTK fires
        // one per pixel of a drag) into a single WindowsState update, exactly
        // like the AWT tracker's `Channel.CONFLATED` + `conflate()`.
        val windowState = Channel<WindowsState.WindowState?>(Channel.CONFLATED)

        // Pump channel → orchestration WindowsState. Runs on the orchestration
        // task's dispatcher (off the Tao main thread). `null` removes the
        // window (minimize/hide/destroy) — matches AWT's `broadcastGone`.
        orchestration.subtask {
            windowState.consumeAsFlow().conflate().collect { state ->
                orchestration.update(WindowsState) { current ->
                    val windows = if (state == null) {
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
            val x = toLogical(bounds[0].toInt())
            val y = toLogical(bounds[1].toInt())
            val w = toLogical(bounds[2].toInt())
            val h = toLogical(bounds[3].toInt())
            if (w <= 0 || h <= 0) return
            windowState.trySendBlocking(
                WindowsState.WindowState(
                    x = x, y = y, width = w, height = h,
                    isAlwaysOnTop = alwaysOnTop,
                    title = title,
                ),
            )
            // Legacy message (deprecated, but older devtools still consume it).
            OrchestrationMessage.ApplicationWindowPositioned(
                windowId, x, y, w, h, isAlwaysOnTop = alwaysOnTop,
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
        broadcastActiveState()

        window.onMoved { _, _ -> broadcastActiveState() }
        window.onResized { _, _ -> broadcastActiveState() }
        window.onFocusChanged { focused ->
            if (focused) {
                // Sidecar brings itself toFront() on this.
                OrchestrationMessage.ApplicationWindowGainedFocus(windowId).sendAsync()
                broadcastActiveState()
            }
        }
        window.onMinimizedChanged { minimized ->
            if (minimized) broadcastGone() else broadcastActiveState()
        }
        window.onDestroyed { broadcastGone() }
    }
}