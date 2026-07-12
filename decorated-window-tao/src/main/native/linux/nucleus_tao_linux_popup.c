/**
 * JNI bridge: standalone transparent popup panel for Linux (X11 / XWayland).
 *
 * The Linux counterpart of `windows/nucleus_tao_windows_popup.c` and
 * `macos/popup_panel.m`: a top-level, ownerless, override-redirect ARGB32
 * X11 window with per-pixel transparency, positioned in global screen
 * coordinates, that never appears in the taskbar / Alt-Tab and never
 * activates the application. Rendering is done by the Kotlin host through
 * `NativeTaoEglBridge.nativeAttachX11` on the window this module creates.
 *
 * Why raw X11 and not GTK: GDK's backend is process-wide (native Wayland
 * on Wayland sessions), and Wayland has no ownerless globally-positioned
 * topmost surface (no layer-shell in vendored Tao, `gtk_window_move` is a
 * no-op on xdg-toplevels). A raw X11 window on its own `XOpenDisplay`
 * connection is an independent X client that works even while the app
 * itself is a native Wayland client — through XWayland, which is present
 * on effectively all desktops. When `XOpenDisplay` fails (rare
 * Wayland-only kiosks), `nativeIsAvailable` returns false and the caller
 * falls back to a regular window.
 *
 * Visual selection: the window's visual is derived from EGL itself (first
 * alpha=8 desktop-GL `EGLConfig` whose `EGL_NATIVE_VISUAL_ID` is a 32-bit
 * X visual). `nativeAttachX11` later resolves the same `EGLDisplay` for
 * the same `Display*` and matches that exact config — no child-window
 * fallback, alpha preserved end to end.
 *
 * Threading model — two X connections, each single-threaded (no
 * XInitThreads dependency):
 *   - The COMMAND connection (`g_cmd_dpy`) is owned by the Tao main
 *     thread. Every JNI entry point below runs on it (the composable
 *     wrapper guarantees this): create/move/map/unmap/cursor/destroy.
 *     The Kotlin host also hands this `Display*` to
 *     `NativeTaoEglBridge.nativeAttachX11`, so EGL work stays on the
 *     same thread/connection.
 *   - Each panel owns an EVENT connection + thread: it opens its own
 *     `Display`, calls `XSelectInput` on the panel XID (event masks are
 *     per-client, so this works across connections) and blocks in a
 *     `poll()` loop on the X fd + a quit pipe. Input events are forwarded
 *     to Java through cached JNI method IDs (same pattern as
 *     `nucleus_tao_linux_widget.c`).
 *
 * Outside-click: XI2 raw ButtonPress on the root window — the X11 analog
 * of the Windows `WH_MOUSE_LL` hook (raw events are observe-only, don't
 * consume, and multiple clients may listen). Fully global on X11
 * sessions; under XWayland raw events only fire while X11 surfaces have
 * input focus — the tray-icon toggle covers the remaining cases.
 *
 * Keyboard: clicking the panel while focusable calls
 * `XSetInputFocus(RevertToParent)` (the `takeKeyboardFocus()` equivalent).
 * Key events forward the ACTIVE-layout keysym resolved to a Unicode code
 * point via libxkbcommon (`xkb_keysym_to_utf32` — needed for non-Latin-1
 * layouts, e.g. Hebrew), plus a LATIN keysym scanned across XKB groups as
 * the vkCode so shortcuts (Ctrl+C on a Hebrew layout) land on the right
 * `Key`. `linuxNativeKeyToAwt` (TaoKeyLinux.kt) does the keysym → AWT VK
 * translation Kotlin-side.
 *
 * Build: compiled against the system X11/XI2 headers (guaranteed present
 * wherever Tao's GTK 3 dev headers are, i.e. every build environment) but
 * linked with `-ldl` only — libX11.so.6, libXi.so.6, libxkbcommon.so.0
 * and libEGL.so.1 are dlopen-ed at runtime so the .so ships standalone,
 * matching `nucleus_tao_egl.c`. XEvent/XVisualInfo layouts are stable
 * Xlib ABI; using the real headers avoids hand-redeclaring the XEvent
 * union.
 */

#include <jni.h>
#include <dlfcn.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/XKBlib.h>
#include <X11/cursorfont.h>
#include <X11/extensions/XInput2.h>

#define NUCLEUS_TAO_POPUP_DEBUG 0
#if NUCLEUS_TAO_POPUP_DEBUG
#define DBG(...) fprintf(stderr, "[nucleus_tao_linux_popup] " __VA_ARGS__)
#else
#define DBG(...) ((void)0)
#endif

/* ── Wire format (must stay in sync with TaoNativeWireFormat.kt) ────────── */

#define WIRE_PTR_DOWN 1
#define WIRE_PTR_UP   2
#define WIRE_PTR_MOVE 3

#define WIRE_BUTTON_NONE      0
#define WIRE_BUTTON_PRIMARY   1
#define WIRE_BUTTON_SECONDARY 2

#define WIRE_KEY_DOWN 1
#define WIRE_KEY_UP   2

#define WIRE_MOD_SHIFT 0x1
#define WIRE_MOD_CTRL  0x2
#define WIRE_MOD_ALT   0x4
#define WIRE_MOD_META  0x8

/* TaoCursorIcon codes (NativeTaoBridge.kt). */
#define ICON_DEFAULT     0
#define ICON_TEXT        1
#define ICON_HAND        2
#define ICON_CROSSHAIR   3
#define ICON_WAIT        4
#define ICON_MOVE        5
#define ICON_NOT_ALLOWED 6
#define ICON_HELP        7
#define ICON_PROGRESS    8
#define ICON_EW_RESIZE   9
#define ICON_NS_RESIZE   10
#define ICON_NESW_RESIZE 11
#define ICON_NWSE_RESIZE 12

/* ── dlopen-ed symbol tables ────────────────────────────────────────────── */

typedef struct xkb_keysym_dummy xkb_keysym_dummy; /* unused, keeps clang-tidy calm */

static struct {
    int initialized;

    /* libX11 */
    Display *(*XOpenDisplay)(const char *);
    int (*XCloseDisplay)(Display *);
    Window (*XCreateWindow)(Display *, Window, int, int, unsigned, unsigned,
                            unsigned, int, unsigned, Visual *, unsigned long,
                            XSetWindowAttributes *);
    int (*XDestroyWindow)(Display *, Window);
    int (*XMapRaised)(Display *, Window);
    int (*XUnmapWindow)(Display *, Window);
    int (*XRaiseWindow)(Display *, Window);
    int (*XMoveResizeWindow)(Display *, Window, int, int, unsigned, unsigned);
    int (*XFlush)(Display *);
    int (*XSync)(Display *, Bool);
    int (*XSelectInput)(Display *, Window, long);
    int (*XNextEvent)(Display *, XEvent *);
    int (*XPending)(Display *);
    Colormap (*XCreateColormap)(Display *, Window, Visual *, int);
    int (*XFreeColormap)(Display *, Colormap);
    XVisualInfo *(*XGetVisualInfo)(Display *, long, XVisualInfo *, int *);
    int (*XFree)(void *);
    int (*XSetInputFocus)(Display *, Window, int, Time);
    Cursor (*XCreateFontCursor)(Display *, unsigned int);
    int (*XDefineCursor)(Display *, Window, Cursor);
    int (*XFreeCursor)(Display *, Cursor);
    int (*XStoreName)(Display *, Window, const char *);
    char *(*XResourceManagerString)(Display *);
    int (*XLookupString)(XKeyEvent *, char *, int, KeySym *, XComposeStatus *);
    KeySym (*XkbKeycodeToKeysym)(Display *, KeyCode, unsigned, unsigned);
    Bool (*XQueryExtension)(Display *, const char *, int *, int *, int *);
    Bool (*XGetEventData)(Display *, XGenericEventCookie *);
    void (*XFreeEventData)(Display *, XGenericEventCookie *);
    Bool (*XQueryPointer)(Display *, Window, Window *, Window *, int *, int *,
                          int *, int *, unsigned *);

    /* libXi */
    int (*XISelectEvents)(Display *, Window, XIEventMask *, int);
    int (*XIQueryVersion)(Display *, int *, int *);

    /* libxkbcommon (optional — Latin-1 fallback without it) */
    uint32_t (*xkb_keysym_to_utf32)(uint32_t keysym);

    /* libEGL (visual selection only) */
    void *(*eglGetPlatformDisplay)(unsigned, void *, const intptr_t *);
    void *(*eglGetDisplay)(void *);
    int (*eglInitialize)(void *, int *, int *);
    int (*eglBindAPI)(unsigned);
    int (*eglChooseConfig)(void *, const int *, void **, int, int *);
    int (*eglGetConfigAttrib)(void *, void *, int, int *);
} fn;

static void *load_first(const char *const *names) {
    for (int i = 0; names[i] != NULL; i++) {
        void *h = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
        if (h != NULL) return h;
    }
    return NULL;
}

static int ensure_libs_loaded(void) {
    if (fn.initialized) return 1;

    const char *x11_libs[] = { "libX11.so.6", "libX11.so", NULL };
    const char *xi_libs[]  = { "libXi.so.6", "libXi.so", NULL };
    const char *xkb_libs[] = { "libxkbcommon.so.0", "libxkbcommon.so", NULL };
    const char *egl_libs[] = { "libEGL.so.1", "libEGL.so", NULL };

    void *libx11 = load_first(x11_libs);
    if (libx11 == NULL) { DBG("libX11 not found\n"); return 0; }
    void *libxi  = load_first(xi_libs);   /* optional: outside-click only */
    void *libxkb = load_first(xkb_libs);  /* optional: non-Latin-1 layouts */
    void *libegl = load_first(egl_libs);  /* optional: falls back to XGetVisualInfo */

#define X11_SYM(name) fn.name = (__typeof__(fn.name)) dlsym(libx11, #name)
    X11_SYM(XOpenDisplay);       X11_SYM(XCloseDisplay);
    X11_SYM(XCreateWindow);      X11_SYM(XDestroyWindow);
    X11_SYM(XMapRaised);         X11_SYM(XUnmapWindow);
    X11_SYM(XRaiseWindow);       X11_SYM(XMoveResizeWindow);
    X11_SYM(XFlush);             X11_SYM(XSync);
    X11_SYM(XSelectInput);       X11_SYM(XNextEvent);
    X11_SYM(XPending);           X11_SYM(XCreateColormap);
    X11_SYM(XFreeColormap);      X11_SYM(XGetVisualInfo);
    X11_SYM(XFree);              X11_SYM(XSetInputFocus);
    X11_SYM(XCreateFontCursor);  X11_SYM(XDefineCursor);
    X11_SYM(XFreeCursor);        X11_SYM(XStoreName);
    X11_SYM(XResourceManagerString);
    X11_SYM(XLookupString);      X11_SYM(XkbKeycodeToKeysym);
    X11_SYM(XQueryExtension);    X11_SYM(XGetEventData);
    X11_SYM(XFreeEventData);     X11_SYM(XQueryPointer);
#undef X11_SYM

    if (libxi != NULL) {
        fn.XISelectEvents = (__typeof__(fn.XISelectEvents)) dlsym(libxi, "XISelectEvents");
        fn.XIQueryVersion = (__typeof__(fn.XIQueryVersion)) dlsym(libxi, "XIQueryVersion");
    }
    if (libxkb != NULL) {
        fn.xkb_keysym_to_utf32 =
            (__typeof__(fn.xkb_keysym_to_utf32)) dlsym(libxkb, "xkb_keysym_to_utf32");
    }
    if (libegl != NULL) {
        fn.eglGetPlatformDisplay =
            (__typeof__(fn.eglGetPlatformDisplay)) dlsym(libegl, "eglGetPlatformDisplay");
        fn.eglGetDisplay    = (__typeof__(fn.eglGetDisplay))    dlsym(libegl, "eglGetDisplay");
        fn.eglInitialize    = (__typeof__(fn.eglInitialize))    dlsym(libegl, "eglInitialize");
        fn.eglBindAPI       = (__typeof__(fn.eglBindAPI))       dlsym(libegl, "eglBindAPI");
        fn.eglChooseConfig  = (__typeof__(fn.eglChooseConfig))  dlsym(libegl, "eglChooseConfig");
        fn.eglGetConfigAttrib =
            (__typeof__(fn.eglGetConfigAttrib)) dlsym(libegl, "eglGetConfigAttrib");
    }

    if (!fn.XOpenDisplay || !fn.XCloseDisplay || !fn.XCreateWindow ||
        !fn.XDestroyWindow || !fn.XMapRaised || !fn.XUnmapWindow ||
        !fn.XRaiseWindow || !fn.XMoveResizeWindow || !fn.XFlush ||
        !fn.XSync || !fn.XSelectInput || !fn.XNextEvent || !fn.XPending ||
        !fn.XCreateColormap || !fn.XFreeColormap || !fn.XGetVisualInfo ||
        !fn.XFree || !fn.XSetInputFocus || !fn.XCreateFontCursor ||
        !fn.XDefineCursor || !fn.XFreeCursor || !fn.XLookupString ||
        !fn.XkbKeycodeToKeysym || !fn.XQueryPointer) {
        DBG("missing libX11 symbols\n");
        return 0;
    }
    fn.initialized = 1;
    return 1;
}

/* ── Command connection (Tao main thread) ───────────────────────────────── */

static Display *g_cmd_dpy = NULL;
static int g_cmd_dpy_failed = 0;

static Display *ensure_cmd_display(void) {
    if (g_cmd_dpy != NULL) return g_cmd_dpy;
    if (g_cmd_dpy_failed) return NULL;
    if (!ensure_libs_loaded()) { g_cmd_dpy_failed = 1; return NULL; }
    g_cmd_dpy = fn.XOpenDisplay(NULL);
    if (g_cmd_dpy == NULL) {
        DBG("XOpenDisplay failed (no X server / XWayland?)\n");
        g_cmd_dpy_failed = 1;
    }
    return g_cmd_dpy;
}

/* ── JNI callback plumbing ──────────────────────────────────────────────── */

static JavaVM *g_jvm = NULL;

/* Cached once per interface, from the first registered instance (all
 * implementors are the same named classes — GraalVM metadata requirement). */
static jmethodID g_on_pointer_event = NULL; /* (IFFII)V  */
static jmethodID g_on_scroll        = NULL; /* (FFFF)V   */
static jmethodID g_on_key_event     = NULL; /* (IIII)V   */
static jmethodID g_on_outside_click = NULL; /* (II)V     */

static JNIEnv *attach_jvm_thread(void) {
    if (g_jvm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **) &env, NULL) != JNI_OK) {
            return NULL;
        }
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void cache_event_callback_ids(JNIEnv *env, jobject callback) {
    if (g_on_pointer_event != NULL) return;
    jclass cls = (*env)->GetObjectClass(env, callback);
    if (cls == NULL) return;
    g_on_pointer_event = (*env)->GetMethodID(env, cls, "onPointerEvent", "(IFFII)V");
    g_on_scroll        = (*env)->GetMethodID(env, cls, "onScroll", "(FFFF)V");
    g_on_key_event     = (*env)->GetMethodID(env, cls, "onKeyEvent", "(IIII)V");
    (*env)->DeleteLocalRef(env, cls);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void cache_outside_listener_id(JNIEnv *env, jobject listener) {
    if (g_on_outside_click != NULL) return;
    jclass cls = (*env)->GetObjectClass(env, listener);
    if (cls == NULL) return;
    g_on_outside_click = (*env)->GetMethodID(env, cls, "onOutsideClick", "(II)V");
    (*env)->DeleteLocalRef(env, cls);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ── Panel state ────────────────────────────────────────────────────────── */

typedef struct {
    Window   win;
    Colormap cmap;
    Cursor   cursor;         /* current XCreateFontCursor, or None        */

    /* Geometry mirror for the outside-click hit test (event thread reads,
     * command thread writes). */
    pthread_mutex_t lock;
    int x, y, w, h;
    int visible;
    int focusable;

    /* Event thread. */
    pthread_t evt_thread;
    int       evt_thread_started;
    int       quit_pipe[2];

    /* Java refs (guarded by [lock]; invoked from the event thread). */
    jobject event_cb;    /* EventCallback global ref, or NULL   */
    jobject outside_cb;  /* OutsideClickListener global ref     */
} Panel;

/* ── Keysym helpers ─────────────────────────────────────────────────────── */

/* Active-layout keysym → Unicode code point. libxkbcommon handles every
 * legacy keysym block (Hebrew, Cyrillic, Greek…); the fallback covers
 * Latin-1 and the direct-Unicode keysym range only. */
static int keysym_to_codepoint(KeySym ks) {
    if (fn.xkb_keysym_to_utf32 != NULL) {
        return (int) fn.xkb_keysym_to_utf32((uint32_t) ks);
    }
    if (ks >= 0x20 && ks <= 0xFF) return (int) ks;              /* Latin-1  */
    if ((ks & 0xFF000000UL) == 0x01000000UL) return (int) (ks & 0x00FFFFFF);
    return 0;
}

/* Scans XKB groups for a Latin keysym at shift level 0 so shortcuts land
 * on the right key under non-Latin layouts (Ctrl+C while typing Hebrew).
 * Falls back to the group-0 keysym. */
static KeySym vk_keysym_for(Display *dpy, KeyCode keycode) {
    for (unsigned group = 0; group < 4; group++) {
        KeySym ks = fn.XkbKeycodeToKeysym(dpy, keycode, group, 0);
        if (ks == NoSymbol) continue;
        if ((ks >= 'a' && ks <= 'z') || (ks >= 'A' && ks <= 'Z') ||
            (ks >= '0' && ks <= '9')) {
            return ks;
        }
    }
    KeySym base = fn.XkbKeycodeToKeysym(dpy, keycode, 0, 0);
    return base != NoSymbol ? base : 0;
}

static int wire_modifiers(unsigned state) {
    int mods = 0;
    if (state & ShiftMask)   mods |= WIRE_MOD_SHIFT;
    if (state & ControlMask) mods |= WIRE_MOD_CTRL;
    if (state & Mod1Mask)    mods |= WIRE_MOD_ALT;   /* Alt   */
    if (state & Mod4Mask)    mods |= WIRE_MOD_META;  /* Super */
    return mods;
}

static int wire_button(unsigned xbutton) {
    switch (xbutton) {
        case Button1: return WIRE_BUTTON_PRIMARY;
        case Button3: return WIRE_BUTTON_SECONDARY;
        default:      return WIRE_BUTTON_NONE;
    }
}

/* ── Event thread ───────────────────────────────────────────────────────── */

static void forward_pointer(JNIEnv *env, Panel *p, int type, float x, float y,
                            int button, int mods) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->event_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_pointer_event == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_pointer_event, (jint) type,
                           (jfloat) x, (jfloat) y, (jint) button, (jint) mods);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void forward_scroll(JNIEnv *env, Panel *p, float x, float y,
                           float dx, float dy) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->event_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_scroll == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_scroll, (jfloat) x, (jfloat) y,
                           (jfloat) dx, (jfloat) dy);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void forward_key(JNIEnv *env, Panel *p, int type, int vk, int codepoint,
                        int mods) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->event_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_key_event == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_key_event, (jint) type, (jint) vk,
                           (jint) codepoint, (jint) mods);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void forward_outside_click(JNIEnv *env, Panel *p, int button) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->outside_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_outside_click == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_outside_click, (jint) 1, (jint) button);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Raw XI2 ButtonPress: hit-test the pointer against the panel rect and
 * notify when the press landed outside while the panel is visible. */
static void handle_raw_press(JNIEnv *env, Display *dpy, Panel *p, int button) {
    pthread_mutex_lock(&p->lock);
    int visible = p->visible;
    int px = p->x, py = p->y, pw = p->w, ph = p->h;
    jobject cb = p->outside_cb;
    pthread_mutex_unlock(&p->lock);
    if (!visible || cb == NULL) return;

    Window root_ret, child_ret;
    int root_x = 0, root_y = 0, wx = 0, wy = 0;
    unsigned mask = 0;
    Window root = DefaultRootWindow(dpy);
    if (!fn.XQueryPointer(dpy, root, &root_ret, &child_ret, &root_x, &root_y,
                          &wx, &wy, &mask)) {
        return;
    }
    int inside = root_x >= px && root_x < px + pw &&
                 root_y >= py && root_y < py + ph;
    if (!inside) {
        int wire = button == 1 ? WIRE_BUTTON_PRIMARY
                 : button == 3 ? WIRE_BUTTON_SECONDARY
                 : 3; /* "other", matches the macOS monitor's encoding */
        forward_outside_click(env, p, wire);
    }
}

static void handle_key_event(JNIEnv *env, Display *dpy, Panel *p, XKeyEvent *ke,
                             int wire_type) {
    char buf[8];
    KeySym active_ks = NoSymbol;
    fn.XLookupString(ke, buf, sizeof(buf), &active_ks, NULL);
    int codepoint = active_ks != NoSymbol ? keysym_to_codepoint(active_ks) : 0;
    KeySym vk = vk_keysym_for(dpy, (KeyCode) ke->keycode);
    /* Non-printable actives (arrows, F-keys…) map to codepoint 0 upstream;
     * dispatchSyntheticKeyTyped filters control chars anyway. */
    forward_key(env, p, wire_type, (int) vk, codepoint, wire_modifiers(ke->state));
}

static void *event_thread_main(void *arg) {
    Panel *p = (Panel *) arg;

    Display *dpy = fn.XOpenDisplay(NULL);
    if (dpy == NULL) return NULL;

    /* Per-client event mask on the shared XID — the command connection
     * never selects anything, so ButtonPress ownership is uncontended. */
    fn.XSelectInput(dpy, p->win,
                    ButtonPressMask | ButtonReleaseMask | PointerMotionMask |
                    KeyPressMask | KeyReleaseMask | StructureNotifyMask);

    /* XI2 raw buttons on the root for the outside-click monitor. Selected
     * unconditionally (cheap); forwarding is gated on the Java listener. */
    int xi_opcode = -1;
    if (fn.XISelectEvents != NULL && fn.XIQueryVersion != NULL &&
        fn.XQueryExtension != NULL && fn.XGetEventData != NULL) {
        int ev_base = 0, err_base = 0;
        if (fn.XQueryExtension(dpy, "XInputExtension", &xi_opcode, &ev_base, &err_base)) {
            int maj = 2, min = 0;
            if (fn.XIQueryVersion(dpy, &maj, &min) == Success) {
                unsigned char mask_bits[XIMaskLen(XI_LASTEVENT)];
                memset(mask_bits, 0, sizeof(mask_bits));
                XISetMask(mask_bits, XI_RawButtonPress);
                XIEventMask evmask = {
                    .deviceid = XIAllMasterDevices,
                    .mask_len = sizeof(mask_bits),
                    .mask = mask_bits,
                };
                fn.XISelectEvents(dpy, DefaultRootWindow(dpy), &evmask, 1);
            } else {
                xi_opcode = -1;
            }
        } else {
            xi_opcode = -1;
        }
    }
    fn.XFlush(dpy);

    JNIEnv *env = attach_jvm_thread();

    struct pollfd fds[2] = {
        { .fd = ConnectionNumber(dpy), .events = POLLIN },
        { .fd = p->quit_pipe[0],       .events = POLLIN },
    };

    int running = 1;
    while (running) {
        /* Drain everything already buffered before blocking in poll —
         * Xlib reads whole batches off the socket, so poll() alone would
         * sleep on events sitting in the client-side queue. */
        while (fn.XPending(dpy) > 0) {
            XEvent ev;
            fn.XNextEvent(dpy, &ev);
            if (env == NULL) continue;

            switch (ev.type) {
                case ButtonPress: {
                    XButtonEvent *be = &ev.xbutton;
                    if (be->button >= Button4 && be->button <= 7) {
                        /* 4/5 = vertical wheel, 6/7 = horizontal. One line
                         * per click; sign matches the Compose convention
                         * (positive Y scrolls the content down). */
                        float dx = be->button == 6 ? -1.0f : be->button == 7 ? 1.0f : 0.0f;
                        float dy = be->button == Button4 ? -1.0f : be->button == Button5 ? 1.0f : 0.0f;
                        forward_scroll(env, p, (float) be->x, (float) be->y, dx, dy);
                        break;
                    }
                    pthread_mutex_lock(&p->lock);
                    int focusable = p->focusable;
                    pthread_mutex_unlock(&p->lock);
                    if (focusable) {
                        /* takeKeyboardFocus() equivalent: OR windows never
                         * receive focus from the WM, grab it explicitly. */
                        fn.XSetInputFocus(dpy, p->win, RevertToParent, be->time);
                    }
                    forward_pointer(env, p, WIRE_PTR_DOWN, (float) be->x, (float) be->y,
                                    wire_button(be->button), wire_modifiers(be->state));
                    break;
                }
                case ButtonRelease: {
                    XButtonEvent *be = &ev.xbutton;
                    if (be->button >= Button4 && be->button <= 7) break;
                    forward_pointer(env, p, WIRE_PTR_UP, (float) be->x, (float) be->y,
                                    wire_button(be->button), wire_modifiers(be->state));
                    break;
                }
                case MotionNotify: {
                    XMotionEvent *me = &ev.xmotion;
                    forward_pointer(env, p, WIRE_PTR_MOVE, (float) me->x, (float) me->y,
                                    WIRE_BUTTON_NONE, wire_modifiers(me->state));
                    break;
                }
                case KeyPress:
                    handle_key_event(env, dpy, p, &ev.xkey, WIRE_KEY_DOWN);
                    break;
                case KeyRelease:
                    handle_key_event(env, dpy, p, &ev.xkey, WIRE_KEY_UP);
                    break;
                case DestroyNotify:
                    if (ev.xdestroywindow.window == p->win) running = 0;
                    break;
                case GenericEvent: {
                    XGenericEventCookie *cookie = &ev.xcookie;
                    if (xi_opcode >= 0 && cookie->extension == xi_opcode &&
                        cookie->evtype == XI_RawButtonPress &&
                        fn.XGetEventData(dpy, cookie)) {
                        XIRawEvent *raw = (XIRawEvent *) cookie->data;
                        handle_raw_press(env, dpy, p, raw->detail);
                        fn.XFreeEventData(dpy, cookie);
                    }
                    break;
                }
                default:
                    break;
            }
        }
        if (!running) break;

        if (poll(fds, 2, -1) < 0) continue;
        if (fds[1].revents & POLLIN) break; /* quit pipe */
    }

    fn.XCloseDisplay(dpy);
    return NULL;
}

/* ── JNI exports ────────────────────────────────────────────────────────── */

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeIsAvailable(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    return ensure_cmd_display() != NULL ? JNI_TRUE : JNI_FALSE;
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeDisplayPtr(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    return (jlong) (uintptr_t) ensure_cmd_display();
}

/* Xft.dpi from the root resource database. X clients live in the X
 * coordinate space (logical under XWayland), so GDK's Wayland scale must
 * NOT be used for this panel — see TaoStandalonePopupHostLinux. */
EXPORT jfloat JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeScale(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    Display *dpy = ensure_cmd_display();
    if (dpy == NULL || fn.XResourceManagerString == NULL) return 1.0f;
    const char *rm = fn.XResourceManagerString(dpy);
    if (rm == NULL) return 1.0f;
    const char *entry = strstr(rm, "Xft.dpi:");
    if (entry == NULL) return 1.0f;
    double dpi = atof(entry + strlen("Xft.dpi:"));
    if (dpi < 48.0 || dpi > 480.0) return 1.0f;
    return (float) (dpi / 96.0);
}

/* Picks the X visual for the panel from EGL's alpha-capable desktop-GL
 * configs so the later `nativeAttachX11` matches the exact same config.
 * Falls back to any 32-bit TrueColor visual, then to CopyFromParent. */
static Visual *choose_argb_visual(Display *dpy, int *out_depth) {
    *out_depth = 0;

    if (fn.eglInitialize != NULL && fn.eglChooseConfig != NULL &&
        fn.eglGetConfigAttrib != NULL && fn.eglBindAPI != NULL) {
        void *edpy = NULL;
        if (fn.eglGetPlatformDisplay != NULL) {
            edpy = fn.eglGetPlatformDisplay(0x31D5 /* EGL_PLATFORM_X11_KHR */, dpy, NULL);
        }
        if (edpy == NULL && fn.eglGetDisplay != NULL) {
            edpy = fn.eglGetDisplay(dpy);
        }
        int maj = 0, min = 0;
        if (edpy != NULL && fn.eglInitialize(edpy, &maj, &min) &&
            fn.eglBindAPI(0x30A2 /* EGL_OPENGL_API */)) {
            const int attrs[] = {
                0x3033, 0x0004,   /* EGL_SURFACE_TYPE,    EGL_WINDOW_BIT */
                0x3040, 0x0008,   /* EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT */
                0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, /* RGBA 8888 */
                0x3038            /* EGL_NONE */
            };
            void *cfgs[64];
            int ncfg = 0;
            if (fn.eglChooseConfig(edpy, attrs, cfgs, 64, &ncfg) && ncfg > 0) {
                for (int i = 0; i < ncfg; i++) {
                    int vid = 0;
                    fn.eglGetConfigAttrib(edpy, cfgs[i], 0x302E /* NATIVE_VISUAL_ID */, &vid);
                    if (vid == 0) continue;
                    XVisualInfo tmpl;
                    memset(&tmpl, 0, sizeof(tmpl));
                    tmpl.visualid = (VisualID) vid;
                    int n = 0;
                    XVisualInfo *vi = fn.XGetVisualInfo(dpy, VisualIDMask, &tmpl, &n);
                    if (vi != NULL && n > 0 && vi[0].depth == 32) {
                        Visual *v = vi[0].visual;
                        *out_depth = vi[0].depth;
                        fn.XFree(vi);
                        DBG("visual from EGL config: 0x%lx depth 32\n", (unsigned long) vid);
                        return v;
                    }
                    if (vi != NULL) fn.XFree(vi);
                }
            }
        }
    }

    XVisualInfo tmpl;
    memset(&tmpl, 0, sizeof(tmpl));
    tmpl.depth = 32;
    tmpl.class = TrueColor;
    int n = 0;
    XVisualInfo *vi = fn.XGetVisualInfo(dpy, VisualDepthMask | VisualClassMask, &tmpl, &n);
    if (vi != NULL && n > 0) {
        Visual *v = vi[0].visual;
        *out_depth = vi[0].depth;
        fn.XFree(vi);
        DBG("visual fallback: first ARGB32\n");
        return v;
    }
    if (vi != NULL) fn.XFree(vi);
    return NULL;
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeCreatePanel(
    JNIEnv *env, jclass clazz, jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void) clazz;
    Display *dpy = ensure_cmd_display();
    if (dpy == NULL) return 0;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);

    int depth = 0;
    Visual *visual = choose_argb_visual(dpy, &depth);
    if (visual == NULL) {
        DBG("no ARGB visual — compositor missing?\n");
        return 0;
    }

    Window root = DefaultRootWindow(dpy);
    Colormap cmap = fn.XCreateColormap(dpy, root, visual, AllocNone);

    XSetWindowAttributes swa;
    memset(&swa, 0, sizeof(swa));
    swa.colormap = cmap;
    swa.border_pixel = 0;
    swa.background_pixel = 0;
    swa.override_redirect = True;
    /* No event_mask here: input is selected per-client by the event
     * thread's own connection. */
    unsigned w = widthPx > 0 ? (unsigned) widthPx : 1;
    unsigned h = heightPx > 0 ? (unsigned) heightPx : 1;
    Window win = fn.XCreateWindow(
        dpy, root, xPx, yPx, w, h, 0, depth, InputOutput, visual,
        CWColormap | CWBorderPixel | CWBackPixel | CWOverrideRedirect, &swa);
    if (win == 0) {
        fn.XFreeColormap(dpy, cmap);
        return 0;
    }
    if (fn.XStoreName != NULL) fn.XStoreName(dpy, win, "NucleusStandalonePopup");
    fn.XSync(dpy, False);

    Panel *p = (Panel *) calloc(1, sizeof(Panel));
    if (p == NULL) {
        fn.XDestroyWindow(dpy, win);
        fn.XFreeColormap(dpy, cmap);
        return 0;
    }
    p->win = win;
    p->cmap = cmap;
    p->x = xPx;
    p->y = yPx;
    p->w = (int) w;
    p->h = (int) h;
    pthread_mutex_init(&p->lock, NULL);
    if (pipe(p->quit_pipe) != 0) {
        p->quit_pipe[0] = p->quit_pipe[1] = -1;
    }

    if (pthread_create(&p->evt_thread, NULL, event_thread_main, p) == 0) {
        p->evt_thread_started = 1;
    } else {
        DBG("event thread creation failed\n");
    }

    DBG("panel created: win=0x%lx depth=%d\n", win, depth);
    return (jlong) (uintptr_t) p;
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeWindowXid(
    JNIEnv *env, jclass clazz, jlong panel)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    return p != NULL ? (jlong) p->win : 0;
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeSetFrameOnScreen(
    JNIEnv *env, jclass clazz, jlong panel, jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL || dpy == NULL) return;
    unsigned w = widthPx > 0 ? (unsigned) widthPx : 1;
    unsigned h = heightPx > 0 ? (unsigned) heightPx : 1;
    fn.XMoveResizeWindow(dpy, p->win, xPx, yPx, w, h);
    fn.XFlush(dpy);
    pthread_mutex_lock(&p->lock);
    p->x = xPx;
    p->y = yPx;
    p->w = (int) w;
    p->h = (int) h;
    pthread_mutex_unlock(&p->lock);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeSetPanelVisible(
    JNIEnv *env, jclass clazz, jlong panel, jboolean visible)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL || dpy == NULL) return;
    if (visible) {
        fn.XMapRaised(dpy, p->win);
    } else {
        fn.XUnmapWindow(dpy, p->win);
    }
    fn.XFlush(dpy);
    pthread_mutex_lock(&p->lock);
    p->visible = visible ? 1 : 0;
    pthread_mutex_unlock(&p->lock);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeSetFocusable(
    JNIEnv *env, jclass clazz, jlong panel, jboolean focusable)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    pthread_mutex_lock(&p->lock);
    p->focusable = focusable ? 1 : 0;
    pthread_mutex_unlock(&p->lock);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeSetPanelCursor(
    JNIEnv *env, jclass clazz, jlong panel, jint iconCode)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL || dpy == NULL) return;
    unsigned shape;
    switch (iconCode) {
        case ICON_TEXT:        shape = XC_xterm;              break;
        case ICON_HAND:        shape = XC_hand2;              break;
        case ICON_CROSSHAIR:   shape = XC_crosshair;          break;
        case ICON_WAIT:        shape = XC_watch;              break;
        case ICON_MOVE:        shape = XC_fleur;              break;
        case ICON_NOT_ALLOWED: shape = XC_X_cursor;           break;
        case ICON_HELP:        shape = XC_question_arrow;     break;
        case ICON_PROGRESS:    shape = XC_watch;              break;
        case ICON_EW_RESIZE:   shape = XC_sb_h_double_arrow;  break;
        case ICON_NS_RESIZE:   shape = XC_sb_v_double_arrow;  break;
        case ICON_NESW_RESIZE: shape = XC_bottom_left_corner; break;
        case ICON_NWSE_RESIZE: shape = XC_bottom_right_corner; break;
        default:               shape = XC_left_ptr;           break;
    }
    Cursor cursor = fn.XCreateFontCursor(dpy, shape);
    fn.XDefineCursor(dpy, p->win, cursor);
    fn.XFlush(dpy);
    if (p->cursor != None) fn.XFreeCursor(dpy, p->cursor);
    p->cursor = cursor;
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeSetEventCallback(
    JNIEnv *env, jclass clazz, jlong panel, jobject callback)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    jobject global = NULL;
    if (callback != NULL) {
        cache_event_callback_ids(env, callback);
        global = (*env)->NewGlobalRef(env, callback);
    }
    pthread_mutex_lock(&p->lock);
    jobject prev = p->event_cb;
    p->event_cb = global;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeInstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel, jobject listener)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL || listener == NULL) return;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    cache_outside_listener_id(env, listener);
    jobject global = (*env)->NewGlobalRef(env, listener);
    pthread_mutex_lock(&p->lock);
    jobject prev = p->outside_cb;
    p->outside_cb = global;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeUninstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    pthread_mutex_lock(&p->lock);
    jobject prev = p->outside_cb;
    p->outside_cb = NULL;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_PopupNativeBridgeLinux_nativeRelease(
    JNIEnv *env, jclass clazz, jlong panel)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL) return;

    if (p->evt_thread_started) {
        if (p->quit_pipe[1] >= 0) {
            char one = 1;
            ssize_t ignored = write(p->quit_pipe[1], &one, 1);
            (void) ignored;
        }
        pthread_join(p->evt_thread, NULL);
    }
    if (p->quit_pipe[0] >= 0) close(p->quit_pipe[0]);
    if (p->quit_pipe[1] >= 0) close(p->quit_pipe[1]);

    pthread_mutex_lock(&p->lock);
    jobject event_cb = p->event_cb;
    jobject outside_cb = p->outside_cb;
    p->event_cb = NULL;
    p->outside_cb = NULL;
    pthread_mutex_unlock(&p->lock);
    if (event_cb != NULL) (*env)->DeleteGlobalRef(env, event_cb);
    if (outside_cb != NULL) (*env)->DeleteGlobalRef(env, outside_cb);

    if (dpy != NULL) {
        if (p->cursor != None) fn.XFreeCursor(dpy, p->cursor);
        fn.XDestroyWindow(dpy, p->win);
        fn.XFreeColormap(dpy, p->cmap);
        fn.XFlush(dpy);
    }
    pthread_mutex_destroy(&p->lock);
    free(p);
}
