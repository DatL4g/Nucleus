package dev.nucleusframework.window.tao.render

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class TaoWheelPinchZoomTest {
    @Test
    fun fullWheelDeltaProducesModerateZoomStep() {
        val step = TaoWheelPinchZoom.stepFromWheelDelta(1f)

        assertEquals(1.05946, step.toDouble(), absoluteTolerance = 0.0001)
    }

    @Test
    fun fractionalDeltasAccumulateLikeOneFullDelta() {
        val quarterStep = TaoWheelPinchZoom.stepFromWheelDelta(0.25f).toDouble()
        val fullStep = TaoWheelPinchZoom.stepFromWheelDelta(1f).toDouble()

        assertEquals(fullStep, quarterStep.pow(4), absoluteTolerance = 0.0001)
    }

    @Test
    fun zoomOutIsInverseOfZoomIn() {
        val zoomIn = TaoWheelPinchZoom.stepFromWheelDelta(1f).toDouble()
        val zoomOut = TaoWheelPinchZoom.stepFromWheelDelta(-1f).toDouble()

        assertEquals(1.0, zoomIn * zoomOut, absoluteTolerance = 0.0001)
    }
}
