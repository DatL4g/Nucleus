package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Windows port of [TaoComposeSceneContext]. Used by overlay scenes that
 * explicitly need popups outside their own HWND bounds. The main host
 * scene follows the Linux backend and keeps its popups inside the same
 * Compose render target.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneContextWindows(
    override val platformContext: PlatformContext,
    private val popupHost: TaoPopupHostWindows,
) : ComposeSceneContext {
    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer =
        TaoPopupSceneLayerWindows(
            host = popupHost,
            initialDensity = density,
            initialLayoutDirection = layoutDirection,
            initialFocusable = focusable,
            parentCompositionContext = compositionContext,
        )
}
