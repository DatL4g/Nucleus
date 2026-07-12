package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives [TaoWheelFling] against a real [BroadcastFrameClock] pumped from a
 * background thread (standing in for the host render loop). Event
 * timestamps are synthetic, so tracked velocities are deterministic; frame
 * timing only affects emission granularity, never the glide's total
 * distance (deltas are derived from absolute curve positions).
 */
class TaoWheelFlingTest {
    private class Harness : AutoCloseable {
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(Dispatchers.Default + frameClock + SupervisorJob())
        val emitted = ConcurrentLinkedQueue<Pair<Float, Float>>()
        val fling =
            TaoWheelFling(scope = scope, pixelsPerTick = 100f) { dx, dy ->
                emitted.add(dx to dy)
            }

        @Volatile
        private var pumping = true

        init {
            Thread {
                while (pumping) {
                    frameClock.sendFrame(System.nanoTime())
                    Thread.sleep(4)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        /** ~19125 px/s touchpad flick: 8 fractional-tick events, 8 ms apart. */
        fun flickTouchpad(dyTicks: Float = 1.53f) {
            var t = 0L
            repeat(8) {
                fling.onUserScroll(0f, dyTicks, t)
                t += 8
            }
        }

        fun awaitGlideStart(timeoutMs: Long = 1_000): Boolean = pollUntil(timeoutMs) { fling.isFlinging }

        fun awaitGlideEnd(timeoutMs: Long = 4_000): Boolean =
            awaitGlideStart(timeoutMs) && pollUntil(timeoutMs) { !fling.isFlinging }

        private fun pollUntil(
            timeoutMs: Long,
            condition: () -> Boolean,
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!condition()) {
                if (System.currentTimeMillis() > deadline) return false
                Thread.sleep(5)
            }
            return true
        }

        override fun close() {
            pumping = false
            scope.cancel()
        }
    }

    @Test
    fun touchpadFlickGlidesWithChromiumDistance() {
        Harness().use { h ->
            h.flickTouchpad()
            assertTrue(h.awaitGlideEnd(), "glide never started or never finished")
            val deltas = h.emitted.toList()
            assertTrue(deltas.size > 10, "glide should span many frames, got ${deltas.size}")
            assertTrue(deltas.all { it.second > 0f }, "glide must keep the flick direction")
            // ~19125 px/s on the Chromium curve ≈ 4950 px ≈ 49.5 ticks; leave
            // headroom for the velocity-tracker estimate.
            val totalTicks = deltas.map { it.second }.sum()
            assertTrue(totalTicks in 30f..70f, "expected ~49 ticks of glide, got $totalTicks")
        }
    }

    @Test
    fun notchedWheelDoesNotGlide() {
        Harness().use { h ->
            var t = 0L
            repeat(8) {
                h.fling.onUserScroll(0f, 2f, t) // exact integer ticks = real wheel
                t += 8
            }
            assertFalse(h.awaitGlideStart(timeoutMs = 300))
            assertTrue(h.emitted.isEmpty(), "integer-tick gestures must not glide")
        }
    }

    @Test
    fun slowTouchpadScrollDoesNotGlide() {
        Harness().use { h ->
            var t = 0L
            repeat(5) {
                h.fling.onUserScroll(0f, 0.005f, t) // ~12 px/s, below MIN_FLING
                t += 40
            }
            assertFalse(h.awaitGlideStart(timeoutMs = 300))
            assertTrue(h.emitted.isEmpty(), "sub-threshold release velocity must not glide")
        }
    }

    @Test
    fun newScrollEventCancelsGlide() {
        Harness().use { h ->
            h.flickTouchpad()
            assertTrue(h.awaitGlideStart(), "glide never started")
            h.fling.onUserScroll(0f, -0.4f, 1_000L)
            assertFalse(h.fling.isFlinging, "real input must preempt the glide")
        }
    }

    @Test
    fun cancelStopsGlide() {
        Harness().use { h ->
            h.flickTouchpad()
            assertTrue(h.awaitGlideStart(), "glide never started")
            h.fling.cancel()
            assertFalse(h.fling.isFlinging)
            val sizeAfterCancel = h.emitted.size
            Thread.sleep(100)
            assertTrue(
                h.emitted.size <= sizeAfterCancel + 1,
                "emissions must stop after cancel()",
            )
        }
    }
}
