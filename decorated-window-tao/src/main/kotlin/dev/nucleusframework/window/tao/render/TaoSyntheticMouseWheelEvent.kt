package dev.nucleusframework.window.tao.render

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.roundToInt

internal object TaoSyntheticMouseWheelEvent {
    private val source: Component = JPanel()

    fun create(
        event: TaoPointerScrollEvent,
        x: Float,
        y: Float,
        keyboardModifiers: PointerKeyboardModifiers,
    ): MouseWheelEvent {
        val preciseWheelRotation = event.primaryAxisDelta.toDouble()
        return MouseWheelEvent(
            source,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            keyboardModifiers.toAwtModifiersEx(),
            x.roundToInt(),
            y.roundToInt(),
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            event.scrollAmount.coerceAtLeast(1),
            preciseWheelRotation.roundToInt(),
            preciseWheelRotation,
        )
    }

    private val TaoPointerScrollEvent.primaryAxisDelta: Float
        get() = if (abs(dxAwt) >= abs(dyAwt)) dxAwt else dyAwt

    private fun PointerKeyboardModifiers.toAwtModifiersEx(): Int =
        (if (isShiftPressed) InputEvent.SHIFT_DOWN_MASK else 0) or
            (if (isCtrlPressed) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (isAltPressed) InputEvent.ALT_DOWN_MASK else 0) or
            (if (isMetaPressed) InputEvent.META_DOWN_MASK else 0)
}
