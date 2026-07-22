# Linux CSD drop shadow — dedicated shadow subsurface (approach B)

Reimplementation of the GTK-style client-side drop shadow for the Tao backend's
undecorated Linux windows, replacing the failed #361 attempt that was removed in
#374 (`5be63ca0`).

## Why the #361 approach failed

#361 grew the *content* EGL child subsurface by the shadow margins, drew the
shadow in the freed ring, and carved the margin transparent with
`BlendMode.CLEAR`. Two fatal problems:

1. **Opaque frame** — on driver stacks that present the EGL child buffer as
   opaque (NixOS/GNOME Wayland, AMD/Mesa — issue #374), the `CLEAR` margin
   showed pure black instead of the desktop behind.
2. **Resize decoupling** — the content subsurface is not clipped to its parent
   and, in `set_desync` mode, lagged/spilled outside the window on fast resize.

Gating harder on compositor alpha (Chrome's `ArgbVisualAvailable`) does **not**
help: on Wayland the compositor advertises alpha, yet that specific EGL child
buffer is still presented opaque. Chrome avoids the whole class of bug by never
putting the shadow in a separate, possibly-opaque buffer — it composites into a
surface the compositor is guaranteed to alpha-blend.

## Constraint: GTK owns the toplevel surface

Unlike Chrome (which owns its toplevel via Ozone/xdg-shell), tao uses a
`gtk::ApplicationWindow`. GTK paints a cairo SHM buffer onto its `wl_surface`
on every `draw` signal, so we cannot bind EGL to the toplevel — that is exactly
why the content already lives in an owned `wl_subsurface` child
(`nucleus_tao_egl.c:973-985`).

## Approach B — dedicated shadow subsurface

Put the shadow in a **second `wl_subsurface` we fully own**, backed by a
**wl_shm ARGB buffer**. An SHM ARGB surface with no opaque region is *always*
alpha-blended by the compositor — no dependency on how the driver presents the
EGL buffer, and no coordination with GTK's cairo draw handler.

```
parent GTK wl_surface (transparent 0,0,0,0 — fully covered)
  └─ shadow subsurface   @ (0,0), full window size, SHM ARGB   [place_below content]
       ░░ themed shadow painted in the margin ring ░░
       ┌────────────────────────────────┐
       │  content subsurface  @ (mL,mT)  │  EGL, visible area only, on top
       └────────────────────────────────┘
```

Key differences from #361:

- The content EGL buffer is the **visible area only** — no margin ring, no
  `CLEAR` carve in it (only rounded-corner carve at its own edges). Whatever the
  driver does with its alpha is irrelevant to the shadow.
- The shadow lives in an **SHM** buffer we paint with cairo (reusing the theme
  render), guaranteed blended.
- Content subsurface offset from `(0,0)` to `(marginLeft, marginTop)`.

### Shadow pixels

Reuse the theme render from the old `nucleus_tao_linux_shadow.c`: render the
live theme's `window.csd > decoration` CSS node off-screen into a cairo ARGB32
surface (native look across Adwaita/Yaru/Breeze incl. the 1px outline), for both
the NORMAL and BACKDROP focus states. Instead of nine-slicing into Skia, we blit
the theme render straight into the shadow subsurface's SHM buffer (full window,
transparent center, shadow ring). Focus cross-fade (200 ms ease-out, Adwaita
`$backdrop_transition`) = paint `normal·(1−f) + backdrop·f` into the SHM buffer
per fade frame; cheap cairo blits.

### WM margin declaration

Still required so the WM lays out the window with the invisible border (maximize
lands in the workarea, not workarea+margins; resize hit region extends into the
ring). Reuse `nativeShadowApply` → `gdk_window_set_shadow_width` (X11
`_GTK_FRAME_EXTENTS`) / GDK grows the surface + `xdg_surface.set_window_geometry`
on Wayland. Zero/restore synchronously on maximize/fullscreen/tile via the
`window-state-event` handler (kept from the old code).

### Resize

Set the content **and** shadow subsurfaces to **sync** during an interactive
resize grab so they apply atomically with the parent's transaction (no lag /
spill), back to `desync` when idle for independent vsync-paced content swaps.

### Move (issue #383)

The shadow subsurface overflows the toplevel's window geometry (negative
offset). In `desync` mode Mutter/GNOME computes interactive-move damage from the
window geometry and skips that overflow, so the shadow ring traces at the old
position while the window is dragged and only clears when the grab ends. Flip
the shadow subsurface to **sync** for the duration of the compositor move grab
(`nativeShadowSetSync`) so it rides the toplevel's atomic surface tree and the
compositor moves + repaints it together with the window; restore `desync` when
the grab ends. Wired in `TaoComposeSceneHostLinux.onNativeWindowDragStarted` /
`endShadowMoveSync`.

## Scope: Wayland only

This effort targets **Wayland only**. X11 keeps the current flat, shadowless
behaviour (the shadow is simply not activated when the EGL attachment kind is
X11). No X11 child-window shadow work. The controller and native bridge gate on
`kind == 2` (Wayland) and no-op otherwise, so X11 windows render exactly as they
do today post-#374.

## Phases

1. **Foundations** — revive + adapt the theme-render C (`nucleus_tao_linux_shadow.c`:
   dlopen GTK/cairo, `shadow_render_theme` → ARGB pixels, `nativeShadowSupported`,
   `nativeShadowApply` WM margins, `nativeShadowThemeStamp`) and the Kotlin
   controller `TaoWindowShadowLinux` (measure margins, focus fraction). No
   compositing yet.
2. **Content inset (Wayland)** — `set_position(mL,mT)`, EGL buffer = visible area
   only, `nativeResize` accordingly; declare margins to the WM.
3. **Shadow subsurface (Wayland)** — the core of B: wl_shm pool + ARGB buffer,
   second subsurface placed below content, paint theme shadow, commit on
   focus/size/theme change.
4. **Resize atomic** — sync subsurfaces during grab; resize hit-test extended
   into the margin.
5. **Housekeeping** — reachability-metadata, build.sh, CI natives verify lists,
   tests.

## Files

- `native/linux/nucleus_tao_linux_shadow.c` — theme render + WM margin decl (revived).
- `native/linux/nucleus_tao_egl.c` — shadow subsurface + SHM + content inset (new core).
- `kotlin/.../deco/TaoWindowShadowLinux.kt` — controller (margins, focus fade).
- `kotlin/.../ffi/NativeTaoLinuxShadowBridge.kt` — JNI bridge.
- `kotlin/.../scene/TaoComposeSceneHostLinux.kt` — wire controller, drop old carve-in-margin.
- `kotlin/.../DecoratedWindow.kt` — margin bookkeeping (WM geometry only; content
  no longer needs Compose padding — the content subsurface is already inset).
