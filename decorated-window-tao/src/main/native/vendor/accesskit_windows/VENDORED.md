# Vendored fork of `accesskit_windows` 0.29.2

Source: https://github.com/AccessKit/accesskit (crates.io 0.29.2)
License: MIT OR Apache-2.0

## Local patches

- `src/node.rs` `ProviderOptions`: add `ProviderOptions_UseComThreading`
  alongside `ProviderOptions_ServerSideProvider` (Chromium/Electron parity).
- `src/node.rs` `IExpandCollapseProvider`: port ExpandCollapse pattern from
  upstream 0.34 so expand/collapse controls are activatable in UIA
  (accesskit_consumer treats them as non-invocable).
- `src/adapter.rs` `QueuedEvents::raise`: do not `.unwrap()` on raise
  HRESULTs (empty queue is a no-op; raise failures must not abort the JVM).
