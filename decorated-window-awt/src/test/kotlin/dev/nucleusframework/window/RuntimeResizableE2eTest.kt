package dev.nucleusframework.window

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.GraphicsEnvironment
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end regression test for issue #260: [DecoratedWindowState.isResizable]
 * must recompose immediately when `Frame.setResizable()` is called after the
 * window is shown — without waiting for another window event (activation,
 * minimize/restore, resize).
 *
 * Opens a real window; skipped in headless environments (CI without display).
 */
class RuntimeResizableE2eTest {
    @Test
    fun stateReactsToRuntimeSetResizable() {
        if (GraphicsEnvironment.isHeadless()) {
            println("SKIPPED: headless environment, no display to open a real window")
            return
        }
        println("Running against display: ${System.getenv("DISPLAY") ?: System.getenv("WAYLAND_DISPLAY")}")

        val sawResizable = CountDownLatch(1)
        val sawNonResizable = CountDownLatch(1)
        val sawResizableAgain = CountDownLatch(2)
        val windowRef = AtomicReference<ComposeWindow>()

        val appThread =
            thread(name = "resizable-e2e") {
                application(exitProcessOnExit = false) {
                    // Undecorated, like the real JBR/JNI backends — DecoratedWindowBody
                    // sets window.shape on Linux, which AWT forbids on decorated frames.
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = "resizable-e2e",
                        undecorated = true,
                    ) {
                        DecoratedWindowBody(title = "resizable-e2e", icon = null, undecorated = true) {
                            windowRef.set(window)
                            val resizable = state.isResizable
                            LaunchedEffect(resizable) {
                                if (resizable) {
                                    sawResizable.countDown()
                                    sawResizableAgain.countDown()
                                } else {
                                    sawNonResizable.countDown()
                                }
                            }
                        }
                    }
                }
            }

        try {
            assertTrue(
                sawResizable.await(30, TimeUnit.SECONDS),
                "Window never composed with state.isResizable = true",
            )

            SwingUtilities.invokeAndWait { windowRef.get().isResizable = false }
            assertTrue(
                sawNonResizable.await(10, TimeUnit.SECONDS),
                "state.isResizable did not react to runtime setResizable(false) — issue #260 regression",
            )

            SwingUtilities.invokeAndWait { windowRef.get().isResizable = true }
            assertTrue(
                sawResizableAgain.await(10, TimeUnit.SECONDS),
                "state.isResizable did not react to runtime setResizable(true) — issue #260 regression",
            )
        } finally {
            windowRef.get()?.let { w ->
                SwingUtilities.invokeLater {
                    w.dispatchEvent(WindowEvent(w, WindowEvent.WINDOW_CLOSING))
                }
            }
            appThread.join(TimeUnit.SECONDS.toMillis(15))
        }
    }
}
