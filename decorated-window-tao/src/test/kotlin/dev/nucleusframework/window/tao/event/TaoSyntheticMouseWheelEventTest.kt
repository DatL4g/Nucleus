package dev.nucleusframework.window.tao.event

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import java.awt.event.InputEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaoSyntheticMouseWheelEventTest {
    @Test
    fun syntheticEventCarriesAwtScrollMetadata() {
        val event =
            TaoSyntheticMouseWheelEvent.create(
                event = TaoPointerScrollEvent(dxAwt = 0f, dyAwt = 1.25f, scrollAmount = 3),
                x = 10.4f,
                y = 20.6f,
                keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true, isCtrlPressed = true),
            )

        assertEquals(3, event.scrollAmount)
        assertEquals(1.25, event.preciseWheelRotation)
        assertEquals(1, event.wheelRotation)
        assertEquals(10, event.x)
        assertEquals(21, event.y)
        assertTrue((event.modifiersEx and InputEvent.SHIFT_DOWN_MASK) != 0)
        assertTrue((event.modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0)
    }
}
