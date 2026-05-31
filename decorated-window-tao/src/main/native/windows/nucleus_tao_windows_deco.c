/**
 * JNI bridge for Tao-window custom decoration on Windows.
 *
 * Subclasses the HWND created by Tao to:
 *   - WM_NCCALCSIZE: extend client area into the title bar
 *   - WM_NCHITTEST: 3-zone hit test (resize borders, caption, client)
 *   - WM_NCMOUSEMOVE: forward as WM_MOUSEMOVE for Compose pointer tracking
 *   - DwmExtendFrameIntoClientArea for DWM shadow
 *
 * Forked from decorated-window-jni's nucleus_windows_decoration.c with the
 * Skiko-AWT child-window plumbing removed: a Tao window has no child HWND —
 * the WGL render surface is the HWND itself.
 *
 * Per-HWND state stored via SetProp/GetProp.
 * Linked libraries: kernel32.lib user32.lib dwmapi.lib gdi32.lib shell32.lib
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>

/* /NODEFAULTLIB support */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

#ifndef SM_CXPADDEDBORDERWIDTH
#define SM_CXPADDEDBORDERWIDTH 92
#endif

/* DPI-aware function pointers (resolved once) */
typedef UINT (WINAPI *PFN_GetDpiForWindow)(HWND);
typedef int  (WINAPI *PFN_GetSystemMetricsForDpi)(int, UINT);

static PFN_GetDpiForWindow         pGetDpiForWindow = NULL;
static PFN_GetSystemMetricsForDpi  pGetSystemMetricsForDpi = NULL;
static volatile BOOL dpiApiResolved = FALSE;

static void resolveDpiApis(void) {
    if (dpiApiResolved) return;
    HMODULE hUser32 = GetModuleHandleA("user32.dll");
    if (hUser32) {
        pGetDpiForWindow = (PFN_GetDpiForWindow)
            GetProcAddress(hUser32, "GetDpiForWindow");
        pGetSystemMetricsForDpi = (PFN_GetSystemMetricsForDpi)
            GetProcAddress(hUser32, "GetSystemMetricsForDpi");
    }
    dpiApiResolved = TRUE;
}

static UINT getDpi(HWND hwnd) {
    if (pGetDpiForWindow) return pGetDpiForWindow(hwnd);
    HDC hdc = GetDC(hwnd);
    UINT dpi = (UINT)GetDeviceCaps(hdc, LOGPIXELSX);
    ReleaseDC(hwnd, hdc);
    return dpi;
}

static int getSystemMetrics(int index, UINT dpi) {
    if (pGetSystemMetricsForDpi) return pGetSystemMetricsForDpi(index, dpi);
    return GetSystemMetrics(index);
}

/* Per-HWND state */
static const wchar_t *PROP_NAME = L"NucleusTaoDecoState";

typedef struct {
    WNDPROC originalWndProc;
    int     titleBarHeightPx;
    COLORREF bgColor;
    BOOL    startupBackgroundErase;
    BOOL    isFullscreen;
    LONG    savedStyle;
    LONG    savedExStyle;
    WINDOWPLACEMENT savedPlacement;
    /* Set while a title-bar touch interaction is being routed to DefWindowProc
     * (instead of Tao's consuming subclass) so the OS synthesises legacy mouse
     * messages for an OS-driven title-bar drag with Aero Snap. See decoWndProc. */
    BOOL    titleBarDragArmed;
} DecoState;

static DecoState *getState(HWND hwnd) {
    return (DecoState *)GetPropW(hwnd, PROP_NAME);
}

static int getResizeBorderWidth(HWND hwnd, BOOL isVertical) {
    UINT dpi = getDpi(hwnd);
    int frameMetric = isVertical ? SM_CXSIZEFRAME : SM_CYSIZEFRAME;
    return getSystemMetrics(frameMetric, dpi)
         + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
}

static BOOL isAutoHideTaskbar(UINT edge, RECT monitorRect) {
    APPBARDATA abd;
    abd.cbSize = sizeof(abd);
    abd.uEdge = edge;
    abd.rc = monitorRect;
    return (BOOL)SHAppBarMessage(ABM_GETAUTOHIDEBAR, &abd);
}

static BOOL isOwnedTaoPopup(HWND root, HWND owner) {
    if (!root || !owner || root == owner) return FALSE;

    wchar_t className[64];
    if (!GetClassNameW(root, className, 64)) return FALSE;
    if (lstrcmpW(className, L"NucleusTaoPopupCls") != 0) return FALSE;

    HWND currentOwner = GetWindow(root, GW_OWNER);
    while (currentOwner) {
        if (currentOwner == owner) return TRUE;
        currentOwner = GetWindow(currentOwner, GW_OWNER);
    }
    return FALSE;
}

/* WndProc subclass */
static LRESULT CALLBACK decoWndProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    DecoState *state = getState(hwnd);
    if (!state) return DefWindowProcW(hwnd, msg, wParam, lParam);

    /* Touch title-bar drag → Aero Snap.
     *
     * Tao routes touch through WM_POINTER and consumes it, so Windows never
     * promotes touch to the legacy mouse messages that the OS modal move loop
     * (and thus Aero Snap) needs. To restore that, we capture a title-bar touch
     * from its very first WM_POINTERDOWN and hand the whole pointer interaction
     * (down → update → up) to DefWindowProc, consuming it so it never reaches
     * Tao. DefWindowProc's pointer handling then synthesises the legacy
     * WM_MOUSE* messages: Compose sees a mouse press on the title bar and runs
     * its normal mouse drag (`dragWindow()` → WM_NCLBUTTONDOWN/HTCAPTION), which
     * enters the OS move loop with full Aero Snap and native
     * restore-from-maximized.
     *
     * A native hit-test gates the capture to the title-bar band. Because the
     * interaction is consumed, Compose never receives a touch press for that
     * contact (no stuck pointer); title-bar content/buttons still work via the
     * synthesised mouse click. */
    if (!state->titleBarDragArmed && msg == WM_POINTERDOWN) {
        POINT pt; pt.x = (int)(short)LOWORD(lParam); pt.y = (int)(short)HIWORD(lParam);
        ScreenToClient(hwnd, &pt);
        if (pt.y >= 0 && pt.y < state->titleBarHeightPx) {
            state->titleBarDragArmed = TRUE;
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
    }
    if (state->titleBarDragArmed) {
        switch (msg) {
        case WM_POINTERUPDATE:
        case WM_POINTERLEAVE:
        case WM_POINTERCAPTURECHANGED:
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        case WM_POINTERUP:
            state->titleBarDragArmed = FALSE;
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        default:
            break;
        }
    }

    switch (msg) {

    /* During ShowWindow, DWM can request an erased client surface before WGL
     * has presented into the now-visible redirection surface. Paint the themed
     * background only for that startup gap; after the first native redraw event
     * this is disabled to avoid solid-color flicker while resizing or dragging. */
    case WM_ERASEBKGND:
        if (state->startupBackgroundErase) {
            HDC hdc = (HDC)wParam;
            RECT rc;
            if (hdc && GetClientRect(hwnd, &rc)) {
                HBRUSH brush = CreateSolidBrush(state->bgColor);
                FillRect(hdc, &rc, brush);
                DeleteObject(brush);
            }
        }
        return 1;


    case WM_NCCALCSIZE: {
        if (!wParam) break;
        if (state->isFullscreen) return 0;

        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lParam;
        RECT originalTop = params->rgrc[0];

        LRESULT result = CallWindowProcW(state->originalWndProc,
                                          hwnd, msg, wParam, lParam);

        params->rgrc[0].top = originalTop.top;

        if (IsZoomed(hwnd)) {
            UINT dpi = getDpi(hwnd);
            int borderWidth = getSystemMetrics(SM_CYSIZEFRAME, dpi)
                            + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
            params->rgrc[0].top += borderWidth;

            HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO mi;
            mi.cbSize = sizeof(mi);
            if (GetMonitorInfoW(hMon, &mi)) {
                if (params->rgrc[0].top == mi.rcMonitor.top
                    && isAutoHideTaskbar(ABE_TOP, mi.rcMonitor)) {
                    params->rgrc[0].top += 1;
                }
                if (params->rgrc[0].bottom == mi.rcMonitor.bottom
                    && isAutoHideTaskbar(ABE_BOTTOM, mi.rcMonitor)) {
                    params->rgrc[0].bottom -= 1;
                }
                if (params->rgrc[0].left == mi.rcMonitor.left
                    && isAutoHideTaskbar(ABE_LEFT, mi.rcMonitor)) {
                    params->rgrc[0].left += 1;
                }
                if (params->rgrc[0].right == mi.rcMonitor.right
                    && isAutoHideTaskbar(ABE_RIGHT, mi.rcMonitor)) {
                    params->rgrc[0].right -= 1;
                }
            }
        }

        return result;
    }

    case WM_NCHITTEST: {
        POINT pt;
        pt.x = (short)LOWORD(lParam);
        pt.y = (short)HIWORD(lParam);

        RECT windowRect;
        GetWindowRect(hwnd, &windowRect);

        int borderWidth = getResizeBorderWidth(hwnd, TRUE);
        int borderHeight = getResizeBorderWidth(hwnd, FALSE);

        if (!IsZoomed(hwnd) && !state->isFullscreen) {
            if (pt.x < windowRect.left + borderWidth &&
                pt.y < windowRect.top + borderHeight) return HTTOPLEFT;
            if (pt.x >= windowRect.right - borderWidth &&
                pt.y < windowRect.top + borderHeight) return HTTOPRIGHT;
            if (pt.x < windowRect.left + borderWidth &&
                pt.y >= windowRect.bottom - borderHeight) return HTBOTTOMLEFT;
            if (pt.x >= windowRect.right - borderWidth &&
                pt.y >= windowRect.bottom - borderHeight) return HTBOTTOMRIGHT;
            if (pt.x < windowRect.left + borderWidth) return HTLEFT;
            if (pt.x >= windowRect.right - borderWidth) return HTRIGHT;
            if (pt.y < windowRect.top + borderHeight) return HTTOP;
            if (pt.y >= windowRect.bottom - borderHeight) return HTBOTTOM;
        }

        /* Title bar zone — always HTCLIENT.
         * NEVER return HTMINBUTTON/HTMAXBUTTON/HTCLOSE: DWM would draw native
         * buttons on top of our Compose UI. Compose handles the whole title
         * bar, including its own min/max/close buttons; unconsumed clicks call
         * window.dragWindow() which posts WM_NCLBUTTONDOWN HTCAPTION via Tao. */
        if (pt.y < windowRect.top + state->titleBarHeightPx) {
            return HTCLIENT;
        }

        return HTCLIENT;
    }

    case WM_NCLBUTTONDOWN: {
        if (wParam == HTCAPTION) {
            ReleaseCapture();
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        break;
    }

    case WM_NCLBUTTONDBLCLK: {
        if (wParam == HTCAPTION) {
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        break;
    }

    case WM_NCMOUSEMOVE: {
        POINT pt;
        pt.x = (short)LOWORD(lParam);
        pt.y = (short)HIWORD(lParam);
        ScreenToClient(hwnd, &pt);
        PostMessageW(hwnd, WM_MOUSEMOVE, 0, MAKELPARAM(pt.x, pt.y));
        break;
    }

    case WM_SYSCOMMAND: {
        if (state->isFullscreen) {
            WPARAM cmd = wParam & 0xFFF0;
            if (cmd == SC_RESTORE || cmd == SC_MAXIMIZE ||
                cmd == SC_SIZE   || cmd == SC_MOVE) {
                return 0;
            }
        }
        break;
    }

    case WM_NCDESTROY: {
        if (state->isFullscreen) {
            SetWindowLongW(hwnd, GWL_STYLE, state->savedStyle);
            SetWindowLongW(hwnd, GWL_EXSTYLE, state->savedExStyle);
        }
        WNDPROC origProc = state->originalWndProc;
        RemovePropW(hwnd, PROP_NAME);
        HeapFree(GetProcessHeap(), 0, state);
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)origProc);
        return CallWindowProcW(origProc, hwnd, msg, wParam, lParam);
    }

    } /* end switch */

    return CallWindowProcW(state->originalWndProc, hwnd, msg, wParam, lParam);
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)lpvReserved;
    if (fdwReason == DLL_PROCESS_ATTACH) {
        resolveDpiApis();
    }
    return TRUE;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: dev.nucleusframework.window.tao                 */
/*  Class:   NativeTaoWindowsDecoBridge                                */
/* ================================================================== */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeInstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint titleBarHeightPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *existing = getState(hwnd);
    if (existing) {
        existing->titleBarHeightPx = (int)titleBarHeightPx;
        return;
    }

    DecoState *state = (DecoState *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(DecoState));
    if (!state) return;

    state->titleBarHeightPx = (int)titleBarHeightPx;
    state->bgColor = RGB(255, 255, 255);
    state->startupBackgroundErase = TRUE;

    SetPropW(hwnd, PROP_NAME, (HANDLE)state);

    LONG_PTR prevWndProc = SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC, (LONG_PTR)decoWndProc);
    state->originalWndProc = (WNDPROC)prevWndProc;

    /* Extend bottom by 1px to keep DWM shadow without enabling glass over the
     * client area. With {0,0,0,1} DWM treats the client area as opaque, so
     * transparent pixels render as black (invisible on dark themes). */
    MARGINS margins = {0, 0, 0, 1};
    DwmExtendFrameIntoClientArea(hwnd, &margins);

    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeUninstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)state->originalWndProc);
    RemovePropW(hwnd, PROP_NAME);
    HeapFree(GetProcessHeap(), 0, state);

    MARGINS margins = {0, 0, 0, 0};
    DwmExtendFrameIntoClientArea(hwnd, &margins);

    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeSetTitleBarHeight(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint heightPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;
    DecoState *state = getState(hwnd);
    if (state) state->titleBarHeightPx = (int)heightPx;
}

/* Background color (ARGB) — synced to DWM caption/border color and dark-mode
 * flag so the "sheet of glass" composited during resize matches the theme. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeSetBackgroundColor(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint argb)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    int r = (argb >> 16) & 0xFF;
    int g = (argb >>  8) & 0xFF;
    int b =  argb        & 0xFF;
    COLORREF color = RGB(r, g, b);

    DecoState *state = getState(hwnd);
    if (state) state->bgColor = color;

    DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */,
                          &color, sizeof(color));
    DwmSetWindowAttribute(hwnd, 34 /* DWMWA_BORDER_COLOR */,
                          &color, sizeof(color));

    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
    BOOL isDark = (luminance < 128) ? TRUE : FALSE;
    DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */,
                          &isDark, sizeof(isDark));
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeSetStartupBackgroundEraseEnabled(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean enabled)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    DecoState *state = getState(hwnd);
    if (state) state->startupBackgroundErase = enabled ? TRUE : FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeSetFullscreen(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean fullscreen)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    if (fullscreen) {
        if (state->isFullscreen) return;

        state->savedStyle = GetWindowLongW(hwnd, GWL_STYLE);
        state->savedExStyle = GetWindowLongW(hwnd, GWL_EXSTYLE);
        state->savedPlacement.length = sizeof(WINDOWPLACEMENT);
        GetWindowPlacement(hwnd, &state->savedPlacement);

        HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO mi;
        mi.cbSize = sizeof(mi);
        GetMonitorInfoW(hMon, &mi);

        BOOL disableTransitions = TRUE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &disableTransitions, sizeof(disableTransitions));

        if (state->savedPlacement.showCmd == SW_SHOWMAXIMIZED) {
            WINDOWPLACEMENT wp = state->savedPlacement;
            wp.rcNormalPosition.left   = mi.rcMonitor.left;
            wp.rcNormalPosition.top    = mi.rcMonitor.top;
            wp.rcNormalPosition.right  = mi.rcMonitor.right;
            wp.rcNormalPosition.bottom = mi.rcMonitor.bottom;
            SetWindowPlacement(hwnd, &wp);
        }

        state->isFullscreen = TRUE;

        LONG style = state->savedStyle
            & ~(LONG)(WS_CAPTION | WS_THICKFRAME | WS_MAXIMIZE);
        SetWindowLongW(hwnd, GWL_STYLE, style);

        LONG exStyle = state->savedExStyle
            & ~(LONG)(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE
                     | WS_EX_CLIENTEDGE | WS_EX_STATICEDGE);
        SetWindowLongW(hwnd, GWL_EXSTYLE, exStyle);

        SetWindowPos(hwnd, HWND_TOPMOST,
            mi.rcMonitor.left, mi.rcMonitor.top,
            mi.rcMonitor.right - mi.rcMonitor.left,
            mi.rcMonitor.bottom - mi.rcMonitor.top,
            SWP_FRAMECHANGED);

        BOOL enableTransitions = FALSE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &enableTransitions, sizeof(enableTransitions));
    } else {
        if (!state->isFullscreen) return;

        state->isFullscreen = FALSE;

        SetWindowLongW(hwnd, GWL_EXSTYLE, state->savedExStyle);
        LONG restoreStyle = state->savedStyle & ~(LONG)WS_MAXIMIZE;
        SetWindowLongW(hwnd, GWL_STYLE, restoreStyle);
        SetWindowPlacement(hwnd, &state->savedPlacement);
        SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED);
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeIsFullscreen(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return JNI_FALSE;
    DecoState *state = getState(hwnd);
    return (state && state->isFullscreen) ? JNI_TRUE : JNI_FALSE;
}

/* Establishes a parent-child (owner) relationship between two HWNDs via
 * GWLP_HWNDPARENT. The child window:
 *   - stays above the owner in z-order
 *   - is hidden when the owner is minimised
 *   - does not appear in the taskbar
 * Used by DecoratedDialog to make the dialog behave like a real JDialog.
 * Pass ownerHwndLong == 0 to clear the owner. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeSetOwner(
    JNIEnv *env, jclass clazz, jlong childHwndLong, jlong ownerHwndLong)
{
    (void)env; (void)clazz;
    HWND child = (HWND)(uintptr_t)childHwndLong;
    HWND owner = (HWND)(uintptr_t)ownerHwndLong;
    if (!child) return;
#if defined(_WIN64)
    SetWindowLongPtrW(child, GWLP_HWNDPARENT, (LONG_PTR)owner);
#else
    SetWindowLongW(child, GWLP_HWNDPARENT, (LONG)(LONG_PTR)owner);
#endif
}

/* Returns the primary monitor's scale factor as `(scale * 1000)`. Falls back
 * to GetDeviceCaps(LOGPIXELSX) when GetDpiForSystem isn't available
 * (pre-Windows 10 1607). Used by DecoratedWindow when the window's own
 * scale factor isn't yet resolvable (pre-onWindowReady). */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeGetPrimaryMonitorScaleMilli(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    UINT dpi = 96;
    HMODULE hUser32 = GetModuleHandleA("user32.dll");
    if (hUser32) {
        typedef UINT (WINAPI *PFN_GetDpiForSystem)(void);
        PFN_GetDpiForSystem pGetDpiForSystem =
            (PFN_GetDpiForSystem)GetProcAddress(hUser32, "GetDpiForSystem");
        if (pGetDpiForSystem) {
            dpi = pGetDpiForSystem();
        } else {
            HDC hdc = GetDC(NULL);
            if (hdc) {
                dpi = (UINT)GetDeviceCaps(hdc, LOGPIXELSX);
                ReleaseDC(NULL, hdc);
            }
        }
    }
    if (dpi == 0) dpi = 96;
    return (jint)((dpi * 1000) / 96);
}

/* Returns [x, y, width, height] of the primary monitor's work area (full
 * screen minus the taskbar) in physical pixels. Used by DecoratedWindow to
 * resolve [WindowPosition.Aligned] for the initial outer position. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeGetPrimaryMonitorWorkArea(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    RECT r;
    if (!SystemParametersInfoW(SPI_GETWORKAREA, 0, &r, 0)) return NULL;
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (!arr) return NULL;
    jlong values[4];
    values[0] = (jlong)r.left;
    values[1] = (jlong)r.top;
    values[2] = (jlong)(r.right - r.left);
    values[3] = (jlong)(r.bottom - r.top);
    (*env)->SetLongArrayRegion(env, arr, 0, 4, values);
    return arr;
}

/* Converts a window-client physical pixel position to screen physical
 * pixels. Returns [screenX, screenY] or NULL on failure. Used by the
 * touch-drag path in TitleBar.kt to compute window-move deltas: with
 * `RegisterTouchWindow` active, Windows does not synthesize mouse
 * messages from touch, so `WM_NCLBUTTONDOWN HTCAPTION` (PostMessage)
 * cannot drive a drag during a touch sequence. We instead track the
 * finger's screen position ourselves and apply `setOuterPosition`. */
JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeClientToScreen(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint xClient, jint yClient)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return NULL;
    POINT p; p.x = xClient; p.y = yClient;
    if (!ClientToScreen(hwnd, &p)) return NULL;
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    jint values[2] = { (jint)p.x, (jint)p.y };
    (*env)->SetIntArrayRegion(env, arr, 0, 2, values);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeIsCursorOverWindowOrOwnedPopup(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND owner = (HWND)(uintptr_t)hwndLong;
    if (!owner || !IsWindow(owner)) return JNI_FALSE;

    POINT pt;
    if (!GetCursorPos(&pt)) return JNI_FALSE;
    HWND hit = WindowFromPoint(pt);
    if (!hit) return JNI_FALSE;

    HWND root = GetAncestor(hit, GA_ROOT);
    if (!root) root = hit;
    if (root == owner) return JNI_TRUE;
    return isOwnedTaoPopup(root, owner) ? JNI_TRUE : JNI_FALSE;
}

/* Returns [x, y, width, height] of the window's outer bounds in screen
 * coordinates (physical pixels). Used by DecoratedDialog to centre itself on
 * its parent. Returns NULL if hwnd is invalid. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeGetWindowRect(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return NULL;
    RECT r;
    if (!GetWindowRect(hwnd, &r)) return NULL;
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (!arr) return NULL;
    jlong values[4];
    values[0] = (jlong)r.left;
    values[1] = (jlong)r.top;
    values[2] = (jlong)(r.right - r.left);
    values[3] = (jlong)(r.bottom - r.top);
    (*env)->SetLongArrayRegion(env, arr, 0, 4, values);
    return arr;
}

static int roundToInt(double value) {
    return (int)(value >= 0.0 ? value + 0.5 : value - 0.5);
}

static int clampInt(int value, int minValue, int maxValue) {
    if (value < minValue) return minValue;
    if (value > maxValue) return maxValue;
    return value;
}

/* Win32 IsZoomed. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeIsMaximized(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return JNI_FALSE;
    return IsZoomed(hwnd) ? JNI_TRUE : JNI_FALSE;
}

/* Atomic unmaximize + reposition under the finger when a touch drag starts
 * on a maximized window. The horizontal anchor preserves the finger's
 * fractional X position within the title bar. Y is clamped to the monitor
 * work area top + the title-bar mid-height so the bar lands on screen.
 * Returns the restored outer rect as [x, y, w, h] in physical pixels. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativePrepareTitleBarTouchDrag(
    JNIEnv *env, jclass clazz, jlong hwndLong,
    jint currentScreenX, jint currentScreenY,
    jint startScreenX, jint startScreenY)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return NULL;

    RECT currentRect;
    if (!GetWindowRect(hwnd, &currentRect)) return NULL;
    jlongArray currentArr = NULL;
    if (!IsZoomed(hwnd)) {
        currentArr = (*env)->NewLongArray(env, 4);
        if (currentArr) {
            jlong values[4] = {
                (jlong)currentRect.left,
                (jlong)currentRect.top,
                (jlong)(currentRect.right - currentRect.left),
                (jlong)(currentRect.bottom - currentRect.top),
            };
            (*env)->SetLongArrayRegion(env, currentArr, 0, 4, values);
        }
        return currentArr;
    }

    WINDOWPLACEMENT wp;
    wp.length = sizeof(WINDOWPLACEMENT);
    if (!GetWindowPlacement(hwnd, &wp)) return NULL;

    int normalWidth = wp.rcNormalPosition.right - wp.rcNormalPosition.left;
    int normalHeight = wp.rcNormalPosition.bottom - wp.rcNormalPosition.top;
    if (normalWidth <= 0 || normalHeight <= 0) return NULL;

    int maximizedWidth = currentRect.right - currentRect.left;
    double xFraction = 0.5;
    if (maximizedWidth > 0) {
        xFraction = ((double)startScreenX - (double)currentRect.left) / (double)maximizedWidth;
        if (xFraction < 0.0) xFraction = 0.0;
        if (xFraction > 1.0) xFraction = 1.0;
    }

    int titleAnchorY = startScreenY - currentRect.top;
    DecoState *state = getState(hwnd);
    int maxTitleAnchorY = state
        ? state->titleBarHeightPx / 2
        : getSystemMetrics(SM_CYCAPTION, getDpi(hwnd)) / 2;
    if (maxTitleAnchorY < 1) maxTitleAnchorY = 1;
    titleAnchorY = clampInt(titleAnchorY, 0, maxTitleAnchorY);

    int targetLeft = roundToInt((double)currentScreenX - xFraction * (double)normalWidth);
    int targetTop = (int)currentScreenY - titleAnchorY;

    POINT monitorPoint; monitorPoint.x = currentScreenX; monitorPoint.y = currentScreenY;
    HMONITOR hMon = MonitorFromPoint(monitorPoint, MONITOR_DEFAULTTONEAREST);
    MONITORINFO mi; mi.cbSize = sizeof(mi);
    if (GetMonitorInfoW(hMon, &mi) && targetTop < mi.rcWork.top) {
        targetTop = mi.rcWork.top;
    }

    wp.flags &= ~(UINT)WPF_RESTORETOMAXIMIZED;
    wp.showCmd = SW_SHOWNORMAL;
    wp.rcNormalPosition.left = targetLeft;
    wp.rcNormalPosition.top = targetTop;
    wp.rcNormalPosition.right = targetLeft + normalWidth;
    wp.rcNormalPosition.bottom = targetTop + normalHeight;

    BOOL disableTransitions = TRUE;
    DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
        &disableTransitions, sizeof(disableTransitions));
    SetWindowPlacement(hwnd, &wp);
    SetWindowPos(hwnd, NULL, targetLeft, targetTop, normalWidth, normalHeight,
        SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    BOOL enableTransitions = FALSE;
    DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
        &enableTransitions, sizeof(enableTransitions));

    RECT restoredRect;
    if (!GetWindowRect(hwnd, &restoredRect)) {
        restoredRect.left = targetLeft;
        restoredRect.top = targetTop;
        restoredRect.right = targetLeft + normalWidth;
        restoredRect.bottom = targetTop + normalHeight;
    }
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (!arr) return NULL;
    jlong values[4] = {
        (jlong)restoredRect.left,
        (jlong)restoredRect.top,
        (jlong)(restoredRect.right - restoredRect.left),
        (jlong)(restoredRect.bottom - restoredRect.top),
    };
    (*env)->SetLongArrayRegion(env, arr, 0, 4, values);
    return arr;
}

/* Synchronous outer-position move via `SetWindowPos(SWP_NOSIZE)`. Used by the
 * Windows touch title-bar drag path because Tao's `setOuterPosition` is
 * asynchronous (posts a user event to the Tao loop); under a touch stream
 * of 60-100 events/s that backlog produces visible lag. Calling
 * `SetWindowPos` directly from the touch-event handler keeps the window
 * pinned to the finger. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoWindowsDecoBridge_nativeSetWindowOuterPositionPx(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint xPx, jint yPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;
    SetWindowPos(hwnd, NULL, (int)xPx, (int)yPx, 0, 0,
        SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
}
