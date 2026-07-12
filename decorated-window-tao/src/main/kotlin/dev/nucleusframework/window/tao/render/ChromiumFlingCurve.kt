package dev.nucleusframework.window.tao.render

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Faithful port of Chromium's touchpad fling curve
 * (`ui/events/gestures/fling_curve.cc`) — the deceleration physics behind
 * Chrome's post-flick glide.
 *
 * The curve is one universal deceleration profile in pixels,
 * `position(t) = α·e^(−γt) − β·t − α`, with `velocity(t)` its derivative
 * (α = −5707.62, β = 172, γ = 3.7). A fling with release velocity `v₀`
 * enters the curve at the time offset where the curve's velocity equals
 * `v₀` and rides it until the velocity reaches zero (~1.3 s from the very
 * top). A faster flick enters earlier and therefore travels further; the
 * tail — the "slower and slower" feel — is identical for every fling.
 *
 * The 2-D direction is preserved through the displacement ratio exactly as
 * in Chromium: the dominant axis rides the curve at full scale, the other
 * axis is scaled proportionally.
 */
internal class ChromiumFlingCurve(
    velocityX: Float,
    velocityY: Float,
) {
    private val maxAxisVelocity: Float = max(abs(velocityX), abs(velocityY))
    private val startVelocity: Float = min(maxAxisVelocity, velocityAtTime(0f))

    /** False when the release velocity can't produce any motion. */
    val isValid: Boolean = startVelocity > 0f

    private val curveDuration: Float = timeAtVelocity(0f)
    private val timeOffset: Float = if (isValid) timeAtVelocity(startVelocity) else 0f
    private val positionOffset: Float = positionAtTime(timeOffset)

    // Chromium divides by the CLAMPED max (its callers pre-clamp velocity via
    // kMaxFlingVelocity, so the ratio stays ≤1 there). Our velocities come
    // straight from a tracker estimate, so divide by the unclamped max — an
    // implausibly fast estimate rides the full curve instead of multiplying it.
    private val displacementRatioX: Float = if (isValid) velocityX / maxAxisVelocity else 0f
    private val displacementRatioY: Float = if (isValid) velocityY / maxAxisVelocity else 0f

    /** Seconds of glide remaining from the fling start. */
    val remainingSeconds: Float
        get() = if (isValid) curveDuration - timeOffset else 0f

    /** Cumulative glide offset in px, [elapsedSeconds] after the fling start. */
    fun offsetAt(elapsedSeconds: Float): Offset {
        if (!isValid) return Offset.Zero
        val t = (elapsedSeconds + timeOffset).coerceIn(timeOffset, curveDuration)
        val displacement = positionAtTime(t) - positionOffset
        return Offset(displacement * displacementRatioX, displacement * displacementRatioY)
    }

    fun isFinishedAt(elapsedSeconds: Float): Boolean = !isValid || elapsedSeconds + timeOffset >= curveDuration

    private companion object {
        // Chromium ui/events/gestures/fling_curve.cc (kDefaultAlpha/Beta/Gamma).
        const val ALPHA = -5707.62f
        const val BETA = 172f
        const val GAMMA = 3.7f

        fun positionAtTime(t: Float): Float = ALPHA * exp(-GAMMA * t) - BETA * t - ALPHA

        fun velocityAtTime(t: Float): Float = -ALPHA * GAMMA * exp(-GAMMA * t) - BETA

        fun timeAtVelocity(v: Float): Float = -ln((v + BETA) / (-ALPHA * GAMMA)) / GAMMA
    }
}
