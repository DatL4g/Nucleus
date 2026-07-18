package dev.nucleusframework.window.tao.scene

import dev.nucleusframework.window.tao.popup.TaoPopupHostLinux
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayerLinux
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Linux port of [TaoComposeSceneContext] (macOS) /
 * [TaoComposeSceneContextWindows]. Installed on the main host scene when
 * `nativePopupLayers = true`: every Compose Popup / DropdownMenu / Tooltip
 * layer materialises as a [TaoPopupSceneLayerLinux] (a Tao popup window)
 * instead of drawing inside the host's EGL render target.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneContextLinux(
    override val platformContext: PlatformContext,
    private val popupHost: TaoPopupHostLinux,
) : ComposeSceneContext {
    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        focusable: Boolean,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer =
        TaoPopupSceneLayerLinux(
            host = popupHost,
            initialDensity = density,
            initialLayoutDirection = layoutDirection,
            initialFocusable = focusable,
            parentCompositionContext = compositionContext,
        )
}
