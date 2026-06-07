package dev.nucleusframework.window.tao.render

import kotlin.math.pow

internal object TaoWindowsPinchZoom {
    private const val WHEEL_DELTAS_PER_DOUBLING: Float = 12f

    /**
     * Windows reports Ctrl+wheel / precision-touchpad pinch as WHEEL_DELTA-normalized
     * deltas. Treat them as a continuous stream: fractional deltas compose to the
     * same zoom as a single larger delta, and one full wheel delta stays moderate.
     */
    fun stepFromWheelDelta(delta: Float): Float = if (delta == 0f) 1f else 2f.pow(delta / WHEEL_DELTAS_PER_DOUBLING)
}
