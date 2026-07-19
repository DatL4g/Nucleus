package dev.nucleusframework.window.tao.scene

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 semantics tests: the tree is observed through the exact
 * `PlatformContext.SemanticsOwnerListener` hook the tao accessibility layer
 * ([dev.nucleusframework.window.tao.a11y.TaoSemanticsObserver]) plugs into —
 * what these tests see is what the a11y snapshot serializer sees.
 */
class TaoSceneSemanticsTest {
    @Test
    fun `semantics owner is exposed through the platform context hook`() =
        runTaoSceneTest {
            setContent { Box(Modifier.fillMaxSize()) }
            assertTrue(semanticsRoots().isNotEmpty(), "the scene must register a semantics owner")
        }

    @Test
    fun `text nodes are discoverable by text`() =
        runTaoSceneTest {
            setContent {
                Column(Modifier.fillMaxSize()) {
                    BasicText("Hello")
                    BasicText("World")
                }
            }
            frame()
            assertTrue(hasNodeWithText("Hello"))
            assertTrue(hasNodeWithText("World"))
            assertEquals(false, hasNodeWithText("Absent"))
        }

    @Test
    fun `test tags are discoverable and carry bounds`() =
        runTaoSceneTest(width = 200, height = 200) {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(50.dp).testTag("target"))
                }
            }
            frame()
            val node = nodeWithTag("target")
            assertEquals(50f, node.boundsInRoot.width)
            assertEquals(50f, node.boundsInRoot.height)
        }

    @Test
    fun `clickNode clicks through semantics bounds`() =
        runTaoSceneTest(width = 200, height = 200) {
            var clicks = 0
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.size(60.dp).testTag("first"))
                    Box(Modifier.size(60.dp).testTag("button").clickable { clicks++ })
                }
            }
            frame()
            clickNode(nodeWithTag("button"))
            assertEquals(1, clicks)
        }

    @Test
    fun `semantics updates track recomposition`() =
        runTaoSceneTest {
            var label by mutableStateOf("Before")
            setContent {
                Box(Modifier.fillMaxSize()) { BasicText(label) }
            }
            frame()
            assertTrue(hasNodeWithText("Before"))
            label = "After"
            frame()
            assertTrue(hasNodeWithText("After"))
            assertEquals(false, hasNodeWithText("Before"))
        }

    @Test
    fun `clickable nodes expose an onClick action`() =
        runTaoSceneTest {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(40.dp).testTag("btn").clickable { })
                }
            }
            frame()
            val node = nodeWithTag("btn")
            assertTrue(
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null,
                "clickable must expose SemanticsActions.OnClick",
            )
            assertEquals(
                null,
                node.config.getOrNull(SemanticsProperties.Disabled),
                "enabled clickable must not be marked Disabled",
            )
        }
}
