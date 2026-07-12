package dev.nucleusframework.window.tao.render

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Expected values precomputed from Chromium's closed-form curve
 * (`ui/events/gestures/fling_curve.cc`, α=−5707.62 β=172 γ=3.7):
 * duration-from-top = 1.30011 s, max curve velocity = 20946 px/s,
 * distance(300) = 34.15 px, distance(5000) = 1193.13 px,
 * distance(8000) = 1982.68 px, full-curve distance = 5437.52 px.
 */
class ChromiumFlingCurveTest {
    @Test
    fun zeroVelocityIsInvalid() {
        val curve = ChromiumFlingCurve(0f, 0f)
        assertFalse(curve.isValid)
        assertTrue(curve.isFinishedAt(0f))
        assertEquals(0f, curve.offsetAt(1f).y)
    }

    @Test
    fun glideDistanceMatchesChromiumClosedForm() {
        assertTotalDistance(velocityY = 300f, expectedPx = 34.15f)
        assertTotalDistance(velocityY = 5_000f, expectedPx = 1_193.13f)
        assertTotalDistance(velocityY = 8_000f, expectedPx = 1_982.68f)
    }

    @Test
    fun velocityAboveCurveMaxIsClamped() {
        assertTotalDistance(velocityY = 50_000f, expectedPx = 5_437.52f)
    }

    @Test
    fun negativeVelocityMirrors() {
        assertTotalDistance(velocityY = -5_000f, expectedPx = -1_193.13f)
    }

    @Test
    fun startsAtZeroAndFinishesAtRemainingSeconds() {
        val curve = ChromiumFlingCurve(0f, 5_000f)
        assertEquals(0f, curve.offsetAt(0f).y, absoluteTolerance = 0.01f)
        assertEquals(0.91987f, curve.remainingSeconds, absoluteTolerance = 0.001f)
        assertFalse(curve.isFinishedAt(curve.remainingSeconds - 0.01f))
        assertTrue(curve.isFinishedAt(curve.remainingSeconds + 0.001f))
    }

    @Test
    fun offsetsGrowMonotonicallyWithDecayingDeltas() {
        val curve = ChromiumFlingCurve(0f, 8_000f)
        var previousOffset = 0f
        var previousDelta = Float.MAX_VALUE
        var t = 0.016f
        while (t < curve.remainingSeconds) {
            val offset = curve.offsetAt(t).y
            val delta = offset - previousOffset
            assertTrue(delta > 0f, "offset must keep growing at t=$t")
            assertTrue(delta <= previousDelta + 0.001f, "glide must decelerate at t=$t")
            previousOffset = offset
            previousDelta = delta
            t += 0.016f
        }
    }

    @Test
    fun directionRatioIsPreserved() {
        val curve = ChromiumFlingCurve(-2_500f, 5_000f)
        val end = curve.offsetAt(curve.remainingSeconds)
        assertEquals(-0.5f, end.x / end.y, absoluteTolerance = 0.001f)
        assertEquals(1_193.13f, end.y, absoluteTolerance = 2f)
    }

    private fun assertTotalDistance(
        velocityY: Float,
        expectedPx: Float,
    ) {
        val curve = ChromiumFlingCurve(0f, velocityY)
        assertTrue(curve.isValid)
        val total = curve.offsetAt(curve.remainingSeconds + 1f).y
        assertTrue(
            abs(total - expectedPx) < 2f,
            "expected ~${expectedPx}px for v0=$velocityY, got $total",
        )
    }
}
