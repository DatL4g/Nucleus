package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/**
 * Platform-agnostic surface for a standalone transparent popup panel.
 * Implemented per-platform: [TaoStandalonePopupHost] (Windows, EGL/DComp) and
 * [TaoStandalonePopupHostMac] (macOS, Metal/CAMetalLayer). Lets
 * `dev.nucleusframework.window.tao.TaoStandalonePopup` route per platform
 * without a per-platform composable.
 */
internal interface StandalonePopupHost {
    val isValid: Boolean
    val scale: Float

    var onPreviewKeyEvent: ((KeyEvent) -> Boolean)?
    var onKeyEvent: ((KeyEvent) -> Boolean)?

    fun setContent(content: @Composable () -> Unit)

    /** Logical (dp) screen position and size of the panel. */
    fun setFrame(
        xDp: Float,
        yDp: Float,
        widthDp: Float,
        heightDp: Float,
    )

    fun setVisible(visible: Boolean)

    fun setFocusable(focusable: Boolean)

    fun setOutsideClickListener(listener: (() -> Unit)?)

    fun dispose()
}
