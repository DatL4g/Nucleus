package dev.nucleusframework.window.tao.render

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class TaoWindowsPinchZoomTest {
    @Test
    fun fullWheelDeltaProducesModerateZoomStep() {
        val step = TaoWindowsPinchZoom.stepFromWheelDelta(1f)

        assertEquals(1.05946, step.toDouble(), absoluteTolerance = 0.0001)
    }

    @Test
    fun fractionalDeltasAccumulateLikeOneFullDelta() {
        val quarterStep = TaoWindowsPinchZoom.stepFromWheelDelta(0.25f).toDouble()
        val fullStep = TaoWindowsPinchZoom.stepFromWheelDelta(1f).toDouble()

        assertEquals(fullStep, quarterStep.pow(4), absoluteTolerance = 0.0001)
    }

    @Test
    fun zoomOutIsInverseOfZoomIn() {
        val zoomIn = TaoWindowsPinchZoom.stepFromWheelDelta(1f).toDouble()
        val zoomOut = TaoWindowsPinchZoom.stepFromWheelDelta(-1f).toDouble()

        assertEquals(1.0, zoomIn * zoomOut, absoluteTolerance = 0.0001)
    }
}
