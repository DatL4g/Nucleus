package dev.nucleusframework.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalViewConfiguration
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.LocalTaoWindow

/**
 * Declares this component as a window drag region: an unconsumed primary
 * press followed by a move starts the native interactive window move
 * (`performWindowDragWithEvent:` on macOS, `WM_NCLBUTTONDOWN`/`HTCAPTION` on
 * Windows, a compositor move grab on Linux).
 *
 * This is the same handler the built-in `TitleBar` installs on its whole
 * surface, exposed as a standalone modifier so custom chrome (a design
 * system's toolbar or headerbar composed via [WindowScaffold]) opts into
 * dragging declaratively. Interactive children (buttons, text fields) opt out
 * automatically by consuming the press event — identical to the `TitleBar`
 * contract.
 *
 * Must be used inside a Tao `DecoratedWindow` content tree; outside of one
 * (no [LocalTaoWindow]) the modifier is a no-op.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.windowDragArea(
    enabled: Boolean = true,
    doubleClickAction: WindowDoubleClickAction = WindowDoubleClickAction.ToggleMaximize,
): Modifier =
    composed {
        val window = LocalTaoWindow.current
        if (!enabled || window == null) return@composed Modifier
        val viewConfig = LocalViewConfiguration.current
        var lastPress by remember { mutableLongStateOf(0L) }
        Modifier
            .titleBarHitTestHandler(window)
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Final) {
                if (doubleClickAction == WindowDoubleClickAction.None) return@onPointerEvent
                // Suppress double-click → toggle-maximize while fullscreen: on
                // macOS `[NSWindow zoom:]` would exit fullscreen unexpectedly.
                if (window.isFullscreen) return@onPointerEvent
                // Touch has no PointerButton — a single touch contact is the
                // touch-equivalent of a primary click (Linux/Wayland is the
                // only backend routing title-bar touch to Compose).
                val isPrimaryOrTouch =
                    this.currentEvent.button == PointerButton.Primary ||
                        (
                            Platform.Current == Platform.Linux &&
                                this.currentEvent.changes.any { it.type == PointerType.Touch }
                        )
                if (isPrimaryOrTouch && this.currentEvent.changes.any { !it.isConsumed }) {
                    val now = System.currentTimeMillis()
                    if (now - lastPress in
                        viewConfig.doubleTapMinTimeMillis..viewConfig.doubleTapTimeoutMillis &&
                        (window.isMaximized || window.isResizable)
                    ) {
                        window.setMaximized(!window.isMaximized)
                        // Cancel any in-flight touch drag armed with the
                        // pre-toggle maximize state.
                        window.cancelWindowsTitleBarTouchDrag()
                    }
                    lastPress = now
                }
            }
    }

/**
 * Opts this subtree out of any ancestor [windowDragArea]: presses landing
 * here never start a window move.
 *
 * A [windowDragArea] treats an unconsumed press as "drag the window", which
 * interactive children normally cancel by consuming it. Gesture detectors
 * that only claim the pointer once it *moves* — scrollbars, sliders, resize
 * handles, anything built on `awaitFirstDown(requireUnconsumed = false)` —
 * leave the press unconsumed, so the window would start moving before they
 * take over. Wrap them with this modifier instead of relying on consumption
 * timing.
 *
 * The press is consumed in the Main pass so an ancestor [windowDragArea]
 * (which arms on Final, after Main) sees it already claimed. Descendants of
 * this modifier still receive the press on Main first (Main is root → leaf),
 * so the wrapped component keeps working normally.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.noWindowDrag(): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Main) { event ->
        event.changes.forEach { it.consume() }
    }
