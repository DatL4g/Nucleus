/**
 * JNI bridge: GTK client-side-decoration drop shadow, rendered from the
 * live GTK 3 theme for the Tao backend's undecorated windows.
 *
 * Replicates the mechanism GTK uses for its own CSD windows
 * (gtk/gtkwindow.c `get_shadow_width` + gtkcssshadowvalue.c painting):
 *
 *   1. The theme's `decoration` CSS node (`window.csd > decoration`) is
 *      rendered off-screen with gtk_render_background/_frame into a cairo
 *      ARGB32 surface, for both the NORMAL and BACKDROP states — so the
 *      shadow matches whatever theme the user runs (Adwaita, Yaru, ...)
 *      including the `:backdrop` fade GTK animates on focus loss.
 *   2. The invisible margin around the visible frame is declared to the
 *      window manager through gdk_window_set_shadow_width(), which is the
 *      exact call GTK makes internally: `_GTK_FRAME_EXTENTS` on X11,
 *      xdg_surface.set_window_geometry margins on Wayland. Tiling,
 *      snapping and maximization then use the visible frame only.
 *   3. The input shape is shrunk to the visible frame plus a 12 px resize
 *      ring (GTK4's RESIZE_HANDLE_SIZE), so clicks in the outer shadow
 *      fall through to the window below.
 *
 * The Kotlin side (TaoWindowShadowLinux) uploads the rendered pixels as
 * Skia images, nine-slices them into the window margins each frame and
 * cross-fades normal <-> backdrop over 200 ms on focus change, mirroring
 * Adwaita's `transition: 200ms ease-out`.
 *
 * Compiled into libnucleus_tao_linux_widget.so (see build.sh). Same
 * dlopen-only linkage policy as nucleus_tao_linux_widget.c: no link-time
 * GTK dependency.
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose dispatcher thread).
 */

#include <jni.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

typedef int      gint;
typedef int      gboolean;
typedef char     gchar;
typedef unsigned long gulong;
typedef void     GtkWidget;
typedef void     GtkWindow;
typedef void     GdkWindow;
typedef void     GdkScreen;
typedef void     GdkVisual;
typedef void     GtkWidgetPath;
typedef void     GtkStyleContext;
typedef void     GtkCssProvider;
typedef void     GtkSettings;
typedef void     GError;
typedef void    *GdkAtom;
typedef gulong   GType;
typedef unsigned int GtkStateFlags;
typedef void     cairo_surface_t;
typedef void     cairo_t;
typedef void     cairo_region_t;

typedef struct {
    int x;
    int y;
    int width;
    int height;
} cairo_rectangle_int_t;

/* GTypeInstance fundamental constant: G_TYPE_NONE == (1 << 2). Appending it
 * to a widget path creates the anonymous element GTK names via
 * set_object_name — how GTK models the `decoration` CSS node. */
#define G_TYPE_NONE_CONST ((GType) (1 << 2))
#define GTK_STATE_FLAG_BACKDROP (1 << 6)
#define GTK_STYLE_PROVIDER_PRIORITY_USER 800
#define CAIRO_FORMAT_ARGB32 0

typedef GdkWindow       *(*PFN_gtk_widget_get_window)(GtkWidget *w);
typedef GType            (*PFN_gtk_window_get_type)(void);
typedef GtkWidgetPath   *(*PFN_gtk_widget_path_new)(void);
typedef gint             (*PFN_gtk_widget_path_append_type)(GtkWidgetPath *p, GType type);
typedef void             (*PFN_gtk_widget_path_iter_set_object_name)(GtkWidgetPath *p, gint pos, const char *name);
typedef void             (*PFN_gtk_widget_path_iter_add_class)(GtkWidgetPath *p, gint pos, const char *name);
typedef void             (*PFN_gtk_widget_path_iter_set_state)(GtkWidgetPath *p, gint pos, GtkStateFlags state);
typedef void             (*PFN_gtk_widget_path_free)(GtkWidgetPath *p);
typedef GtkStyleContext *(*PFN_gtk_style_context_new)(void);
typedef void             (*PFN_gtk_style_context_set_screen)(GtkStyleContext *c, GdkScreen *s);
typedef void             (*PFN_gtk_style_context_set_path)(GtkStyleContext *c, GtkWidgetPath *p);
typedef void             (*PFN_gtk_style_context_set_state)(GtkStyleContext *c, GtkStateFlags state);
typedef void             (*PFN_gtk_style_context_add_provider)(GtkStyleContext *c, void *provider, unsigned int priority);
typedef GtkCssProvider  *(*PFN_gtk_css_provider_new)(void);
typedef gboolean         (*PFN_gtk_css_provider_load_from_data)(GtkCssProvider *p, const gchar *data, long length, GError **error);
typedef void             (*PFN_gtk_render_background)(GtkStyleContext *c, cairo_t *cr, double x, double y, double w, double h);
typedef void             (*PFN_gtk_render_frame)(GtkStyleContext *c, cairo_t *cr, double x, double y, double w, double h);
typedef GtkSettings     *(*PFN_gtk_settings_get_default)(void);

typedef GdkScreen       *(*PFN_gdk_screen_get_default)(void);
typedef gboolean         (*PFN_gdk_screen_is_composited)(GdkScreen *s);
typedef GdkVisual       *(*PFN_gdk_screen_get_rgba_visual)(GdkScreen *s);
typedef void             (*PFN_gdk_window_set_shadow_width)(GdkWindow *w, int left, int right, int top, int bottom);
typedef void             (*PFN_gdk_window_input_shape_combine_region)(GdkWindow *w, const cairo_region_t *region, gint ox, gint oy);
typedef GdkAtom          (*PFN_gdk_atom_intern_static_string)(const gchar *name);
typedef gboolean         (*PFN_gdk_x11_screen_supports_net_wm_hint)(GdkScreen *s, GdkAtom property);

typedef cairo_surface_t *(*PFN_cairo_image_surface_create)(int format, int width, int height);
typedef void             (*PFN_cairo_surface_set_device_scale)(cairo_surface_t *s, double sx, double sy);
typedef cairo_t         *(*PFN_cairo_create)(cairo_surface_t *s);
typedef void             (*PFN_cairo_destroy)(cairo_t *cr);
typedef void             (*PFN_cairo_surface_flush)(cairo_surface_t *s);
typedef unsigned char   *(*PFN_cairo_image_surface_get_data)(cairo_surface_t *s);
typedef int              (*PFN_cairo_image_surface_get_stride)(cairo_surface_t *s);
typedef void             (*PFN_cairo_surface_destroy)(cairo_surface_t *s);
typedef cairo_region_t  *(*PFN_cairo_region_create_rectangle)(const cairo_rectangle_int_t *rect);
typedef void             (*PFN_cairo_region_destroy)(cairo_region_t *r);

typedef unsigned int     (*PFN_gdk_window_get_state)(GdkWindow *w);

typedef void             (*PFN_g_object_get)(void *obj, const char *first_prop, ...);
typedef void             (*PFN_g_object_unref)(void *obj);
typedef void             (*PFN_g_free)(void *mem);
typedef void            *(*PFN_g_object_get_data)(void *obj, const char *key);
typedef void             (*PFN_g_object_set_data_full)(
    void *obj, const char *key, void *data, void (*destroy)(void *));
typedef gulong           (*PFN_g_signal_connect_data)(
    void *instance, const char *signal, void (*handler)(void), void *data,
    void (*destroy)(void *, void *), int connect_flags);

static struct {
    int initialized;
    PFN_gtk_widget_get_window                 gtk_widget_get_window;
    PFN_gtk_window_get_type                   gtk_window_get_type;
    PFN_gtk_widget_path_new                   gtk_widget_path_new;
    PFN_gtk_widget_path_append_type           gtk_widget_path_append_type;
    PFN_gtk_widget_path_iter_set_object_name  gtk_widget_path_iter_set_object_name;
    PFN_gtk_widget_path_iter_add_class        gtk_widget_path_iter_add_class;
    PFN_gtk_widget_path_iter_set_state        gtk_widget_path_iter_set_state;
    PFN_gtk_widget_path_free                  gtk_widget_path_free;
    PFN_gtk_style_context_new                 gtk_style_context_new;
    PFN_gtk_style_context_set_screen          gtk_style_context_set_screen;
    PFN_gtk_style_context_set_path            gtk_style_context_set_path;
    PFN_gtk_style_context_set_state           gtk_style_context_set_state;
    PFN_gtk_style_context_add_provider        gtk_style_context_add_provider;
    PFN_gtk_css_provider_new                  gtk_css_provider_new;
    PFN_gtk_css_provider_load_from_data       gtk_css_provider_load_from_data;
    PFN_gtk_render_background                 gtk_render_background;
    PFN_gtk_render_frame                      gtk_render_frame;
    PFN_gtk_settings_get_default              gtk_settings_get_default;
    PFN_gdk_screen_get_default                gdk_screen_get_default;
    PFN_gdk_screen_is_composited              gdk_screen_is_composited;
    PFN_gdk_screen_get_rgba_visual            gdk_screen_get_rgba_visual;
    PFN_gdk_window_set_shadow_width           gdk_window_set_shadow_width;
    PFN_gdk_window_input_shape_combine_region gdk_window_input_shape_combine_region;
    PFN_gdk_atom_intern_static_string         gdk_atom_intern_static_string;
    PFN_gdk_x11_screen_supports_net_wm_hint   gdk_x11_screen_supports_net_wm_hint; /* optional */
    PFN_cairo_image_surface_create            cairo_image_surface_create;
    PFN_cairo_surface_set_device_scale        cairo_surface_set_device_scale;
    PFN_cairo_create                          cairo_create;
    PFN_cairo_destroy                         cairo_destroy;
    PFN_cairo_surface_flush                   cairo_surface_flush;
    PFN_cairo_image_surface_get_data          cairo_image_surface_get_data;
    PFN_cairo_image_surface_get_stride        cairo_image_surface_get_stride;
    PFN_cairo_surface_destroy                 cairo_surface_destroy;
    PFN_cairo_region_create_rectangle         cairo_region_create_rectangle;
    PFN_cairo_region_destroy                  cairo_region_destroy;
    PFN_gdk_window_get_state                  gdk_window_get_state;
    PFN_g_object_get                          g_object_get;
    PFN_g_object_unref                        g_object_unref;
    PFN_g_free                                g_free;
    PFN_g_object_get_data                     g_object_get_data;
    PFN_g_object_set_data_full                g_object_set_data_full;
    PFN_g_signal_connect_data                 g_signal_connect_data;
} s;

static void *shadow_load_first(const char *const *names) {
    for (int i = 0; names[i] != NULL; i++) {
        /* RTLD_LOCAL: every symbol is fetched via dlsym() on the returned
         * handle, so GTK's dependency closure must NOT enter the global
         * scope — on NixOS it includes libsqlite3 (via tinysparql), which
         * would interpose the sqlite bundled in androidx/Room's JNI lib
         * and crash the JVM (issue #366). */
        void *h = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (h != NULL) return h;
    }
    return NULL;
}

static int shadow_ensure_loaded(void) {
    if (s.initialized) return 1;
    const char *gtk_libs[]   = { "libgtk-3.so.0", "libgtk-3.so", NULL };
    const char *gdk_libs[]   = { "libgdk-3.so.0", "libgdk-3.so", NULL };
    const char *gobj_libs[]  = { "libgobject-2.0.so.0", "libgobject-2.0.so", NULL };
    const char *glib_libs[]  = { "libglib-2.0.so.0", "libglib-2.0.so", NULL };
    const char *cairo_libs[] = { "libcairo.so.2", "libcairo.so", NULL };
    void *libgtk   = shadow_load_first(gtk_libs);
    void *libgdk   = shadow_load_first(gdk_libs);
    void *libgobj  = shadow_load_first(gobj_libs);
    void *libglib  = shadow_load_first(glib_libs);
    void *libcairo = shadow_load_first(cairo_libs);
    if (!libgtk || !libgdk || !libgobj || !libglib || !libcairo) return 0;

    s.gtk_widget_get_window                = (PFN_gtk_widget_get_window)                dlsym(libgtk, "gtk_widget_get_window");
    s.gtk_window_get_type                  = (PFN_gtk_window_get_type)                  dlsym(libgtk, "gtk_window_get_type");
    s.gtk_widget_path_new                  = (PFN_gtk_widget_path_new)                  dlsym(libgtk, "gtk_widget_path_new");
    s.gtk_widget_path_append_type          = (PFN_gtk_widget_path_append_type)          dlsym(libgtk, "gtk_widget_path_append_type");
    s.gtk_widget_path_iter_set_object_name = (PFN_gtk_widget_path_iter_set_object_name) dlsym(libgtk, "gtk_widget_path_iter_set_object_name");
    s.gtk_widget_path_iter_add_class       = (PFN_gtk_widget_path_iter_add_class)       dlsym(libgtk, "gtk_widget_path_iter_add_class");
    s.gtk_widget_path_iter_set_state       = (PFN_gtk_widget_path_iter_set_state)       dlsym(libgtk, "gtk_widget_path_iter_set_state");
    s.gtk_widget_path_free                 = (PFN_gtk_widget_path_free)                 dlsym(libgtk, "gtk_widget_path_free");
    s.gtk_style_context_new                = (PFN_gtk_style_context_new)                dlsym(libgtk, "gtk_style_context_new");
    s.gtk_style_context_set_screen         = (PFN_gtk_style_context_set_screen)         dlsym(libgtk, "gtk_style_context_set_screen");
    s.gtk_style_context_set_path           = (PFN_gtk_style_context_set_path)           dlsym(libgtk, "gtk_style_context_set_path");
    s.gtk_style_context_set_state          = (PFN_gtk_style_context_set_state)          dlsym(libgtk, "gtk_style_context_set_state");
    s.gtk_style_context_add_provider       = (PFN_gtk_style_context_add_provider)       dlsym(libgtk, "gtk_style_context_add_provider");
    s.gtk_css_provider_new                 = (PFN_gtk_css_provider_new)                 dlsym(libgtk, "gtk_css_provider_new");
    s.gtk_css_provider_load_from_data      = (PFN_gtk_css_provider_load_from_data)      dlsym(libgtk, "gtk_css_provider_load_from_data");
    s.gtk_render_background                = (PFN_gtk_render_background)                dlsym(libgtk, "gtk_render_background");
    s.gtk_render_frame                     = (PFN_gtk_render_frame)                     dlsym(libgtk, "gtk_render_frame");
    s.gtk_settings_get_default             = (PFN_gtk_settings_get_default)             dlsym(libgtk, "gtk_settings_get_default");

    s.gdk_screen_get_default                = (PFN_gdk_screen_get_default)                dlsym(libgdk, "gdk_screen_get_default");
    s.gdk_screen_is_composited              = (PFN_gdk_screen_is_composited)              dlsym(libgdk, "gdk_screen_is_composited");
    s.gdk_screen_get_rgba_visual            = (PFN_gdk_screen_get_rgba_visual)            dlsym(libgdk, "gdk_screen_get_rgba_visual");
    s.gdk_window_set_shadow_width           = (PFN_gdk_window_set_shadow_width)           dlsym(libgdk, "gdk_window_set_shadow_width");
    s.gdk_window_input_shape_combine_region = (PFN_gdk_window_input_shape_combine_region) dlsym(libgdk, "gdk_window_input_shape_combine_region");
    s.gdk_atom_intern_static_string         = (PFN_gdk_atom_intern_static_string)         dlsym(libgdk, "gdk_atom_intern_static_string");
    /* X11-only entry point; absent on X11-less GDK builds. Checked at use. */
    s.gdk_x11_screen_supports_net_wm_hint   = (PFN_gdk_x11_screen_supports_net_wm_hint)   dlsym(libgdk, "gdk_x11_screen_supports_net_wm_hint");

    s.cairo_image_surface_create     = (PFN_cairo_image_surface_create)     dlsym(libcairo, "cairo_image_surface_create");
    s.cairo_surface_set_device_scale = (PFN_cairo_surface_set_device_scale) dlsym(libcairo, "cairo_surface_set_device_scale");
    s.cairo_create                   = (PFN_cairo_create)                   dlsym(libcairo, "cairo_create");
    s.cairo_destroy                  = (PFN_cairo_destroy)                  dlsym(libcairo, "cairo_destroy");
    s.cairo_surface_flush            = (PFN_cairo_surface_flush)            dlsym(libcairo, "cairo_surface_flush");
    s.cairo_image_surface_get_data   = (PFN_cairo_image_surface_get_data)   dlsym(libcairo, "cairo_image_surface_get_data");
    s.cairo_image_surface_get_stride = (PFN_cairo_image_surface_get_stride) dlsym(libcairo, "cairo_image_surface_get_stride");
    s.cairo_surface_destroy          = (PFN_cairo_surface_destroy)          dlsym(libcairo, "cairo_surface_destroy");
    s.cairo_region_create_rectangle  = (PFN_cairo_region_create_rectangle)  dlsym(libcairo, "cairo_region_create_rectangle");
    s.cairo_region_destroy           = (PFN_cairo_region_destroy)           dlsym(libcairo, "cairo_region_destroy");

    s.gdk_window_get_state = (PFN_gdk_window_get_state) dlsym(libgdk, "gdk_window_get_state");

    s.g_object_get          = (PFN_g_object_get)          dlsym(libgobj, "g_object_get");
    s.g_object_unref        = (PFN_g_object_unref)        dlsym(libgobj, "g_object_unref");
    s.g_free                = (PFN_g_free)                dlsym(libglib, "g_free");
    s.g_object_get_data     = (PFN_g_object_get_data)     dlsym(libgobj, "g_object_get_data");
    s.g_object_set_data_full = (PFN_g_object_set_data_full) dlsym(libgobj, "g_object_set_data_full");
    s.g_signal_connect_data = (PFN_g_signal_connect_data) dlsym(libgobj, "g_signal_connect_data");

    if (!s.gtk_widget_get_window || !s.gtk_window_get_type ||
        !s.gtk_widget_path_new || !s.gtk_widget_path_append_type ||
        !s.gtk_widget_path_iter_set_object_name ||
        !s.gtk_widget_path_iter_add_class || !s.gtk_widget_path_iter_set_state ||
        !s.gtk_widget_path_free || !s.gtk_style_context_new ||
        !s.gtk_style_context_set_screen || !s.gtk_style_context_set_path ||
        !s.gtk_style_context_set_state || !s.gtk_style_context_add_provider ||
        !s.gtk_css_provider_new || !s.gtk_css_provider_load_from_data ||
        !s.gtk_render_background || !s.gtk_render_frame ||
        !s.gtk_settings_get_default || !s.gdk_screen_get_default ||
        !s.gdk_screen_is_composited || !s.gdk_screen_get_rgba_visual ||
        !s.gdk_window_set_shadow_width ||
        !s.gdk_window_input_shape_combine_region ||
        !s.gdk_atom_intern_static_string ||
        !s.cairo_image_surface_create || !s.cairo_surface_set_device_scale ||
        !s.cairo_create || !s.cairo_destroy || !s.cairo_surface_flush ||
        !s.cairo_image_surface_get_data || !s.cairo_image_surface_get_stride ||
        !s.cairo_surface_destroy || !s.cairo_region_create_rectangle ||
        !s.cairo_region_destroy || !s.gdk_window_get_state ||
        !s.g_object_get || !s.g_object_unref || !s.g_free ||
        !s.g_object_get_data || !s.g_object_set_data_full ||
        !s.g_signal_connect_data) {
        return 0;
    }
    s.initialized = 1;
    return 1;
}

/**
 * Builds the style context GTK itself would use for a CSD window's
 * decoration node: path `window.background.csd > decoration`, with
 * GTK_STATE_FLAG_BACKDROP applied on both nodes for the unfocused variant
 * and the `tiled` class on the window node for the snapped variant (themes
 * select `.tiled decoration { ... }`).
 *
 * The caller owns the returned context (g_object_unref).
 */
static GtkStyleContext *shadow_make_decoration_context(int backdrop, int tiled) {
    GtkWidgetPath *path = s.gtk_widget_path_new();
    gint pos = s.gtk_widget_path_append_type(path, s.gtk_window_get_type());
    s.gtk_widget_path_iter_set_object_name(path, pos, "window");
    s.gtk_widget_path_iter_add_class(path, pos, "background");
    s.gtk_widget_path_iter_add_class(path, pos, "csd");
    if (tiled) s.gtk_widget_path_iter_add_class(path, pos, "tiled");
    if (backdrop) s.gtk_widget_path_iter_set_state(path, pos, GTK_STATE_FLAG_BACKDROP);

    pos = s.gtk_widget_path_append_type(path, G_TYPE_NONE_CONST);
    s.gtk_widget_path_iter_set_object_name(path, pos, "decoration");
    if (backdrop) s.gtk_widget_path_iter_set_state(path, pos, GTK_STATE_FLAG_BACKDROP);

    GtkStyleContext *ctx = s.gtk_style_context_new();
    s.gtk_style_context_set_screen(ctx, s.gdk_screen_get_default());
    s.gtk_style_context_set_path(ctx, path);
    s.gtk_style_context_set_state(ctx, backdrop ? GTK_STATE_FLAG_BACKDROP : 0);
    s.gtk_widget_path_free(path);
    return ctx;
}

/*
 * Class:     dev_nucleusframework_window_tao_NativeTaoLinuxShadowBridge
 * Method:    nativeShadowSupported
 *
 * Mirrors gtk_window_supports_client_shadow (gtk3 gtkwindow.c): running
 * compositor + RGBA visual, and on X11 a WM advertising _GTK_FRAME_EXTENTS
 * in _NET_SUPPORTED. kind: 1 = X11, 2 = Wayland.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxShadowBridge_nativeShadowSupported(
    JNIEnv *env, jclass clazz, jint kind) {
    (void) env; (void) clazz;
    if (!shadow_ensure_loaded()) return JNI_FALSE;
    GdkScreen *screen = s.gdk_screen_get_default();
    if (screen == NULL) return JNI_FALSE;
    if (!s.gdk_screen_is_composited(screen)) return JNI_FALSE;
    if (s.gdk_screen_get_rgba_visual(screen) == NULL) return JNI_FALSE;
    if (kind == 1) {
        if (s.gdk_x11_screen_supports_net_wm_hint == NULL) return JNI_FALSE;
        GdkAtom atom = s.gdk_atom_intern_static_string("_GTK_FRAME_EXTENTS");
        if (!s.gdk_x11_screen_supports_net_wm_hint(screen, atom)) return JNI_FALSE;
    }
    return JNI_TRUE;
}

/*
 * Renders the themed decoration (shadow + 1px outline) around a visibleW x
 * visibleH frame, with marginL/T/R/B logical pixels of room on each side,
 * at the given device scale. Corner radii (logical px, TL/TR/BR/BL) are
 * forced via a per-context USER-priority provider so the shadow hugs the
 * same rounded shape the Skia carve applies to the content.
 *
 * Returns [widthPx, heightPx, pixel0, pixel1, ...] where pixels are
 * row-major premultiplied ARGB32 (cairo native = Skia BGRA_8888 PREMUL on
 * little-endian), or NULL on failure.
 */
JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxShadowBridge_nativeShadowRender(
    JNIEnv *env, jclass clazz, jboolean backdrop, jboolean tiled,
    jint visibleW, jint visibleH,
    jint marginL, jint marginT, jint marginR, jint marginB,
    jfloat scale, jfloat radTL, jfloat radTR, jfloat radBR, jfloat radBL) {
    (void) clazz;
    if (!shadow_ensure_loaded()) return NULL;
    if (visibleW <= 0 || visibleH <= 0 || scale <= 0.0f) return NULL;

    const int pw = (int) ceil((visibleW + marginL + marginR) * (double) scale);
    const int ph = (int) ceil((visibleH + marginT + marginB) * (double) scale);
    if (pw <= 0 || ph <= 0 || pw > 8192 || ph > 8192) return NULL;

    GtkStyleContext *ctx = shadow_make_decoration_context(backdrop, tiled);

    /* Integer formatting only: %f obeys LC_NUMERIC and a comma decimal
     * separator (e.g. fr_FR) is a CSS parse error. */
    char css[192];
    snprintf(css, sizeof(css),
             "decoration { border-radius: %dpx %dpx %dpx %dpx; }",
             (int) lround((double) radTL), (int) lround((double) radTR),
             (int) lround((double) radBR), (int) lround((double) radBL));
    GtkCssProvider *provider = s.gtk_css_provider_new();
    if (s.gtk_css_provider_load_from_data(provider, css, -1, NULL)) {
        s.gtk_style_context_add_provider(ctx, provider, GTK_STYLE_PROVIDER_PRIORITY_USER);
    }

    cairo_surface_t *surface = s.cairo_image_surface_create(CAIRO_FORMAT_ARGB32, pw, ph);
    s.cairo_surface_set_device_scale(surface, scale, scale);
    cairo_t *cr = s.cairo_create(surface);
    /* Outset box-shadows are painted by the background pass (GTK3
     * gtkrenderbackground.c paints them outside the border box before the
     * background itself); the frame pass adds CSS borders if the theme has
     * any on the decoration node. */
    s.gtk_render_background(ctx, cr, marginL, marginT, visibleW, visibleH);
    s.gtk_render_frame(ctx, cr, marginL, marginT, visibleW, visibleH);
    s.cairo_destroy(cr);
    s.cairo_surface_flush(surface);

    const unsigned char *data = s.cairo_image_surface_get_data(surface);
    const int stride = s.cairo_image_surface_get_stride(surface);
    jintArray out = NULL;
    if (data != NULL) {
        out = (*env)->NewIntArray(env, 2 + pw * ph);
        if (out != NULL) {
            jint header[2] = { pw, ph };
            (*env)->SetIntArrayRegion(env, out, 0, 2, header);
            for (int row = 0; row < ph; row++) {
                (*env)->SetIntArrayRegion(env, out, 2 + row * pw, pw,
                                          (const jint *) (data + (size_t) row * stride));
            }
        }
    }

    s.cairo_surface_destroy(surface);
    s.g_object_unref(provider);
    s.g_object_unref(ctx);
    return out;
}

/* ── WM margin declaration, synchronized with the window state ──────────
 *
 * GTK zeroes its shadow width the moment the window turns maximized /
 * fullscreen / tiled and restores it on the way back, synchronously inside
 * the window-state-event dispatch — BEFORE the next surface commit. Doing
 * this a frame later (from the render loop) is too late on Wayland: the
 * committed xdg geometry disagrees with the compositor's configure during
 * a maximize/snap transition and mutter visibly fights the window. So the
 * desired margins are stashed on the GtkWindow and a window-state-event
 * handler applies the effective value with GTK's exact timing. */

typedef struct {
    int l;
    int t;
    int r;
    int b;
    /* Last effective suspend decision: -1 unknown, 0 margins, 1 zeroed.
     * window-state-event also fires for focus-only changes; re-calling
     * gdk_window_set_shadow_width with unchanged values must be avoided. */
    int suspended;
} ShadowMargins;

static const char *SHADOW_MARGINS_KEY = "nucleus-shadow-margins";

/* GdkWindowState: MAXIMIZED | FULLSCREEN | TILED | TOP/RIGHT/BOTTOM/LEFT_TILED. */
#define SHADOW_STATE_SUSPEND_MASK \
    ((1u << 2) | (1u << 4) | (1u << 8) | (1u << 9) | (1u << 11) | (1u << 13) | (1u << 15))

/* Prefix of GdkEventWindowState — enough to reach new_window_state.
 * Layout on LP64: type @0, window @8, send_event @16, changed_mask @20,
 * new_window_state @24. */
typedef struct {
    int type;
    void *window;
    signed char send_event;
    unsigned int changed_mask;
    unsigned int new_window_state;
} ShadowGdkEventWindowState;

static void shadow_apply_effective(GtkWidget *widget, unsigned int state) {
    ShadowMargins *m = s.g_object_get_data(widget, SHADOW_MARGINS_KEY);
    GdkWindow *gdk_window = s.gtk_widget_get_window(widget);
    if (m == NULL || gdk_window == NULL) return;
    const int suspended = (state & SHADOW_STATE_SUSPEND_MASK) != 0;
    if (suspended == m->suspended) return;
    m->suspended = suspended;
    if (suspended) {
        s.gdk_window_set_shadow_width(gdk_window, 0, 0, 0, 0);
    } else {
        s.gdk_window_set_shadow_width(gdk_window, m->l, m->r, m->t, m->b);
    }
}

static gboolean shadow_on_window_state_event(GtkWidget *widget, void *event, void *user_data) {
    (void) user_data;
    if (event != NULL) {
        const ShadowGdkEventWindowState *ev = (const ShadowGdkEventWindowState *) event;
        shadow_apply_effective(widget, ev->new_window_state);
    }
    return 0; /* propagate */
}

/*
 * Declares the invisible shadow margin to the window manager — the exact
 * internal GTK call: _GTK_FRAME_EXTENTS (scaled to device px by GDK) on
 * X11, per-side margins + xdg_surface.set_window_geometry on Wayland (where
 * GDK also grows the surface to keep the visible geometry stable). Margins
 * are logical pixels; they auto-suspend while maximized/fullscreen/tiled
 * (see shadow_on_window_state_event above).
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxShadowBridge_nativeShadowApply(
    JNIEnv *env, jclass clazz, jlong gtkWindowPtr,
    jint marginL, jint marginT, jint marginR, jint marginB) {
    (void) env; (void) clazz;
    if (!shadow_ensure_loaded() || gtkWindowPtr == 0) return;
    GtkWidget *widget = (GtkWidget *) (intptr_t) gtkWindowPtr;
    GdkWindow *gdk_window = s.gtk_widget_get_window(widget);
    if (gdk_window == NULL) return;

    ShadowMargins *margins = (ShadowMargins *) malloc(sizeof(ShadowMargins));
    if (margins == NULL) return;
    margins->l = marginL;
    margins->t = marginT;
    margins->r = marginR;
    margins->b = marginB;
    margins->suspended = -1;
    const int first = s.g_object_get_data(widget, SHADOW_MARGINS_KEY) == NULL;
    s.g_object_set_data_full(widget, SHADOW_MARGINS_KEY, margins, free);
    if (first) {
        s.g_signal_connect_data(widget, "window-state-event",
                                (void (*)(void)) shadow_on_window_state_event,
                                NULL, NULL, 0);
    }
    shadow_apply_effective(widget, s.gdk_window_get_state(gdk_window));
}

/*
 * Restricts the window's input region to the given logical rect (the
 * visible frame inflated by the 12 px resize ring — GTK4's
 * RESIZE_HANDLE_SIZE) so clicks in the outer shadow fall through to
 * whatever is below. Pass width < 0 to reset to the full surface.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxShadowBridge_nativeShadowSetInputShape(
    JNIEnv *env, jclass clazz, jlong gtkWindowPtr,
    jint x, jint y, jint width, jint height) {
    (void) env; (void) clazz;
    if (!shadow_ensure_loaded() || gtkWindowPtr == 0) return;
    GdkWindow *gdk_window = s.gtk_widget_get_window((GtkWidget *) (intptr_t) gtkWindowPtr);
    if (gdk_window == NULL) return;
    if (width < 0) {
        s.gdk_window_input_shape_combine_region(gdk_window, NULL, 0, 0);
        return;
    }
    cairo_rectangle_int_t rect = { x, y, width, height };
    cairo_region_t *region = s.cairo_region_create_rectangle(&rect);
    s.gdk_window_input_shape_combine_region(gdk_window, region, 0, 0);
    s.cairo_region_destroy(region);
}

/*
 * Cheap change-detection key for the shadow image cache: theme name +
 * dark preference. A live theme switch (or dark-mode toggle) changes the
 * stamp; the Kotlin side then re-renders its cached shadow images.
 */
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxShadowBridge_nativeShadowThemeStamp(
    JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (!shadow_ensure_loaded()) return NULL;
    GtkSettings *settings = s.gtk_settings_get_default();
    if (settings == NULL) return NULL;
    gchar *name = NULL;
    gboolean dark = 0;
    s.g_object_get(settings,
                   "gtk-theme-name", &name,
                   "gtk-application-prefer-dark-theme", &dark,
                   NULL);
    char stamp[256];
    snprintf(stamp, sizeof(stamp), "%s|%d", name != NULL ? name : "?", dark ? 1 : 0);
    if (name != NULL) s.g_free(name);
    return (*env)->NewStringUTF(env, stamp);
}
