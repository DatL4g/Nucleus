package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class TaoWindowScrollTest {
    @Test
    fun lineScrollKeepsWheelRotationSeparateFromScrollAmount() {
        val event = dispatchScroll(TaoEventCode.SCROLL_LINE, dx = 100, dy = -200)

        assertEquals(-1f, event.dxAwt)
        assertEquals(2f, event.dyAwt)
        assertEquals(expectedLineScrollAmount(), event.scrollAmount)
    }

    @Test
    fun pixelScrollMirrorsMacOsAwtPreciseWheelRotationScale() {
        val event = dispatchScroll(TaoEventCode.SCROLL_PIXEL, dx = 1000, dy = -2000)

        assertEquals(-1f, event.dxAwt)
        assertEquals(2f, event.dyAwt)
        assertEquals(1, event.scrollAmount)
    }

    private fun dispatchScroll(
        code: Int,
        dx: Int,
        dy: Int,
    ): TaoPointerScrollEvent {
        var event: TaoPointerScrollEvent? = null
        TaoWindow(handle = 1L).apply {
            onPointerScroll { event = it }
            dispatch(code, dx, dy)
        }
        return requireNotNull(event)
    }

    private fun expectedLineScrollAmount(): Int =
        when (Platform.Current) {
            Platform.Linux -> 3
            else -> 1
        }
}
