package dev.nucleusframework.window.tao.a11y

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TaoA11yAction
import dev.nucleusframework.window.tao.TaoA11yNode
import dev.nucleusframework.window.tao.TaoA11yRole
import dev.nucleusframework.window.tao.TaoA11ySnapshotSerializer
import dev.nucleusframework.window.tao.TaoAccessibilityController
import dev.nucleusframework.window.tao.scene.TaoSceneTestScope
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage-1 coverage of the accessibility projection pipeline: Compose
 * semantics → [TaoSemanticsObserver] walk → [TaoAccessibilityController]
 * snapshot → [TaoA11ySnapshotSerializer] wire bytes. This is the exact
 * production path up to the JNI call; the native decoders are exercised
 * end-to-end by the `tao-a11y` CI job (UIA / AT-SPI / AX clients).
 *
 * The controller is subclassed to capture the node list at the JNI seam —
 * `pushSnapshot` would otherwise no-op before attach (nsView == 0), which
 * is precisely the display-less configuration this tier runs in.
 */
class TaoA11yProjectionTest {
    private class CapturingController : TaoAccessibilityController(windowHandle = 0L) {
        val snapshots = mutableListOf<List<TaoA11yNode>>()

        override fun pushSnapshot(nodes: List<TaoA11yNode>) {
            snapshots += nodes
        }
    }

    private class Projection(
        val controller: CapturingController,
        val observer: TaoSemanticsObserver,
    ) {
        fun sync(): List<TaoA11yNode> {
            observer.syncIfDirty()
            check(controller.snapshots.isNotEmpty()) { "observer never pushed a snapshot" }
            return controller.snapshots.last()
        }
    }

    private fun TaoSceneTestScope.attachObserver(): Projection {
        val controller = CapturingController()
        val observer =
            TaoSemanticsObserver(
                controller = controller,
                densityProvider = { 1f },
                onScheduleSync = { /* tests sync explicitly */ },
            )
        semanticsOwners().forEach(observer::onSemanticsOwnerAppended)
        return Projection(controller, observer)
    }

    @Test
    fun `compose semantics are projected into the a11y node snapshot`() =
        runTaoSceneTest {
            setContent {
                Column {
                    BasicText("Hello a11y")
                    Box(
                        Modifier
                            .testTag("ok-button")
                            .size(60.dp, 24.dp)
                            .clickable(role = Role.Button, onClick = {}),
                    ) { BasicText("OK") }
                    Box(
                        Modifier
                            .testTag("volume")
                            .size(120.dp, 12.dp)
                            .progressSemantics(value = 0.25f),
                    )
                }
            }
            frameUntilIdle()

            val nodes = attachObserver().sync()
            assertTrue(nodes.size > 1, "expected a projected tree, got ${nodes.size} node(s)")

            assertTrue(
                nodes.any { it.label == "Hello a11y" },
                "static text label missing from projection: ${nodes.map { it.label }}",
            )

            val button = nodes.single { it.testTag == "ok-button" }
            assertEquals(TaoA11yRole.Button, button.role)
            assertTrue(
                button.actions and TaoA11yAction.CLICK != 0,
                "clickable button must project the CLICK action",
            )
            assertTrue(button.frameW > 0f && button.frameH > 0f, "placed node must project a non-empty frame")

            val progress = nodes.single { it.testTag == "volume" }
            assertEquals(TaoA11yRole.Progress, progress.role)
            assertEquals(0.25f, progress.numericValue)

            // Topology invariants the native decoders rely on: parents are
            // emitted before their children, every parentId resolves, and the
            // stitched children lists mirror the parentId edges exactly.
            val seen = HashSet<Long>()
            for (node in nodes) {
                if (node.parentId != 0L) {
                    assertTrue(node.parentId in seen, "child ${node.nodeId} emitted before parent ${node.parentId}")
                }
                seen += node.nodeId
            }
            val byId = nodes.associateBy { it.nodeId }
            for (node in nodes) {
                for (childId in node.children) {
                    assertEquals(node.nodeId, byId.getValue(childId).parentId)
                }
            }
        }

    @Test
    fun `semantics changes propagate into the next snapshot`() =
        runTaoSceneTest {
            var label by mutableStateOf("before")
            setContent {
                BasicText(label, modifier = Modifier.testTag("live-label"))
            }
            frameUntilIdle()

            val projection = attachObserver()
            val first = projection.sync()
            assertEquals("before", first.single { it.testTag == "live-label" }.label)

            label = "after"
            frameUntilIdle()
            semanticsOwners().forEach(projection.observer::onSemanticsChange)

            val second = projection.sync()
            assertEquals("after", second.single { it.testTag == "live-label" }.label)
        }

    @Test
    fun `projected snapshot round-trips through the v7 wire format`() =
        runTaoSceneTest {
            setContent {
                Column {
                    BasicText("wire-check")
                    Box(
                        Modifier
                            .testTag("wire-button")
                            .size(40.dp, 20.dp)
                            .clickable(role = Role.Button, onClick = {}),
                    ) { BasicText("Go") }
                }
            }
            frameUntilIdle()

            val nodes = attachObserver().sync()
            val bytes = TaoA11ySnapshotSerializer.encodeFull(nodes, focusId = 0L)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // Header — must match the layout the native decoders parse.
            assertEquals(0xA110A11A.toInt(), buf.int, "magic")
            assertEquals(7, buf.short.toInt(), "version")
            assertEquals(0, buf.short.toInt(), "full snapshot must not carry the partial flag")
            assertEquals(nodes.size, buf.int, "nodeCount")
            assertEquals(0L, buf.long, "focusId")
            assertEquals(0, buf.int, "reserved")

            // Walk every node with the documented v7 layout; the cursor must
            // land exactly on the end of the buffer.
            repeat(nodes.size) { i ->
                val expected = nodes[i]
                assertEquals(expected.nodeId, buf.long, "nodeId[$i]")
                assertEquals(expected.parentId, buf.long, "parentId[$i]")
                assertEquals(expected.role.code, buf.short.toInt(), "role[$i]")
                buf.short // flags
                buf.short // actions
                buf.short // extraFlags
                repeat(4) { buf.float } // frame
                repeat(3) { buf.float } // min/max/numeric
                buf.int // selectionStart
                buf.int // selectionEnd
                repeat(4) { buf.float } // scroll axes
                val label = ByteArray(buf.short.toInt() and 0xFFFF).also(buf::get).toString(Charsets.UTF_8)
                assertEquals(expected.label, label, "label[$i]")
                val valueLen = buf.short.toInt() and 0xFFFF
                buf.position(buf.position() + valueLen) // value
                repeat(buf.short.toInt() and 0xFFFF) {
                    val actionLen = buf.short.toInt() and 0xFFFF
                    buf.position(buf.position() + actionLen) // custom action
                }
                val testTag = ByteArray(buf.short.toInt() and 0xFFFF).also(buf::get).toString(Charsets.UTF_8)
                assertEquals(expected.testTag, testTag, "testTag[$i]")
                repeat(buf.int) { buf.long } // children
            }
            assertEquals(0, buf.remaining(), "decoder cursor must end exactly at the buffer end")
        }
}
