package dev.nucleusframework.window.tao.event

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.nucleusframework.window.tao.TaoModifierMask

internal fun taoKeyboardModifiers(modifiers: Int): PointerKeyboardModifiers =
    PointerKeyboardModifiers(
        isCtrlPressed = (modifiers and TaoModifierMask.CONTROL) != 0,
        isMetaPressed = (modifiers and TaoModifierMask.META) != 0,
        isAltPressed = (modifiers and TaoModifierMask.ALT) != 0,
        isShiftPressed = (modifiers and TaoModifierMask.SHIFT) != 0,
    )
