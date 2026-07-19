package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 pointer tests, dispatched in the exact shape
 * `TaoComposeSceneHost.onPointerMove/onPointerButton` uses — including the
 * host's own input guards, which are part of the tested contract.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TaoScenePointerTest {
    @Test
    fun `click on a clickable box fires exactly once`() =
        runTaoSceneTest(width = 100, height = 100) {
            var clicks = 0
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(40.dp).clickable { clicks++ })
                }
            }
            click(20f, 20f)
            assertEquals(1, clicks)
            click(20f, 20f)
            assertEquals(2, clicks)
        }

    @Test
    fun `click outside a clickable does nothing`() =
        runTaoSceneTest(width = 100, height = 100) {
            var clicks = 0
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(40.dp).clickable { clicks++ })
                }
            }
            click(80f, 80f)
            assertEquals(0, clicks)
        }

    @Test
    fun `host guard - button event before any cursor move is dropped`() =
        runTaoSceneTest(width = 100, height = 100) {
            var clicks = 0
            setContent {
                Box(Modifier.fillMaxSize().clickable { clicks++ })
            }
            // No moveMouse: the press/release pair must be swallowed by the guard.
            pointerButton(PointerButton.Primary, pressed = true)
            pointerButton(PointerButton.Primary, pressed = false)
            assertEquals(0, clicks)
        }

    @Test
    fun `host guard - stray release without press is dropped`() =
        runTaoSceneTest(width = 100, height = 100) {
            var releases = 0
            setContent {
                Box(
                    Modifier.fillMaxSize().onPointerEvent(PointerEventType.Release) { releases++ },
                )
            }
            moveMouse(50f, 50f)
            pointerButton(PointerButton.Primary, pressed = false)
            assertEquals(0, releases)
        }

    @Test
    fun `host guard - double press closes the stale interaction first`() =
        runTaoSceneTest(width = 100, height = 100) {
            var presses = 0
            var releases = 0
            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Press) { presses++ }
                        .onPointerEvent(PointerEventType.Release) { releases++ },
                )
            }
            moveMouse(50f, 50f)
            pointerButton(PointerButton.Primary, pressed = true)
            // Second press without release — host injects a synthetic Release.
            pointerButton(PointerButton.Primary, pressed = true)
            assertEquals(2, presses)
            assertEquals(1, releases)
        }

    @Test
    fun `right button reaches compose as secondary`() =
        runTaoSceneTest(width = 100, height = 100) {
            var secondary = false
            setContent {
                Box(
                    Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) {
                        secondary = it.buttons.isSecondaryPressed
                    },
                )
            }
            moveMouse(50f, 50f)
            pointerButton(PointerButton.Secondary, pressed = true)
            pointerButton(PointerButton.Secondary, pressed = false)
            assertTrue(secondary)
        }

    @Test
    fun `press move release drives a drag gesture`() =
        runTaoSceneTest(width = 200, height = 200) {
            var dragged by mutableStateOf(Offset.Zero)
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset { IntOffset(dragged.x.roundToInt(), dragged.y.roundToInt()) }
                            .size(40.dp)
                            .background(Color.Blue)
                            .pointerInput(Unit) {
                                detectDragGestures { change, amount ->
                                    change.consume()
                                    dragged += amount
                                }
                            },
                    )
                }
            }
            moveMouse(20f, 20f)
            pointerButton(PointerButton.Primary, pressed = true)
            moveMouse(60f, 50f) // must exceed touch slop
            moveMouse(80f, 70f)
            pointerButton(PointerButton.Primary, pressed = false)
            assertTrue(dragged.x > 0f, "drag must move right (got $dragged)")
            assertTrue(dragged.y > 0f, "drag must move down (got $dragged)")
            // The box visually followed the pointer.
            frame()
            assertEquals(BLUE, pixelAt(60, 55))
        }

    @Test
    fun `hover exit resets hover state via exitPointer`() =
        runTaoSceneTest(width = 100, height = 100) {
            var inside by mutableStateOf(false)
            setContent {
                val hovered = remember { mutableStateOf(false) }
                inside = hovered.value
                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Enter) { hovered.value = true }
                        .onPointerEvent(PointerEventType.Exit) { hovered.value = false },
                )
            }
            moveMouse(50f, 50f)
            assertTrue(inside)
            exitPointer()
            assertEquals(false, inside)
        }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
    }
}
