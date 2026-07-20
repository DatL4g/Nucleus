package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize

/**
 * Mutable [WindowInfo] backed by snapshot state, shared by the macOS, Linux
 * and Windows scene hosts (previously duplicated as three identical classes).
 * Mirrors the upstream `WindowInfoImpl` (which is `internal` to compose-ui).
 *
 * `containerSize` is read by `Popup.skiko.kt` (via `LocalWindowInfo.current`)
 * to compute the available area for popup positioning. The host must update it
 * on every resize / scale-factor change, otherwise popup positioning collapses
 * to a zero-sized window and menus consistently flip above the click.
 */
internal class TaoWindowInfo : WindowInfo {
    override var isWindowFocused: Boolean by mutableStateOf(true)
    override var keyboardModifiers: PointerKeyboardModifiers by mutableStateOf(PointerKeyboardModifiers())
    override var containerSize: IntSize by mutableStateOf(IntSize.Zero)
    override var containerDpSize: DpSize by mutableStateOf(DpSize.Zero)
}
