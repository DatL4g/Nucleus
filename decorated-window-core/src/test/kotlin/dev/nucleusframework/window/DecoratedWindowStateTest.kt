package dev.nucleusframework.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoratedWindowStateTest {
    @Test
    fun resizableDefaultsToTrue() {
        assertTrue(DecoratedWindowState.of().isResizable)
    }

    @Test
    fun ofSetsResizableBit() {
        assertFalse(DecoratedWindowState.of(resizable = false).isResizable)
        assertTrue(DecoratedWindowState.of(resizable = true).isResizable)
    }

    @Test
    fun copyTogglesResizableWithoutAffectingOtherBits() {
        val state =
            DecoratedWindowState.of(
                fullscreen = true,
                minimized = true,
                maximized = true,
                active = false,
                tiled = true,
                resizable = true,
            )
        val nonResizable = state.copy(resizable = false)

        assertFalse(nonResizable.isResizable)
        assertTrue(nonResizable.isFullscreen)
        assertTrue(nonResizable.isMinimized)
        assertTrue(nonResizable.isMaximized)
        assertFalse(nonResizable.isActive)
        assertTrue(nonResizable.isTiled)
    }

    @Test
    fun copyPreservesResizableByDefault() {
        val state = DecoratedWindowState.of(resizable = false)
        assertFalse(state.copy(active = false).isResizable)
    }
}
