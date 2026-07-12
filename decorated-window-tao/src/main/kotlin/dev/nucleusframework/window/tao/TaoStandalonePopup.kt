package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.render.StandalonePopupHost
import dev.nucleusframework.window.tao.render.TaoStandalonePopupHost
import dev.nucleusframework.window.tao.render.TaoStandalonePopupHostMac

/**
 * Standalone transparent popup (Windows + macOS): a top-level, ownerless,
 * non-activating native panel with per-pixel transparency, hosting [content]
 * in its own Compose scene. There is no backing "window" anywhere — nothing
 * appears in the taskbar/Dock or the app switcher — and rendering is driven on
 * demand (no owner window render loop). Built for system-tray popups.
 *
 * Windows uses an ownerless `WS_POPUP` + DComp surface; macOS uses an ownerless
 * non-activating `NSPanel` + `CAMetalLayer`. Linux has no equivalent that works
 * across WMs, so on Linux (or when the native pipeline is unavailable) the
 * composable is a no-op.
 *
 * Must be called inside `taoApplication { }` (directly or through
 * `nucleusApplication` on the Tao backend).
 *
 * @param visible shows/hides the panel; the composition (and [content] state)
 *   is retained while hidden.
 * @param position top-left corner in logical (dp) screen coordinates.
 * @param size panel size in dp.
 * @param focusable whether the panel can take keyboard focus on click.
 * @param onOutsideClick invoked when the user clicks anywhere outside the
 *   panel while it is visible (native mouse-hook / NSEvent monitor, fires on
 *   mouse-down).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TaoStandalonePopup(
    visible: Boolean,
    position: WindowPosition.Absolute,
    size: DpSize,
    focusable: Boolean = true,
    onOutsideClick: (() -> Unit)? = null,
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    // Linux has no cross-WM transparency equivalent — no-op there.
    if (Platform.Current != Platform.Windows && Platform.Current != Platform.MacOS) return

    // Native resources are allocated inside remember{}: if the composition
    // is abandoned before DisposableEffect registers, the panel leaks.
    // Standard Compose caveat, negligible for an application-scoped popup.
    val host: StandalonePopupHost =
        remember {
            when (Platform.Current) {
                Platform.MacOS -> TaoStandalonePopupHostMac()
                else -> TaoStandalonePopupHost()
            }
        }
    if (!host.isValid) return

    DisposableEffect(Unit) {
        onDispose { host.dispose() }
    }

    // Bridge the caller's composition locals into the panel's own scene
    // (fresh scenes don't inherit locals), but keep the scene's density —
    // the outer application composition runs with GlobalDensity(1f).
    // Both the locals and the content go through State reads INSIDE the
    // panel composition, so outer changes (dark mode, strings…) recompose
    // the panel instead of freezing first-composition values.
    val outerLocals = rememberUpdatedState(currentCompositionLocalContext)
    val currentContent = rememberUpdatedState(content)
    val sceneDensity = Density(host.scale)

    SideEffect {
        host.onPreviewKeyEvent = onPreviewKeyEvent
        host.onKeyEvent = onKeyEvent
    }

    // All host mutations run AFTER the current composition pass: setContent
    // composes the panel's own scene, which must never nest inside an active
    // composition of the caller's composition.
    LaunchedEffect(host) {
        host.setContent {
            CompositionLocalProvider(outerLocals.value) {
                CompositionLocalProvider(LocalDensity provides sceneDensity) {
                    currentContent.value()
                }
            }
        }
    }
    LaunchedEffect(host, position, size) {
        host.setFrame(
            xDp = position.x.value,
            yDp = position.y.value,
            widthDp = size.width.value,
            heightDp = size.height.value,
        )
    }
    LaunchedEffect(host, focusable) { host.setFocusable(focusable) }
    LaunchedEffect(host, visible) { host.setVisible(visible) }

    val outsideClickState = rememberUpdatedState(onOutsideClick)
    LaunchedEffect(host, onOutsideClick != null) {
        host.setOutsideClickListener(
            if (onOutsideClick != null) {
                { outsideClickState.value?.invoke() }
            } else {
                null
            },
        )
    }
}
