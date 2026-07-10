package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaoWindowResizableTest {
    @Test
    fun reflectsCreationFlag() {
        assertTrue(TaoWindow(handle = 1L).isResizable)
        assertTrue(TaoWindow(handle = 2L, isResizable = true).isResizable)
        assertFalse(TaoWindow(handle = 3L, isResizable = false).isResizable)
    }
}
