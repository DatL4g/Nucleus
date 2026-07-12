package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies the DirectManipulation native chain against a real HWND without
 * any Tao event loop: COM manager/viewport creation, subclass install,
 * idle fetch semantics, and clean detach. Windows-only (self-skips
 * elsewhere, like [StandalonePanelNativeSmokeTest]); executed for real on
 * the windows-latest CI runner right after the DLLs are built.
 *
 * Touchpad input can't be synthesized headlessly — gesture/inertia
 * behavior is validated on-device — but this catches the failure modes a
 * runner CAN see: missing exports, COM registration, viewport setup,
 * lifecycle leaks/crashes.
 */
class DManipNativeSmokeTest {
    @Test
    fun directManipulationViewportAttachesToRealHwnd() {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return

        assertTrue(NativeTaoDManipBridge.isLoaded, "nucleus_tao_windows_deco failed to load")
        assertTrue(PopupNativeBridgeWindows.isLoaded, "nucleus_tao_windows_native_view failed to load")

        val panel =
            PopupNativeBridgeWindows.nativeCreatePanel(
                parentHwnd = 0L,
                xPx = -32_000,
                yPx = -32_000,
                widthPx = 300,
                heightPx = 200,
            )
        assertNotEquals(0L, panel, "ownerless panel creation failed")
        try {
            val hwnd = PopupNativeBridgeWindows.nativeContentHwnd(panel)
            assertNotEquals(0L, hwnd, "panel content HWND unavailable")

            assertTrue(
                NativeTaoDManipBridge.nativeAttach(hwnd),
                "DirectManipulation viewport attach failed (COM/manager/viewport)",
            )

            val out = FloatArray(3)
            val status = NativeTaoDManipBridge.nativeFetch(hwnd, out)
            assertEquals(NativeTaoDManipBridge.STATUS_IDLE, status, "fresh viewport must be idle")
            assertEquals(0f, out[0], "no pan X expected while idle")
            assertEquals(0f, out[1], "no pan Y expected while idle")
            assertEquals(1f, out[2], "no scale delta expected while idle")

            NativeTaoDManipBridge.nativeDetach(hwnd)
            assertEquals(
                NativeTaoDManipBridge.STATUS_UNAVAILABLE,
                NativeTaoDManipBridge.nativeFetch(hwnd, out),
                "fetch after detach must report unavailable",
            )
        } finally {
            PopupNativeBridgeWindows.nativeRelease(panel)
        }
    }
}
