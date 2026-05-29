// Copyright 2014-2021 The winit contributors
// Copyright 2021-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0

#![cfg(target_os = "linux")]

// PATCH(nucleus): event-driven minimize/restore hook (mirrors the Windows hook
// in `platform/windows.rs` and the macOS hook in `platform/macos.rs`).
//
// Tao has no `WindowEvent::Minimized`. On Linux/GTK the iconified state surfaces
// only through GDK's `window-state-event` signal, which fires for every state
// change (focus, maximize, sticky, …) — there is no `WindowEvent::Resized`-style
// notification to observe. Rather than add an enum variant — which would ripple
// through every exhaustive `WindowEvent` match — the GTK `connect_window_state_event`
// handler calls this hook only when the `ICONIFIED` bit actually transitions.
// The embedder installs a fn pointer and dispatches its own minimized event
// deterministically (`true` = minimized/iconified, `false` = restored).
//
// Caveat: this is effectively X11-only. Wayland's xdg-shell has a `set_minimized`
// request but no event reporting the minimized state back, so GTK never raises
// `GDK_WINDOW_STATE_ICONIFIED` on a Wayland session — the hook simply never fires.
pub(crate) static MINIMIZED_HOOK: std::sync::OnceLock<fn(crate::window::WindowId, bool)> =
  std::sync::OnceLock::new();

/// Install a hook invoked from the GTK `window-state-event` handler whenever a
/// window's iconified state changes (`true` = iconified, `false` = restored).
/// Idempotent: the first installed hook wins. The hook runs on the main thread
/// inside the GTK signal callback, so it must not block or pump the loop — it
/// should only post a user event back to the event loop.
pub fn set_minimized_hook(hook: fn(crate::window::WindowId, bool)) {
  let _ = MINIMIZED_HOOK.set(hook);
}
