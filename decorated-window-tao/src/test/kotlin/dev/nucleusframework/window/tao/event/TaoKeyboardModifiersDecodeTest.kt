package dev.nucleusframework.window.tao.event

import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import dev.nucleusframework.window.tao.TaoModifierMask
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exhaustive decode of the wire modifier bitmask into Compose pointer modifiers. */
class TaoKeyboardModifiersDecodeTest {
    @Test
    fun `all sixteen combinations decode exactly`() {
        for (mask in 0..0xF) {
            val decoded = taoKeyboardModifiers(mask)
            assertEquals(mask and TaoModifierMask.SHIFT != 0, decoded.isShiftPressed, "shift, mask=$mask")
            assertEquals(mask and TaoModifierMask.CONTROL != 0, decoded.isCtrlPressed, "ctrl, mask=$mask")
            assertEquals(mask and TaoModifierMask.ALT != 0, decoded.isAltPressed, "alt, mask=$mask")
            assertEquals(mask and TaoModifierMask.META != 0, decoded.isMetaPressed, "meta, mask=$mask")
        }
    }

    @Test
    fun `unknown high bits are ignored`() {
        val decoded = taoKeyboardModifiers(0xFF0)
        assertEquals(false, decoded.isShiftPressed)
        assertEquals(false, decoded.isCtrlPressed)
        assertEquals(false, decoded.isAltPressed)
        assertEquals(false, decoded.isMetaPressed)
    }
}
