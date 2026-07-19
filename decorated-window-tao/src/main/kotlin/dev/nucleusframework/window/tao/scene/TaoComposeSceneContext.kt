package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.popup.TaoPopupHost
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayer
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * `ComposeSceneContext` used by macOS overlay scenes that must lift
 * Compose `Popup` / `DropdownMenu` / `Tooltip` content above embedded
 * AppKit views. The main window scene stays fully Compose-rendered via
 * `CanvasLayersComposeScene`, matching Windows and Linux.
 *
 * Threading: `createLayer` is invoked from the overlay scene's composition,
 * which runs on the macOS main thread.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneContext(
    override val platformContext: PlatformContext,
    private val popupHost: TaoPopupHost,
) : ComposeSceneContext {
    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer =
        TaoPopupSceneLayer(
            host = popupHost,
            initialDensity = density,
            initialLayoutDirection = layoutDirection,
            initialFocusable = focusable,
            parentCompositionContext = compositionContext,
        )
}
