/**
 * JNI bridge: WGL renderer for the Tao backend on Windows.
 *
 * Creates an OpenGL 3.3 compatibility context so Skia's
 * `DirectContext.makeGL()` can render into the window's default framebuffer.
 * Modeled after the Metal helper on macOS.
 *
 * The GL surface is NOT the Tao HWND itself: it is a borderless WS_CHILD
 * "render surface" HWND covering the client area. Rendering directly into a
 * top-level window whose client area was extended into the caption via
 * WM_NCCALCSIZE triggers a long-standing Intel driver bug (HD 5xx/6xx,
 * Windows 10): the ICD derives the client origin from the window *style*
 * (WS_CAPTION ⇒ caption present), so SwapBuffers blits the buffer shifted
 * down by the theoretical caption height — black band at the top, content
 * offset from the real client coordinates, mouse hit-testing visually
 * miscalibrated. A caption-less child window has no style-derived
 * non-client area, so the blit always lands exactly on its rect; this is
 * the same child-surface architecture AWT/Skiko and Chromium use.
 * (Same symptom in the wild: sublimehq/sublime_text#3595.)
 *
 * The child is transparent to input (WS_EX_TRANSPARENT + HTTRANSPARENT):
 * mouse, touch, OLE drag-and-drop all fall through to the Tao HWND, whose
 * subclass (nucleus_tao_windows_deco.c) keeps handling them. Escape hatch:
 * set NUCLEUS_TAO_WIN_GL_DIRECT=1 to restore direct-on-HWND rendering.
 *
 * Sequence used by the JVM side:
 *   nativeAttach(hwnd)     → creates render-surface child + HDC + HGLRC
 *   nativeMakeCurrent(h)   → restores current context (defensive)
 *   nativeResize(h,w,h,s)  → resizes the child, stores dimensions for the
 *                            next BackendRenderTarget
 *   <Skia rendering>
 *   nativePresent(h)       → SwapBuffers
 *   nativeDetach(h)        → tear-down
 *
 * Linked libraries: opengl32.lib gdi32.lib user32.lib kernel32.lib
 */

#include <jni.h>
#include <windows.h>
#include <GL/gl.h>

/* /NODEFAULTLIB support */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

/* WGL extension function pointers (loaded via a temporary dummy context) */
typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);
typedef BOOL  (WINAPI *PFNWGLCHOOSEPIXELFORMATARBPROC)(HDC, const int *, const FLOAT *, UINT, int *, UINT *);
typedef BOOL  (WINAPI *PFNWGLSWAPINTERVALEXTPROC)(int);

static PFNWGLCREATECONTEXTATTRIBSARBPROC pwglCreateContextAttribsARB = NULL;
static PFNWGLCHOOSEPIXELFORMATARBPROC    pwglChoosePixelFormatARB    = NULL;
static PFNWGLSWAPINTERVALEXTPROC         pwglSwapIntervalEXT         = NULL;
static volatile BOOL extensionsLoaded = FALSE;

#define WGL_DRAW_TO_WINDOW_ARB                    0x2001
#define WGL_ACCELERATION_ARB                      0x2003
#define WGL_FULL_ACCELERATION_ARB                 0x2027
#define WGL_SUPPORT_OPENGL_ARB                    0x2010
#define WGL_DOUBLE_BUFFER_ARB                     0x2011
#define WGL_PIXEL_TYPE_ARB                        0x2013
#define WGL_TYPE_RGBA_ARB                         0x202B
#define WGL_COLOR_BITS_ARB                        0x2014
#define WGL_ALPHA_BITS_ARB                        0x201B
#define WGL_DEPTH_BITS_ARB                        0x2022
#define WGL_STENCIL_BITS_ARB                      0x2023

#define WGL_CONTEXT_MAJOR_VERSION_ARB             0x2091
#define WGL_CONTEXT_MINOR_VERSION_ARB             0x2092
#define WGL_CONTEXT_PROFILE_MASK_ARB              0x9126
#define WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB 0x00000002

static LRESULT CALLBACK dummyWndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    return DefWindowProcW(h, m, w, l);
}

/* Bootstrap: WGL extension entry-points are only retrievable while a legacy
 * context is current, so we create a throw-away window + 1.x context first. */
static void loadWglExtensions(void) {
    if (extensionsLoaded) return;

    HINSTANCE hInst = GetModuleHandleW(NULL);
    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = dummyWndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = L"NucleusTaoGlDummy";
    RegisterClassW(&wc);

    HWND hwnd = CreateWindowW(L"NucleusTaoGlDummy", L"", WS_OVERLAPPED,
        0, 0, 1, 1, NULL, NULL, hInst, NULL);
    if (!hwnd) { extensionsLoaded = TRUE; return; }

    HDC hdc = GetDC(hwnd);
    PIXELFORMATDESCRIPTOR pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.cStencilBits = 8;
    int pf = ChoosePixelFormat(hdc, &pfd);
    SetPixelFormat(hdc, pf, &pfd);

    HGLRC hglrc = wglCreateContext(hdc);
    if (hglrc) {
        wglMakeCurrent(hdc, hglrc);
        pwglCreateContextAttribsARB = (PFNWGLCREATECONTEXTATTRIBSARBPROC)
            wglGetProcAddress("wglCreateContextAttribsARB");
        pwglChoosePixelFormatARB = (PFNWGLCHOOSEPIXELFORMATARBPROC)
            wglGetProcAddress("wglChoosePixelFormatARB");
        pwglSwapIntervalEXT = (PFNWGLSWAPINTERVALEXTPROC)
            wglGetProcAddress("wglSwapIntervalEXT");
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(hglrc);
    }

    ReleaseDC(hwnd, hdc);
    DestroyWindow(hwnd);
    UnregisterClassW(L"NucleusTaoGlDummy", hInst);

    extensionsLoaded = TRUE;
}

typedef struct {
    HWND  hwnd;        /* Tao top-level window (input, decoration) */
    HWND  surfaceHwnd; /* render-surface child; == hwnd in direct mode */
    HDC   hdc;
    HGLRC hglrc;
    int   widthPx;
    int   heightPx;
    float scale;
} WglAttachment;

/* ================================================================== */
/*  Render-surface child window                                        */
/* ================================================================== */

static const wchar_t *kSurfaceClassName = L"NucleusTaoGlSurface";
static volatile LONG sSurfaceClassRegistered = 0;

static LRESULT CALLBACK surfaceWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_NCHITTEST:
        /* Transparent to every hit test: mouse, touch (WM_POINTER) and
         * WindowFromPoint (OLE drag-and-drop) all resolve to the Tao
         * parent, whose deco subclass owns input handling. */
        return HTTRANSPARENT;
    case WM_ERASEBKGND:
        /* GL owns every pixel. The themed startup fill stays on the parent
         * (deco WM_ERASEBKGND); without WS_CLIPCHILDREN it covers this
         * child's area too until the first SwapBuffers. */
        return 1;
    case WM_PAINT:
        /* Painting happens via WGL outside the paint cycle; validate so
         * the update region doesn't refire WM_PAINT forever. */
        ValidateRect(hwnd, NULL);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

static void ensureSurfaceClassRegistered(void) {
    if (InterlockedCompareExchange(&sSurfaceClassRegistered, 1, 0) != 0) return;
    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    /* CS_OWNDC: GetDC(hwnd) returns a stable HDC for the window's
     * lifetime — required so wglMakeCurrent's HDC matches across frames. */
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = surfaceWndProc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = kSurfaceClassName;
    wc.hbrBackground = NULL;
    RegisterClassW(&wc);
}

static BOOL directModeRequested(void) {
    char buf[8];
    DWORD n = GetEnvironmentVariableA("NUCLEUS_TAO_WIN_GL_DIRECT", buf, sizeof(buf));
    return n == 1 && buf[0] == '1';
}

/* Creates the render-surface child covering the parent's current client
 * area. Returns NULL on failure (caller falls back to direct mode). */
static HWND createRenderSurface(HWND parent) {
    ensureSurfaceClassRegistered();

    RECT rc;
    if (!GetClientRect(parent, &rc)) { rc.right = 1; rc.bottom = 1; }
    int w = (int)(rc.right - rc.left); if (w < 1) w = 1;
    int h = (int)(rc.bottom - rc.top); if (h < 1) h = 1;

    HWND surface = CreateWindowExW(
        WS_EX_TRANSPARENT | WS_EX_NOPARENTNOTIFY,
        kSurfaceClassName, L"",
        WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
        0, 0, w, h,
        parent, NULL, GetModuleHandleW(NULL), NULL);
    if (!surface) return NULL;

    /* Bottom of the sibling z-order: NativeView children (WebView, …)
     * attached later must composite above the Compose canvas. */
    SetWindowPos(surface, HWND_BOTTOM, 0, 0, 0, 0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    return surface;
}

/* ================================================================== */
/*  Host pixel-format / HGLRC sharing                                  */
/*                                                                     */
/*  Exported so the overlay+popup DLL (nucleus_tao_windows_native_view) */
/*  can join the host's WGL share group via                            */
/*  `wglCreateContextAttribsARB(.., hostHGLRC, ..)`. The overlay HDC's  */
/*  pixel format MUST match the host's exactly (wglShareLists           */
/*  invariant carried over to the ARB share path on every known         */
/*  driver), so we expose the cached PFD + format index for             */
/*  `SetPixelFormat(overlayDC, hostFormatIndex, &cachedPfd)`.           */
/*                                                                     */
/*  Resolved by overlay_gl.c via                                        */
/*  `GetProcAddress(GetModuleHandleW(L"nucleus_tao_gl.dll"), ..)`.      */
/* ================================================================== */

static int                   sHostPixelFormatIndex = 0;
static PIXELFORMATDESCRIPTOR sHostPfd;
static HGLRC                 sHostHglrc = NULL;

__declspec(dllexport) HGLRC nucleus_tao_host_hglrc(void) {
    return sHostHglrc;
}

__declspec(dllexport) int nucleus_tao_host_pixel_format(PIXELFORMATDESCRIPTOR *outPfd) {
    if (outPfd) *outPfd = sHostPfd;
    return sHostPixelFormatIndex;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: dev.nucleusframework.window.tao                 */
/*  Class:   NativeTaoGlBridge                                         */
/* ================================================================== */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return 0;

    loadWglExtensions();

    /* Render-surface child (see file header). Falls back to rendering
     * directly on the Tao HWND if creation fails or the escape hatch
     * NUCLEUS_TAO_WIN_GL_DIRECT=1 is set. */
    HWND surfaceHwnd = NULL;
    if (!directModeRequested()) {
        surfaceHwnd = createRenderSurface(hwnd);
    }
    if (!surfaceHwnd) surfaceHwnd = hwnd;

    HDC hdc = GetDC(surfaceHwnd);
    if (!hdc) {
        if (surfaceHwnd != hwnd) DestroyWindow(surfaceHwnd);
        return 0;
    }

    int pixelFormat = 0;
    PIXELFORMATDESCRIPTOR pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;

    if (pwglChoosePixelFormatARB) {
        /* WGL_ALPHA_BITS_ARB=8: required so the host shares its pixel
         * format with transparent overlay/popup HWNDs. The host stays
         * visually opaque (no DwmEnableBlurBehindWindow on it); the alpha
         * channel just sits unused in the back buffer. Aligning the
         * format also lets the overlay's HGLRC join the share group via
         * wglCreateContextAttribsARB(.., hostHGLRC, ..) — wglShareLists
         * requires identical pixel formats across share-group members. */
        const int attribs[] = {
            WGL_DRAW_TO_WINDOW_ARB, GL_TRUE,
            WGL_SUPPORT_OPENGL_ARB, GL_TRUE,
            WGL_DOUBLE_BUFFER_ARB,  GL_TRUE,
            WGL_ACCELERATION_ARB,   WGL_FULL_ACCELERATION_ARB,
            WGL_PIXEL_TYPE_ARB,     WGL_TYPE_RGBA_ARB,
            WGL_COLOR_BITS_ARB,     32,
            WGL_ALPHA_BITS_ARB,     8,
            WGL_DEPTH_BITS_ARB,     0,
            WGL_STENCIL_BITS_ARB,   8,
            0
        };
        UINT numFormats = 0;
        pwglChoosePixelFormatARB(hdc, attribs, NULL, 1, &pixelFormat, &numFormats);
        if (numFormats == 0) pixelFormat = 0;
    }
    if (pixelFormat == 0) {
        pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
        pfd.iPixelType = PFD_TYPE_RGBA;
        pfd.cColorBits = 32;
        pfd.cAlphaBits = 8;
        pfd.cStencilBits = 8;
        pixelFormat = ChoosePixelFormat(hdc, &pfd);
    }

    DescribePixelFormat(hdc, pixelFormat, sizeof(pfd), &pfd);
    if (!SetPixelFormat(hdc, pixelFormat, &pfd)) {
        ReleaseDC(surfaceHwnd, hdc);
        if (surfaceHwnd != hwnd) DestroyWindow(surfaceHwnd);
        return 0;
    }
    /* Cache for cross-DLL share-group reuse. */
    sHostPixelFormatIndex = pixelFormat;
    sHostPfd = pfd;

    HGLRC hglrc = NULL;
    if (pwglCreateContextAttribsARB) {
        const int ctxAttribs[] = {
            WGL_CONTEXT_MAJOR_VERSION_ARB, 3,
            WGL_CONTEXT_MINOR_VERSION_ARB, 3,
            WGL_CONTEXT_PROFILE_MASK_ARB,  WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB,
            0
        };
        hglrc = pwglCreateContextAttribsARB(hdc, NULL, ctxAttribs);
    }
    if (!hglrc) {
        hglrc = wglCreateContext(hdc);
    }
    if (!hglrc) {
        ReleaseDC(surfaceHwnd, hdc);
        if (surfaceHwnd != hwnd) DestroyWindow(surfaceHwnd);
        return 0;
    }

    wglMakeCurrent(hdc, hglrc);
    /* VSync ON: SwapBuffers blocks until the next display refresh. Required
     * for smooth scroll: Compose's `withFrameNanos` animation steps land on
     * regular display-aligned ticks, otherwise the irregular cadence of
     * software-throttled frames feels juddery compared to AWT/Skiko. */
    if (pwglSwapIntervalEXT) pwglSwapIntervalEXT(1);

    WglAttachment *att = (WglAttachment *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(WglAttachment));
    if (!att) {
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(hglrc);
        ReleaseDC(surfaceHwnd, hdc);
        if (surfaceHwnd != hwnd) DestroyWindow(surfaceHwnd);
        return 0;
    }
    att->hwnd = hwnd;
    att->surfaceHwnd = surfaceHwnd;
    att->hdc = hdc;
    att->hglrc = hglrc;
    att->scale = 1.0f;
    sHostHglrc = hglrc;
    return (jlong)(uintptr_t)att;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    wglMakeCurrent(NULL, NULL);
    if (sHostHglrc == att->hglrc) sHostHglrc = NULL;
    wglDeleteContext(att->hglrc);
    ReleaseDC(att->surfaceHwnd, att->hdc);
    if (att->surfaceHwnd != att->hwnd && IsWindow(att->surfaceHwnd)) {
        DestroyWindow(att->surfaceHwnd);
    }
    HeapFree(GetProcessHeap(), 0, att);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    wglMakeCurrent(att->hdc, att->hglrc);
}

/* Releases whatever GL context is current on the calling thread. The render
 * thread calls this after flushAndSubmit so the dedicated swap thread can bind
 * the same HGLRC for SwapBuffers — a WGL context is current on one thread at a
 * time, so the two must never hold it simultaneously. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeReleaseCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz; (void)handle;
    wglMakeCurrent(NULL, NULL);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    att->widthPx = (int)widthPx;
    att->heightPx = (int)heightPx;
    att->scale = scale;
    /* Keep the render-surface child glued to the client area. Runs in the
     * same event-dispatch turn as the parent's WM_SIZE (before the next
     * render), so the surface never lags the window visually. */
    if (att->surfaceHwnd != att->hwnd && IsWindow(att->surfaceHwnd)) {
        SetWindowPos(att->surfaceHwnd, NULL, 0, 0, att->widthPx, att->heightPx,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_DEFERERASE);
    }
    /* Update GL viewport so subsequent Skia surface creation matches. */
    wglMakeCurrent(att->hdc, att->hglrc);
    glViewport(0, 0, att->widthPx, att->heightPx);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    SwapBuffers(att->hdc);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    return att ? (jint)att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    return att ? (jint)att->heightPx : 0;
}
