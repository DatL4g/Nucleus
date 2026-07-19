package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage-1 popup tests: Compose Popups in the CanvasLayers scene (the same
 * scene type the tao main window host uses, where popup content stays in the
 * window's render target) render, layer above content, and dismiss.
 */
class TaoScenePopupTest {
    @Test
    fun `popup renders above the window content`() =
        runTaoSceneTest(width = 100, height = 100) {
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Popup(offset = IntOffset(20, 20)) {
                        Box(Modifier.size(30.dp).background(Color.Blue))
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
            assertEquals(WHITE, pixelAt(70, 70))
        }

    @Test
    fun `popup disappears when its state is cleared`() =
        runTaoSceneTest(width = 100, height = 100) {
            var show by mutableStateOf(true)
            setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    if (show) {
                        Popup(offset = IntOffset(20, 20)) {
                            Box(Modifier.size(30.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(30, 30))
            show = false
            frame()
            assertEquals(WHITE, pixelAt(30, 30))
        }

    @Test
    fun `outside click dismisses a focusable popup`() =
        runTaoSceneTest(width = 200, height = 200) {
            var dismissed = false
            setContent {
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    if (open) {
                        Popup(
                            offset = IntOffset(20, 20),
                            onDismissRequest = {
                                open = false
                                dismissed = true
                            },
                            // focusable popup: outside clicks request dismissal
                            properties =
                                androidx.compose.ui.window
                                    .PopupProperties(focusable = true),
                        ) {
                            Box(Modifier.size(40.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            assertEquals(BLUE, pixelAt(40, 40))
            click(150f, 150f) // outside the popup bounds
            frame()
            assertEquals(true, dismissed, "outside click must request dismissal")
            assertEquals(WHITE, pixelAt(40, 40))
        }

    @Test
    fun `click inside a focusable popup does not dismiss it`() =
        runTaoSceneTest(width = 200, height = 200) {
            var dismissed = false
            setContent {
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    if (open) {
                        Popup(
                            offset = IntOffset(20, 20),
                            onDismissRequest = {
                                open = false
                                dismissed = true
                            },
                            properties =
                                androidx.compose.ui.window
                                    .PopupProperties(focusable = true),
                        ) {
                            Box(Modifier.size(40.dp).background(Color.Blue))
                        }
                    }
                }
            }
            frame()
            click(40f, 40f) // inside
            frame()
            assertEquals(false, dismissed)
            assertEquals(BLUE, pixelAt(40, 40))
        }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
