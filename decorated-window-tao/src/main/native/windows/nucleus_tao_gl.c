/**
 * JNI bridge: renderer for the Tao backend on Windows.
 *
 * Two interchangeable backends sit behind the same JNI surface:
 *
 *   - EGL / ANGLE (preferred): OpenGL ES translated to Direct3D 11 by ANGLE
 *     (libEGL.dll + libGLESv2.dll, shipped alongside this DLL). D3D11 has a
 *     guaranteed WARP software fallback, so it works on RDP sessions, VMs and
 *     driverless machines where the only OpenGL a window can obtain is the
 *     GDI-generic 1.1 context that Skia's DirectContext.makeGL() rejects.
 *     Skia still uses its GL backend — it auto-assembles the GL interface from
 *     the current ANGLE ES context via eglGetProcAddress, exactly like the
 *     Skia ANGLE bots.
 *
 *   - WGL (fallback): a native OpenGL 3.3 compatibility context, the original
 *     path. Used when ANGLE is unavailable or fails to initialise, and on real
 *     GPUs it remains a no-extra-dependency option.
 *
 * Selection: env var NUCLEUS_TAO_WIN_RENDER = auto (default, try ANGLE then
 * WGL) | angle | wgl. The JNI surface (attach/makeCurrent/releaseCurrent/
 * present/resize/detach) is identical for both; each attachment records its
 * backend kind and dispatches internally.
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
 * set NUCLEUS_TAO_WIN_GL_DIRECT=1 to restore direct-on-HWND rendering (WGL
 * only — the ANGLE path always uses a child surface).
 *
 * Sequence used by the JVM side:
 *   nativeAttach(hwnd)     → creates render-surface child + GL/ES context
 *   nativeMakeCurrent(h)   → restores current context (defensive)
 *   nativeResize(h,w,h,s)  → resizes the child, stores dimensions for the
 *                            next BackendRenderTarget
 *   <Skia rendering>
 *   nativePresent(h)       → SwapBuffers / eglSwapBuffers
 *   nativeDetach(h)        → tear-down
 *
 * Linked libraries: opengl32.lib gdi32.lib user32.lib kernel32.lib
 * (ANGLE is loaded dynamically via LoadLibrary — no import lib.)
 */

#include <jni.h>
#include <windows.h>
#include <GL/gl.h>

/* ANGLE EGL entry points are resolved at runtime via GetProcAddress; we only
 * need the Khronos typedefs and the ANGLE-specific platform constants, so we
 * suppress the prototypes (we never link libEGL). */
#define EGL_EGL_PROTOTYPES 0
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>

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

/* Backend kind recorded per attachment. */
#define BACKEND_WGL 0
#define BACKEND_EGL 1

/* Render-backend selection (NUCLEUS_TAO_WIN_RENDER). */
#define SEL_AUTO 0
#define SEL_EGL  1
#define SEL_WGL  2

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

/* ================================================================== */
/*  ANGLE / EGL entry points (resolved from libEGL.dll at runtime)     */
/* ================================================================== */

typedef void (APIENTRY *PFN_glViewport)(int, int, int, int);

static HMODULE sLibEGL    = NULL;
static HMODULE sLibGLESv2 = NULL;
static volatile BOOL eglLoaded    = FALSE;
static BOOL          eglAvailable = FALSE;

static PFNEGLGETPROCADDRESSPROC        pEglGetProcAddress        = NULL;
static PFNEGLGETPLATFORMDISPLAYEXTPROC pEglGetPlatformDisplayEXT = NULL;
static PFNEGLGETDISPLAYPROC            pEglGetDisplay            = NULL;
static PFNEGLINITIALIZEPROC            pEglInitialize            = NULL;
static PFNEGLBINDAPIPROC               pEglBindAPI               = NULL;
static PFNEGLCHOOSECONFIGPROC          pEglChooseConfig          = NULL;
static PFNEGLCREATEWINDOWSURFACEPROC   pEglCreateWindowSurface   = NULL;
static PFNEGLCREATECONTEXTPROC         pEglCreateContext         = NULL;
static PFNEGLMAKECURRENTPROC           pEglMakeCurrent           = NULL;
static PFNEGLSWAPBUFFERSPROC           pEglSwapBuffers           = NULL;
static PFNEGLSWAPINTERVALPROC          pEglSwapInterval          = NULL;
static PFNEGLDESTROYCONTEXTPROC        pEglDestroyContext        = NULL;
static PFNEGLDESTROYSURFACEPROC        pEglDestroySurface        = NULL;
static PFN_glViewport                  pglViewport               = NULL;

static void loadEgl(void) {
    if (eglLoaded) return;
    eglLoaded = TRUE;

    /* The DLLs ship next to the other nucleus_tao_*.dll; the JVM side extracts
     * them and pre-loads libGLESv2/libEGL so they resolve by bare name here. */
    sLibEGL = LoadLibraryW(L"libEGL.dll");
    if (!sLibEGL) return;
    sLibGLESv2 = LoadLibraryW(L"libGLESv2.dll"); /* libEGL also pulls it in */

    pEglGetProcAddress      = (PFNEGLGETPROCADDRESSPROC)      GetProcAddress(sLibEGL, "eglGetProcAddress");
    pEglGetDisplay          = (PFNEGLGETDISPLAYPROC)          GetProcAddress(sLibEGL, "eglGetDisplay");
    pEglInitialize          = (PFNEGLINITIALIZEPROC)          GetProcAddress(sLibEGL, "eglInitialize");
    pEglBindAPI             = (PFNEGLBINDAPIPROC)             GetProcAddress(sLibEGL, "eglBindAPI");
    pEglChooseConfig        = (PFNEGLCHOOSECONFIGPROC)        GetProcAddress(sLibEGL, "eglChooseConfig");
    pEglCreateWindowSurface = (PFNEGLCREATEWINDOWSURFACEPROC) GetProcAddress(sLibEGL, "eglCreateWindowSurface");
    pEglCreateContext       = (PFNEGLCREATECONTEXTPROC)       GetProcAddress(sLibEGL, "eglCreateContext");
    pEglMakeCurrent         = (PFNEGLMAKECURRENTPROC)         GetProcAddress(sLibEGL, "eglMakeCurrent");
    pEglSwapBuffers         = (PFNEGLSWAPBUFFERSPROC)         GetProcAddress(sLibEGL, "eglSwapBuffers");
    pEglSwapInterval        = (PFNEGLSWAPINTERVALPROC)        GetProcAddress(sLibEGL, "eglSwapInterval");
    pEglDestroyContext      = (PFNEGLDESTROYCONTEXTPROC)      GetProcAddress(sLibEGL, "eglDestroyContext");
    pEglDestroySurface      = (PFNEGLDESTROYSURFACEPROC)      GetProcAddress(sLibEGL, "eglDestroySurface");

    pEglGetPlatformDisplayEXT = (PFNEGLGETPLATFORMDISPLAYEXTPROC)
        GetProcAddress(sLibEGL, "eglGetPlatformDisplayEXT");
    if (!pEglGetPlatformDisplayEXT && pEglGetProcAddress) {
        pEglGetPlatformDisplayEXT = (PFNEGLGETPLATFORMDISPLAYEXTPROC)
            pEglGetProcAddress("eglGetPlatformDisplayEXT");
    }

    if (sLibGLESv2) pglViewport = (PFN_glViewport) GetProcAddress(sLibGLESv2, "glViewport");
    if (!pglViewport && pEglGetProcAddress) pglViewport = (PFN_glViewport) pEglGetProcAddress("glViewport");

    eglAvailable = (pEglInitialize && pEglChooseConfig && pEglCreateContext &&
                    pEglCreateWindowSurface && pEglMakeCurrent && pEglSwapBuffers &&
                    pEglDestroyContext && pEglDestroySurface);
}

/* Skia GrGLGetProc trampoline. The default DirectContext.makeGL() assembles
 * its GL interface via WGL (wglGetProcAddress + opengl32) and therefore fails
 * under an ANGLE context. Skia's DirectContext.makeGLWithInterface() instead
 * takes a GrGLAssembledInterface built from this getProc, which resolves names
 * through ANGLE's eglGetProcAddress (it returns core ES entry points too) —
 * the same mechanism Skiko uses for its ANGLE backend. The JVM passes the
 * address of this function to GLAssembledInterface.createFromNativePointers. */
typedef void (*NucleusGLFuncPtr)(void);
static NucleusGLFuncPtr nucleus_tao_egl_get_proc(void *ctx, const char *name) {
    (void)ctx;
    if (!pEglGetProcAddress) return NULL;
    return (NucleusGLFuncPtr) pEglGetProcAddress(name);
}

/* ================================================================== */
/*  Attachment record                                                  */
/* ================================================================== */

typedef struct {
    int   backend;     /* BACKEND_WGL or BACKEND_EGL */
    HWND  hwnd;        /* Tao top-level window (input, decoration) */
    HWND  surfaceHwnd; /* render-surface child; == hwnd in WGL direct mode */
    /* WGL */
    HDC   hdc;
    HGLRC hglrc;
    /* EGL / ANGLE */
    EGLDisplay eglDisplay;
    EGLSurface eglSurface;
    EGLContext eglContext;
    EGLConfig  eglConfig;
    int   widthPx;
    int   heightPx;
    float scale;
} GlAttachment;

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

static int renderSelection(void) {
    char buf[16];
    DWORD n = GetEnvironmentVariableA("NUCLEUS_TAO_WIN_RENDER", buf, (DWORD)sizeof(buf));
    if (n == 0 || n >= sizeof(buf)) return SEL_AUTO;
    if (lstrcmpiA(buf, "wgl") == 0) return SEL_WGL;
    if (lstrcmpiA(buf, "angle") == 0 || lstrcmpiA(buf, "egl") == 0 || lstrcmpiA(buf, "d3d") == 0) return SEL_EGL;
    return SEL_AUTO; /* "auto" and anything unrecognised */
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
/*  Host pixel-format / context sharing                                */
/*                                                                     */
/*  Exported so the overlay+popup DLL (nucleus_tao_windows_native_view) */
/*  can join the host's share group. For WGL it joins via              */
/*  `wglCreateContextAttribsARB(.., hostHGLRC, ..)`; for EGL/ANGLE via  */
/*  `eglCreateContext(.., hostEglContext, ..)` on the host's display +  */
/*  config. Resolved by overlay_gl.c via GetProcAddress on this DLL.    */
/* ================================================================== */

static int                   sHostPixelFormatIndex = 0;
static PIXELFORMATDESCRIPTOR sHostPfd;
static HGLRC                 sHostHglrc = NULL;

static int                   sHostBackend    = BACKEND_WGL;
static EGLDisplay            sHostEglDisplay = EGL_NO_DISPLAY;
static EGLContext            sHostEglContext = EGL_NO_CONTEXT;
static EGLConfig             sHostEglConfig  = NULL;

__declspec(dllexport) HGLRC nucleus_tao_host_hglrc(void) {
    return sHostHglrc;
}

__declspec(dllexport) int nucleus_tao_host_pixel_format(PIXELFORMATDESCRIPTOR *outPfd) {
    if (outPfd) *outPfd = sHostPfd;
    return sHostPixelFormatIndex;
}

__declspec(dllexport) int nucleus_tao_host_backend(void) {
    return sHostBackend;
}

__declspec(dllexport) void *nucleus_tao_host_egl_display(void) {
    return (void *)sHostEglDisplay;
}

__declspec(dllexport) void *nucleus_tao_host_egl_context(void) {
    return (void *)sHostEglContext;
}

__declspec(dllexport) void *nucleus_tao_host_egl_config(void) {
    return (void *)sHostEglConfig;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

/* ================================================================== */
/*  WGL attach (fallback path — original implementation)               */
/* ================================================================== */

static GlAttachment *attachWgl(HWND hwnd) {
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
        return NULL;
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
        return NULL;
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
        return NULL;
    }

    wglMakeCurrent(hdc, hglrc);
    /* VSync ON: SwapBuffers blocks until the next display refresh. Required
     * for smooth scroll: Compose's `withFrameNanos` animation steps land on
     * regular display-aligned ticks, otherwise the irregular cadence of
     * software-throttled frames feels juddery compared to AWT/Skiko. */
    if (pwglSwapIntervalEXT) pwglSwapIntervalEXT(1);

    GlAttachment *att = (GlAttachment *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(GlAttachment));
    if (!att) {
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(hglrc);
        ReleaseDC(surfaceHwnd, hdc);
        if (surfaceHwnd != hwnd) DestroyWindow(surfaceHwnd);
        return NULL;
    }
    att->backend = BACKEND_WGL;
    att->hwnd = hwnd;
    att->surfaceHwnd = surfaceHwnd;
    att->hdc = hdc;
    att->hglrc = hglrc;
    att->scale = 1.0f;

    sHostBackend = BACKEND_WGL;
    sHostHglrc = hglrc;
    return att;
}

/* ================================================================== */
/*  EGL / ANGLE attach (preferred path)                                */
/* ================================================================== */

/* Asks ANGLE for a Direct3D-11 display of the given device type
 * (hardware adapter, or WARP software rasteriser). */
static EGLDisplay angleD3D11Display(EGLint deviceType) {
    const EGLint attribs[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE,        EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE, deviceType,
        EGL_NONE
    };
    return pEglGetPlatformDisplayEXT(EGL_PLATFORM_ANGLE_ANGLE, EGL_DEFAULT_DISPLAY, attribs);
}

static GlAttachment *attachEgl(HWND hwnd) {
    loadEgl();
    if (!eglAvailable || !pEglGetPlatformDisplayEXT) return NULL;

    /* Try a hardware D3D11 adapter first; fall back to WARP (the software
     * D3D11 rasteriser available on RDP / VMs / driverless boxes). */
    const EGLint deviceTypes[] = {
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE,
    };
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLint major = 0, minor = 0;
    for (int i = 0; i < 2; ++i) {
        EGLDisplay d = angleD3D11Display(deviceTypes[i]);
        if (d != EGL_NO_DISPLAY && pEglInitialize(d, &major, &minor)) { dpy = d; break; }
    }
    if (dpy == EGL_NO_DISPLAY) return NULL;

    if (pEglBindAPI) pEglBindAPI(EGL_OPENGL_ES_API);

    /* Alpha 8 + stencil 8, matching the WGL format so transparent overlay /
     * popup surfaces can share this config. Depth unused (Skia owns it). */
    const EGLint cfgAttribs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,    8,
        EGL_GREEN_SIZE,  8,
        EGL_BLUE_SIZE,   8,
        EGL_ALPHA_SIZE,  8,
        EGL_DEPTH_SIZE,  0,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = NULL;
    EGLint numConfig = 0;
    if (!pEglChooseConfig(dpy, cfgAttribs, &config, 1, &numConfig) || numConfig == 0) {
        return NULL;
    }

    HWND surfaceHwnd = createRenderSurface(hwnd);
    if (!surfaceHwnd) return NULL;

    const EGLint surfAttribs[] = { EGL_NONE };
    EGLSurface surface = pEglCreateWindowSurface(
        dpy, config, (EGLNativeWindowType)surfaceHwnd, surfAttribs);
    if (surface == EGL_NO_SURFACE) {
        DestroyWindow(surfaceHwnd);
        return NULL;
    }

    /* Request an ES 3 context (Skia prefers it); fall back to ES 2. */
    const EGLint ctxAttribs3[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE };
    const EGLint ctxAttribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs3);
    if (ctx == EGL_NO_CONTEXT) {
        ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs2);
    }
    if (ctx == EGL_NO_CONTEXT) {
        pEglDestroySurface(dpy, surface);
        DestroyWindow(surfaceHwnd);
        return NULL;
    }

    if (!pEglMakeCurrent(dpy, surface, surface, ctx)) {
        pEglDestroyContext(dpy, ctx);
        pEglDestroySurface(dpy, surface);
        DestroyWindow(surfaceHwnd);
        return NULL;
    }
    /* VSync ON — same rationale as the WGL path. */
    if (pEglSwapInterval) pEglSwapInterval(dpy, 1);

    GlAttachment *att = (GlAttachment *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(GlAttachment));
    if (!att) {
        pEglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        pEglDestroyContext(dpy, ctx);
        pEglDestroySurface(dpy, surface);
        DestroyWindow(surfaceHwnd);
        return NULL;
    }
    att->backend = BACKEND_EGL;
    att->hwnd = hwnd;
    att->surfaceHwnd = surfaceHwnd;
    att->eglDisplay = dpy;
    att->eglSurface = surface;
    att->eglContext = ctx;
    att->eglConfig = config;
    att->scale = 1.0f;

    sHostBackend = BACKEND_EGL;
    sHostEglDisplay = dpy;
    sHostEglContext = ctx;
    sHostEglConfig = config;
    return att;
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

    int sel = renderSelection();

    GlAttachment *att = NULL;
    /* Preferred: ANGLE/D3D11 (auto + forced angle). Each attempt uses its own
     * fresh child surface, so a failure leaves no pixel-format residue for the
     * WGL fallback. */
    if (sel != SEL_WGL) {
        att = attachEgl(hwnd);
    }
    if (!att && sel != SEL_EGL) {
        att = attachWgl(hwnd);
    }
    return (jlong)(uintptr_t)att;
}

/* Forces the WGL backend. Used by the JVM as a fallback when an EGL/ANGLE
 * attachment succeeds natively but Skia can't build a usable DirectContext on
 * it (the EGL viability is only known after makeGLWithInterface, in Java). */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeAttachWgl(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return 0;
    return (jlong)(uintptr_t)attachWgl(hwnd);
}

/* Returns the backend of an attachment: BACKEND_WGL (0) or BACKEND_EGL (1). */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeBackend(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->backend : (jint)-1;
}

/* Address of the GrGLGetProc trampoline for ANGLE (see nucleus_tao_egl_get_proc).
 * Passed to GLAssembledInterface.createFromNativePointers as the fPtr. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeEglGetProcFn(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong)(uintptr_t)&nucleus_tao_egl_get_proc;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;

    if (att->backend == BACKEND_EGL) {
        /* The EGLDisplay is process-wide (shared with overlays); never
         * eglTerminate it here — just drop this window's context + surface. */
        pEglMakeCurrent(att->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (sHostEglContext == att->eglContext) sHostEglContext = EGL_NO_CONTEXT;
        pEglDestroyContext(att->eglDisplay, att->eglContext);
        pEglDestroySurface(att->eglDisplay, att->eglSurface);
        if (IsWindow(att->surfaceHwnd)) DestroyWindow(att->surfaceHwnd);
    } else {
        wglMakeCurrent(NULL, NULL);
        if (sHostHglrc == att->hglrc) sHostHglrc = NULL;
        wglDeleteContext(att->hglrc);
        ReleaseDC(att->surfaceHwnd, att->hdc);
        if (att->surfaceHwnd != att->hwnd && IsWindow(att->surfaceHwnd)) {
            DestroyWindow(att->surfaceHwnd);
        }
    }
    HeapFree(GetProcessHeap(), 0, att);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    if (att->backend == BACKEND_EGL) {
        pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
    } else {
        wglMakeCurrent(att->hdc, att->hglrc);
    }
}

/* Releases whatever GL context is current on the calling thread. The render
 * thread calls this after flushAndSubmit so the dedicated swap thread can bind
 * the same context for nativePresent — a GL context is current on one thread at
 * a time, so the two must never hold it simultaneously. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeReleaseCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (att && att->backend == BACKEND_EGL) {
        pEglMakeCurrent(att->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    } else {
        wglMakeCurrent(NULL, NULL);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
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
    /* Update GL viewport so subsequent Skia surface creation matches. The
     * ANGLE window surface tracks the HWND size automatically; the explicit
     * viewport is still harmless and keeps both paths symmetrical. */
    if (att->backend == BACKEND_EGL) {
        pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
        if (pglViewport) pglViewport(0, 0, att->widthPx, att->heightPx);
    } else {
        wglMakeCurrent(att->hdc, att->hglrc);
        glViewport(0, 0, att->widthPx, att->heightPx);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    if (att->backend == BACKEND_EGL) {
        pEglSwapBuffers(att->eglDisplay, att->eglSurface);
    } else {
        SwapBuffers(att->hdc);
    }
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->heightPx : 0;
}
