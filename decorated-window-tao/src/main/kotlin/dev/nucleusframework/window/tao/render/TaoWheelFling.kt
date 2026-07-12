package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Synthesizes Chrome-like scroll inertia for the Windows wheel path.
 *
 * macOS pushes decaying momentum events after a trackpad flick; Windows
 * precision touchpads stop the `WM_MOUSEWHEEL` stream dead at finger lift,
 * so a flick halts abruptly. Compose's own wheel fling can't help: it is
 * gated off for default fling behaviors (`shouldBeTriggeredByMouseWheel`)
 * and the default is resolved per scrollable with no global hook. So the
 * glide is synthesized here at the input layer instead — velocity-track the
 * real events, and once the stream goes quiet, emit synthetic wheel ticks
 * riding [ChromiumFlingCurve] until it decays to rest: the same event
 * stream a momentum-scrolling OS/driver would deliver, consumed by the
 * existing precise-wheel direct path.
 *
 * Only gestures containing fractional ticks (precision touchpads) glide;
 * notched mouse wheels keep the plain animated scroll, mirroring Chrome
 * (DirectManipulation inertia vs. wheel tick animation). Drivers that
 * already synthesize inertia events self-correct: their decaying tail keeps
 * the gesture alive and leaves only a tiny release velocity here.
 *
 * Threading: single-threaded on the Tao event-loop thread — [onUserScroll]
 * and [cancel] run from native event dispatch, and the arm/fling coroutines
 * resume through the host's flushing dispatcher, drained on that thread.
 */
internal class TaoWheelFling(
    private val scope: CoroutineScope,
    private val pixelsPerTick: Float,
    private val emitTicks: (dxTicks: Float, dyTicks: Float) -> Unit,
) {
    private val xVelocity = VelocityTracker1D(isDataDifferential = true)
    private val yVelocity = VelocityTracker1D(isDataDifferential = true)
    private var armJob: Job? = null

    // Volatile only for cross-thread visibility in tests; production access
    // is single-threaded (event-loop thread).
    @Volatile
    private var flingJob: Job? = null
    private var sawFractionalTick = false

    /** True while a synthetic glide is emitting ticks. */
    val isFlinging: Boolean
        get() = flingJob?.isActive == true

    /**
     * Feeds a real user wheel event (tick units, AWT sign convention). Any
     * active glide is cancelled — new input always takes over, as in Chrome.
     */
    fun onUserScroll(
        dxTicks: Float,
        dyTicks: Float,
        timeMillis: Long,
    ) {
        cancelFling()
        if (isFractional(dxTicks) || isFractional(dyTicks)) sawFractionalTick = true
        xVelocity.addDataPoint(timeMillis, dxTicks * pixelsPerTick)
        yVelocity.addDataPoint(timeMillis, dyTicks * pixelsPerTick)
        armJob?.cancel()
        armJob =
            scope.launch {
                delay(GESTURE_END_MS)
                maybeStartFling()
            }
    }

    /** Stops tracking and any active glide (button press, focus loss, detach). */
    fun cancel() {
        armJob?.cancel()
        armJob = null
        cancelFling()
        resetGesture()
    }

    private fun cancelFling() {
        flingJob?.cancel()
        flingJob = null
    }

    private fun resetGesture() {
        xVelocity.resetTracking()
        yVelocity.resetTracking()
        sawFractionalTick = false
    }

    private fun maybeStartFling() {
        val vx = xVelocity.calculateVelocity()
        val vy = yVelocity.calculateVelocity()
        val touchpadGesture = sawFractionalTick
        resetGesture()
        if (!touchpadGesture) return
        if (max(abs(vx), abs(vy)) < MIN_FLING_VELOCITY_PX_PER_S) return
        val curve = ChromiumFlingCurve(vx, vy)
        if (!curve.isValid) return
        flingJob =
            scope.launch {
                var emittedX = 0f
                var emittedY = 0f
                val startNanos = withFrameNanos { it }
                while (true) {
                    val elapsed = (withFrameNanos { it } - startNanos) / NANOS_PER_SECOND
                    val target = curve.offsetAt(elapsed)
                    val dx = target.x - emittedX
                    val dy = target.y - emittedY
                    if (abs(dx) > MIN_EMIT_PX || abs(dy) > MIN_EMIT_PX) {
                        emittedX += dx
                        emittedY += dy
                        emitTicks(dx / pixelsPerTick, dy / pixelsPerTick)
                    }
                    if (curve.isFinishedAt(elapsed)) break
                }
            }
    }

    private fun isFractional(ticks: Float): Boolean = abs(ticks - round(ticks)) > FRACTIONAL_TICK_EPSILON

    internal companion object {
        /** Quiet gap that ends a gesture — matches Compose's `ScrollProgressTimeout`. */
        const val GESTURE_END_MS = 50L

        /** Below this release velocity a glide wouldn't be perceptible. */
        const val MIN_FLING_VELOCITY_PX_PER_S = 100f

        /** Skip emissions Compose would discard as sub-visible anyway. */
        const val MIN_EMIT_PX = 0.01f

        /** Mirrors [ChromeScrollConfig.FRACTIONAL_TICK_EPSILON]. */
        const val FRACTIONAL_TICK_EPSILON = 0.001f

        const val NANOS_PER_SECOND = 1e9f
    }
}
