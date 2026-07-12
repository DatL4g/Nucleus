package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.withFrameNanos
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
    /**
     * Gesture samples (timeMillis, dxPx, dyPx) for the release-velocity
     * estimate. A windowed distance-over-time mean is used instead of an
     * impulse/LSQ velocity tracker: Windows synthesizes coalesced wheel
     * quanta (a single 2-tick event = 200px at one instant ≈ a 25 000 px/s
     * point-wise spike), which tracker strategies built for touch positions
     * wildly overestimate — measured glides hit the curve's 20 946 px/s cap
     * on ordinary flicks. Total distance ÷ elapsed time over the last
     * [VELOCITY_WINDOW_MS] is immune to the burst shape.
     */
    private class Sample(
        val timeMillis: Long,
        val dxPx: Float,
        val dyPx: Float,
    )

    private val samples = ArrayDeque<Sample>()
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
        samples.addLast(Sample(timeMillis, dxTicks * pixelsPerTick, dyTicks * pixelsPerTick))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
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
        dev.nucleusframework.window.tao.TaoScrollDiagnostics.softwareFlingActive = false
    }

    private fun resetGesture() {
        samples.clear()
        sawFractionalTick = false
    }

    /**
     * Windowed release velocity: distance ÷ time over the samples inside the
     * last [VELOCITY_WINDOW_MS] before the final event. Requires
     * [MIN_SAMPLES] events (a lone coalesced quantum has no measurable
     * duration) and caps the result at [MAX_FLING_VELOCITY_PX_PER_S] — the
     * curve tops out at 20 946 px/s, far beyond any deliberate gesture.
     */
    private fun releaseVelocityPxPerS(): Pair<Float, Float>? {
        val last = samples.lastOrNull() ?: return null
        val windowed = samples.filter { last.timeMillis - it.timeMillis <= VELOCITY_WINDOW_MS }
        if (windowed.size < MIN_SAMPLES) return null
        // The first sample's delta accumulated BEFORE the window starts;
        // count only the time span and distance between first and last.
        val spanMs = (last.timeMillis - windowed.first().timeMillis).coerceAtLeast(1L)
        var dx = 0f
        var dy = 0f
        for (i in 1 until windowed.size) {
            dx += windowed[i].dxPx
            dy += windowed[i].dyPx
        }
        val vx = dx * MS_PER_SECOND / spanMs
        val vy = dy * MS_PER_SECOND / spanMs
        val magnitude = max(abs(vx), abs(vy))
        if (magnitude <= 0f) return null
        val scale =
            if (magnitude > MAX_FLING_VELOCITY_PX_PER_S) {
                MAX_FLING_VELOCITY_PX_PER_S / magnitude
            } else {
                1f
            }
        return (vx * scale) to (vy * scale)
    }

    private fun maybeStartFling() {
        val velocity = releaseVelocityPxPerS()
        val touchpadGesture = sawFractionalTick
        resetGesture()
        if (!touchpadGesture || velocity == null) return
        val (vx, vy) = velocity
        if (max(abs(vx), abs(vy)) < MIN_FLING_VELOCITY_PX_PER_S) return
        val curve = ChromiumFlingCurve(vx, vy)
        if (!curve.isValid) return
        dev.nucleusframework.window.tao.TaoScrollDiagnostics.softwareFlingActive = true
        flingJob =
            scope.launch {
                try {
                    var emittedX = 0f
                    var emittedY = 0f
                    // Baseline on the launch instant, not the first frame tick —
                    // the host frame clock runs on the same System.nanoTime base,
                    // and burning a frame just to read the clock would add ~16ms
                    // to the lift-to-glide gap (visible on a hard flick).
                    val startNanos = System.nanoTime()
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
                } finally {
                    dev.nucleusframework.window.tao.TaoScrollDiagnostics.softwareFlingActive = false
                }
            }
    }

    private fun isFractional(ticks: Float): Boolean = abs(ticks - round(ticks)) > FRACTIONAL_TICK_EPSILON

    internal companion object {
        /**
         * Quiet gap that ends a gesture. Precision-touchpad wheel synthesis
         * arrives at 60–125 Hz (gaps ≤ ~16 ms), so 35 ms is still safely
         * above one inter-event gap while shaving the lift-to-glide freeze —
         * at 6 000 px/s a 50 ms gap read as a visible hitch on hard flicks.
         * A premature fire mid-gesture self-heals: the next real event
         * cancels the glide and the windowed velocity barely differs.
         */
        const val GESTURE_END_MS = 35L

        /** Below this release velocity a glide wouldn't be perceptible. */
        const val MIN_FLING_VELOCITY_PX_PER_S = 100f

        /**
         * Release-velocity ceiling. Chrome's DirectManipulation inertia never
         * launches anywhere near the curve's 20 946 px/s top; 6 000 px/s
         * (≈ Avalonia's 8 000 cap, Chromium gesture configs are lower still)
         * bounds the glide at ~1 450 px for the hardest flick.
         */
        const val MAX_FLING_VELOCITY_PX_PER_S = 6_000f

        /** Sliding window for the distance-over-time velocity estimate. */
        const val VELOCITY_WINDOW_MS = 120L

        /** A single coalesced quantum has no measurable duration — no glide. */
        const val MIN_SAMPLES = 3

        /** Ring-buffer bound; ~2× any realistic 120 ms event burst. */
        const val MAX_SAMPLES = 32

        const val MS_PER_SECOND = 1_000f

        /** Skip emissions Compose would discard as sub-visible anyway. */
        const val MIN_EMIT_PX = 0.01f

        /** Mirrors [ChromeScrollConfig.FRACTIONAL_TICK_EPSILON]. */
        const val FRACTIONAL_TICK_EPSILON = 0.001f

        const val NANOS_PER_SECOND = 1e9f
    }
}
