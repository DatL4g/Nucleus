package dev.nucleusframework.window.tao

import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeLinux
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the native chain behind the Linux standalone popup panel without
 * any Tao event loop: X connection, override-redirect ARGB panel creation,
 * EGL attach and Skia DirectContext creation. The Linux twin of
 * [StandalonePanelNativeSmokeTest] (Windows). Unlike AppKit, X11 has no
 * main-thread requirement — the command connection is owned by whichever
 * single thread uses it, here the test worker.
 *
 * Skipped when no X server is reachable (headless CI without Xvfb, rare
 * Wayland-only setups) — the same signal `isTaoStandalonePopupAvailable()`
 * reports to production callers.
 */
class StandalonePanelLinuxNativeSmokeTest {
    @Test
    fun standalonePanelPipelineInitializes() {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return

        assertTrue(PopupNativeBridgeLinux.isLoaded, "nucleus_tao_linux_popup failed to load")
        assertTrue(NativeTaoEglBridge.isLoaded, "nucleus_tao_egl failed to load")
        if (!PopupNativeBridgeLinux.nativeIsAvailable()) {
            println("SKIP: no X server reachable (DISPLAY unset?)")
            return
        }

        val panel =
            PopupNativeBridgeLinux.nativeCreatePanel(
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 300,
                heightPx = 200,
            )
        assertNotEquals(0L, panel, "standalone panel creation failed (no ARGB visual?)")

        var attachment = 0L
        try {
            attachment =
                NativeTaoEglBridge.nativeAttachX11(
                    displayPtr = PopupNativeBridgeLinux.nativeDisplayPtr(),
                    xid = PopupNativeBridgeLinux.nativeWindowXid(panel),
                    widthPx = 300,
                    heightPx = 200,
                )
            assertNotEquals(0L, attachment, "EGL attach failed on standalone panel")
            NativeTaoEglBridge.nativeSetSwapInterval(attachment, 0)
            val intf =
                GLAssembledInterface.createFromNativePointers(
                    0L,
                    NativeTaoEglBridge.nativeGetProcAddrFunctionPointer(),
                )
            val ctx = DirectContext.makeGLWithInterface(intf)
            ctx.close()
            assertTrue(PopupNativeBridgeLinux.nativeScale() > 0f, "panel scale must be positive")
            val workArea = PopupNativeBridgeLinux.nativePrimaryWorkArea()
            assertNotNull(workArea, "primary work area query failed")
            assertTrue(
                workArea.size == 4 && workArea[2] > 0 && workArea[3] > 0,
                "invalid work area: ${workArea.toList()}",
            )
            println("work area: ${workArea.toList()}, scale: ${PopupNativeBridgeLinux.nativeScale()}")
        } finally {
            if (attachment != 0L) NativeTaoEglBridge.nativeDetach(attachment)
            PopupNativeBridgeLinux.nativeRelease(panel)
        }
    }

    /**
     * Exercises the panel lifecycle around a real X window: reposition, show,
     * hide, event/outside-click callback installation, and the dedicated
     * event thread (spawned by `nativeCreatePanel`, joined by
     * `nativeRelease`, detaching itself from the JVM on exit). A crash or
     * hang anywhere in that chain fails this test; it is the closest JVM-side
     * proxy for popup dismissal on a real window.
     */
    @Test
    fun standalonePanelLifecycleSurvivesShowMoveHideAndEventThread() {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return
        assertTrue(PopupNativeBridgeLinux.isLoaded, "nucleus_tao_linux_popup failed to load")
        if (!PopupNativeBridgeLinux.nativeIsAvailable()) {
            println("SKIP: no X server reachable (DISPLAY unset?)")
            return
        }

        val panel =
            PopupNativeBridgeLinux.nativeCreatePanel(
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 120,
                heightPx = 80,
            )
        assertNotEquals(0L, panel, "standalone panel creation failed")

        try {
            // Wire the Java listeners onto the panel's event thread (the
            // thread itself was spawned by nativeCreatePanel and attaches
            // itself to the JVM on its first forwarded event).
            PopupNativeBridgeLinux.nativeSetEventCallback(
                panel,
                object : PopupNativeBridgeLinux.EventCallback {
                    override fun onPointerEvent(
                        type: Int,
                        x: Float,
                        y: Float,
                        button: Int,
                        modifiers: Int,
                    ) = Unit

                    override fun onScroll(
                        x: Float,
                        y: Float,
                        dx: Float,
                        dy: Float,
                    ) = Unit

                    override fun onKeyEvent(
                        type: Int,
                        vkCode: Int,
                        codePoint: Int,
                        modifiers: Int,
                    ) = Unit
                },
            )
            PopupNativeBridgeLinux.nativeInstallOutsideClickMonitor(
                panel,
                object : PopupNativeBridgeLinux.OutsideClickListener {
                    override fun onOutsideClick(
                        type: Int,
                        button: Int,
                    ) = Unit
                },
            )

            // Position + dismissal cycle on the real window.
            PopupNativeBridgeLinux.nativeSetFrameOnScreen(panel, 50, 60, 160, 90)
            PopupNativeBridgeLinux.nativeSetPanelVisible(panel, true)
            PopupNativeBridgeLinux.nativeSetFrameOnScreen(panel, 80, 90, 160, 90)
            PopupNativeBridgeLinux.nativeSetPanelVisible(panel, false)

            PopupNativeBridgeLinux.nativeUninstallOutsideClickMonitor(panel)
        } finally {
            // Joins the event thread; a leaked/attached thread or a stuck
            // quit pipe hangs here and trips the test timeout.
            PopupNativeBridgeLinux.nativeRelease(panel)
        }
    }
}
