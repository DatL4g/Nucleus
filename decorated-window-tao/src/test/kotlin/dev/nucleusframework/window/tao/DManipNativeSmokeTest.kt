package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies the DirectManipulation native chain against a real HWND without
 * any Tao event loop: COM manager/viewport creation, subclass install,
 * idle fetch semantics, and clean detach. Windows-only (self-skips
 * elsewhere); executed for real on the windows-latest CI runner right
 * after the DLLs are built.
 *
 * The HWND is a bare hidden STATIC window created by the bridge — the
 * DComp-backed standalone panels can't initialize in the runner's
 * non-interactive session (no compositor), and DirectManipulation needs
 * neither GL nor composition.
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

        val hwnd = NativeTaoDManipBridge.nativeCreateTestHwnd()
        assertNotEquals(0L, hwnd, "bare test HWND creation failed")
        try {
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
            NativeTaoDManipBridge.nativeDestroyTestHwnd(hwnd)
        }
    }
}
