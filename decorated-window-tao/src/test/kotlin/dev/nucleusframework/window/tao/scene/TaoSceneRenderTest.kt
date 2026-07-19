@file:OptIn(ExperimentalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage-1 offscreen rendering tests: the scene renders through the production
 * CPU record path ([recordSceneToPicture]) with no native window, and pixels
 * land exactly where Compose laid them out.
 */
class TaoSceneRenderTest {
    @Test
    fun `solid background fills the whole frame`() =
        runTaoSceneTest(width = 100, height = 80) {
            setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            assertEquals(RED, pixelAt(0, 0))
            assertEquals(RED, pixelAt(99, 79))
            assertEquals(RED, pixelAt(50, 40))
        }

    @Test
    fun `box is drawn at its layout position`() =
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.offset(x = 20.dp, y = 30.dp).size(10.dp).background(Color.Blue))
                }
            }
            assertEquals(BLUE, pixelAt(25, 35))
            assertEquals(WHITE, pixelAt(10, 10))
            assertEquals(WHITE, pixelAt(35, 35))
        }

    @Test
    fun `density scales layout to physical pixels`() =
        runTaoSceneTest(width = 100, height = 100, density = 2f) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.size(10.dp).background(Color.Blue))
                }
            }
            // 10dp at 2x density = 20 physical px
            assertEquals(BLUE, pixelAt(19, 19))
            assertEquals(WHITE, pixelAt(21, 21))
        }

    @Test
    fun `state change recomposes and repaints on the next frame`() =
        runTaoSceneTest(width = 50, height = 50) {
            var red by mutableStateOf(true)
            setContent {
                Box(Modifier.fillMaxSize().background(if (red) Color.Red else Color.Green))
            }
            assertEquals(RED, pixelAt(25, 25))
            red = false
            frame()
            assertEquals(GREEN, pixelAt(25, 25))
        }

    @Test
    fun `hover enter and exit drive pointer-event state`() =
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                var hovered by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .onPointerEvent(PointerEventType.Enter) { hovered = true }
                            .onPointerEvent(PointerEventType.Exit) { hovered = false }
                            .background(if (hovered) Color.Blue else Color.Black),
                    )
                }
            }
            assertEquals(BLACK, pixelAt(20, 20))
            moveMouse(20f, 20f)
            assertEquals(BLUE, pixelAt(20, 20))
            moveMouse(80f, 80f)
            assertEquals(BLACK, pixelAt(20, 20))
        }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
