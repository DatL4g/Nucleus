package dev.nucleusframework.window.tao.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChromeScrollConfigTest {
    @Test
    fun integerTicksAnimate() {
        val config = ChromeScrollConfig()
        assertFalse(config.isPreciseTick(rotation = 1.0, nowMs = 0L))
        assertFalse(config.isPreciseTick(rotation = -3.0, nowMs = 10L))
    }

    @Test
    fun fractionalTicksApplyImmediately() {
        val config = ChromeScrollConfig()
        assertTrue(config.isPreciseTick(rotation = 1.47, nowMs = 0L))
        assertTrue(config.isPreciseTick(rotation = -0.5, nowMs = 10L))
    }

    @Test
    fun integerTickInsideTouchpadSessionStaysImmediate() {
        val config = ChromeScrollConfig()
        assertTrue(config.isPreciseTick(rotation = 1.47, nowMs = 0L))
        // Quantized-to-integer event mid-gesture must not flip the burst
        // back to the animated path.
        assertTrue(config.isPreciseTick(rotation = 1.0, nowMs = 300L))
    }

    @Test
    fun touchpadSessionExpiresBackToAnimatedWheel() {
        val config = ChromeScrollConfig()
        assertTrue(config.isPreciseTick(rotation = 1.47, nowMs = 0L))
        assertFalse(
            config.isPreciseTick(
                rotation = 1.0,
                nowMs = ChromeScrollConfig.TOUCHPAD_SESSION_MS + 1L,
            ),
        )
    }
}
