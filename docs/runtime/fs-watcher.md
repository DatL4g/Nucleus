# FS Watcher

`fs-watcher` is a Kotlin-first filesystem watching API for Nucleus, backed by a Rust JNI bridge.

Current implementation:

- native backend: `notify 8.2.0`
- stable delivered events: `Created`, `Modified`, `Removed`, `Overflow`
- `Moved` is best-effort on the native debounced path only; `Raw` and `Polling` do not currently guarantee it
- `Other` remains part of the public model, but is not a current delivery guarantee

## Current API

```kotlin
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.flow.Flow

data class FsWatchSource(
    val root: Path,
    val recursive: Boolean,
    val name: String? = null,
)

sealed interface FsWatchEvent {
    val source: FsWatchSource?
    val needsRescan: Boolean

    data class Created(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Modified(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Removed(
        val path: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Moved(
        val from: Path,
        val to: Path,
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent

    data class Overflow(
        override val source: FsWatchSource? = null,
        override val needsRescan: Boolean = true,
    ) : FsWatchEvent

    data class Other(
        val paths: List<Path> = emptyList(),
        override val source: FsWatchSource? = null,
        val isDirectory: Boolean? = null,
        override val needsRescan: Boolean = false,
    ) : FsWatchEvent
}

sealed interface FsWatchBackendStrategy {
    data object Auto : FsWatchBackendStrategy
    data object NativeOnly : FsWatchBackendStrategy
    data class Polling(
        val interval: Duration = Duration.ofSeconds(2),
        val compareContents: Boolean = false,
    ) : FsWatchBackendStrategy
}

sealed interface FsWatchDeliveryMode {
    data object Raw : FsWatchDeliveryMode
    data class Debounced(
        val window: Duration = Duration.ofMillis(150),
    ) : FsWatchDeliveryMode
}

data class FsWatcherConfig(
    val followSymlinks: Boolean = false,
    val backend: FsWatchBackendStrategy = FsWatchBackendStrategy.Auto,
    val eventBufferCapacity: Int = 64,
    val errorBufferCapacity: Int = 32,
    val deliveryMode: FsWatchDeliveryMode = FsWatchDeliveryMode.Debounced(),
)

data class FsWatchError(
    val message: String,
    val source: FsWatchSource? = null,
    val recoverable: Boolean = false,
    val cause: Throwable? = null,
)

class FsWatchException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface FsWatchRegistration : AutoCloseable {
    val source: FsWatchSource
    val active: Boolean
    override fun close()
}

interface FsWatcher : AutoCloseable {
    val registrations: Set<FsWatchSource>
    val events: Flow<FsWatchEvent>
    val errors: Flow<FsWatchError>

    fun watch(
        path: Path,
        recursive: Boolean = true,
        name: String? = null,
    ): FsWatchRegistration

    override fun close()
}

object FsWatchers {
    fun create(config: FsWatcherConfig = FsWatcherConfig()): FsWatcher
    fun isSupported(config: FsWatcherConfig = FsWatcherConfig()): Boolean
}
```

`FsWatchBackendStrategy.Polling` is now implemented as a minimal viable fallback backend. It is opt-in: callers only get polling when they explicitly request `FsWatchBackendStrategy.Polling(...)`, and `FsWatchers.isSupported(config)` / `FsWatchers.create(...)` validate that the native bridge is available for polling-backed creation instead of treating polling as part of the default native-only probing path.

`FsWatchBackendStrategy.Auto` still only controls the default backend selection path and availability probing for that path. It does not auto-fallback to polling when native watching is unavailable, and it does not auto-switch between raw and debounced delivery.

`eventBufferCapacity` and `errorBufferCapacity` are result-oriented tuning knobs. Increasing them can reduce premature watcher-level `Overflow` and error drops under bursty load, but they do not change backend semantics or make delivery lossless. Both capacities must be greater than `0`.

`deliveryMode` is watcher-scoped configuration. The default is `FsWatchDeliveryMode.Debounced(window = 150ms)`, which favors lower-noise delivery over exposing the raw OS event stream. `FsWatchDeliveryMode.Raw` is an explicit opt-out for callers that want native callbacks forwarded without the debounced contract. Debounce window validation is part of config construction and currently requires a positive `Duration` that remains at least `1ms` after `toMillis()` conversion.

Coroutine scope, dispatcher choice, native callback execution, and backpressure strategy remain internal implementation details in this release and are not public API.

## Event Semantics

- Stable cross-platform core events should be `Created`, `Modified`, `Removed`, and `Overflow`.
- `Moved` should be treated as best-effort. The current supported path is native + `FsWatchDeliveryMode.Debounced`, and callers must still tolerate `Removed + Created` or other rename-like observations on hosts that do not surface a paired rename shape.
- `needsRescan` is part of the public model from v1 because native backends can drop or coalesce events.
- `Overflow` and any event with `needsRescan = true` mean rescan wins. Once either signal is observed, callers should treat incremental ordering as lossy and reconcile from filesystem state.
- The bridge keeps a conservative stable surface: `Modified` is only guaranteed for the current `notify` shapes we explicitly map, and rename-like partial hints remain outside the stable public contract unless they are promoted into a documented best-effort event such as native debounced `Moved`.
- Each unique live `FsWatchSource` acts as a logical subscriber, even when multiple registrations map to shared native coverage.
- When one filesystem change matches multiple distinct live sources, the public layer fans that change out per source, and each delivered public event carries exactly one `source`.
- Shared native watches for same-path, same-`recursive` registrations are only an implementation optimization. Repeated `watch(...)` calls with the same `FsWatchSource` are ref-counted lifecycle handles, not duplicate public deliveries.
- Overlap registrations are allowed, and the library should not duplicate-deliver the same native change to the same `FsWatchSource` just because internal native installation planning keeps redundant overlap coverage out of the backend.
- Native backends and host OS layers may still duplicate, drop, or coalesce notifications, so delivery is at-least-once rather than exactly-once.
- Default delivery is debounced and low-noise, so public delivery timing and event granularity are not the same thing as raw native callback timing or count.
- Polling delivery is also not timing-equivalent to the default native debounced path: poll interval, snapshot cadence, and change coalescing can differ materially from callback-driven native delivery even when both surface the same public event types.
- `source = null` is reserved for watcher-level `Overflow` and watcher-level errors, so callers should treat it as a full-watcher rescan signal rather than an ordinary file event.
- `followSymlinks` defaults to `false` to avoid unexpectedly expanding recursive watches through symlinks.
- Paths should keep stable user-visible semantics. Registration matching stays anchored to the original lexical root rather than silently replacing it with `toRealPath()`.
- With `followSymlinks = false`, that lexical anchoring is also the hard boundary for callback routing: even if the native backend or OS reports a resolved / canonical descendant path, Kotlin matching must not attribute that event or path-scoped error back to the symlink's lexical source.
- With `followSymlinks = true`, resolved-root matching is still allowed so canonical callback paths can be remapped back onto the watched lexical symlink root before public delivery.
- For the first `notify`-backed slice, `Modified` only has a stable contract for `Modify(Data(_))` and `Modify(Metadata(_))`; rename-related `Modify(Name(_))` cases are intentionally left out of the guaranteed surface.
- This also means `Modified` is a conservative first-slice guarantee, not a promise that every backend will report ordinary file writes as `Modified`; for example, `notify 8.2.0` on Windows often reports writes as `Modify(Any)`.

## Native Integration

Internal shape:

```text
Kotlin public API
  -> Kotlin internal NativeFsWatcherBridge
  -> Rust cdylib
  -> notify
```

The bridge should follow the same overall pattern already used by `decorated-window-tao`:

- Kotlin `internal object` with `NativeLibraryLoader.load(...)`
- Rust exports JNI symbols directly
- native binaries shipped under `src/main/resources/nucleus/native/...`
- that resource layout is intentionally multi-platform shaped, so the loader contract can select platform-specific artifacts by OS/arch directory
- GraalVM reachability metadata included from the beginning
- Reachability metadata for JNI callbacks must stay bytecode-accurate with Kotlin bridge signatures, including boxed types for nullable parameters such as `originNativeRegistrationId: Long?` -> `java.lang.Long`

Artifact note:

- The source tree and `NativeLibraryLoader` resource layout are designed for multi-platform packaging, not just a single-host layout.
- A local source-tree build and local `processResources` verification only prove that the current host can assemble and load its own packaged native artifact from `src/main/resources/nucleus/native/...`.
- In the current repository state, that local verification is host-oriented. It does not by itself prove that Linux, Windows, and macOS release artifacts are all present, correctly packaged, or mutually consistent.
- Complete cross-platform release integrity must be established by dedicated CI or release workflows that build and validate each target artifact set separately.
- This host-oriented warning also applies to polling validation in practice: the module now has polling coverage, but the real observed polling behavior still depends on which host OS has actually been exercised.

Observed host verification:

- Fresh Windows host validation passed for `:fs-watcher:test` and `:fs-watcher:build :fs-watcher:publishToMavenLocal`.
- Fresh Linux validation also passed for `:fs-watcher:test` and `:fs-watcher:build :fs-watcher:publishToMavenLocal` when run from a Linux-native clone under WSL rather than from `/mnt/d/...`.
- Fresh macOS validation also passed for `:fs-watcher:test --tests dev.nucleusframework.fswatcher.FsWatcherRealFileSystemTest` and `:fs-watcher:check` on the local Darwin host.
- The local Windows toolchain still treats the ARM64 native artifact as optional in practice: x64 validation passed, while the optional ARM64 build continued to depend on host linker availability such as `msvcrt.lib`.

Internal callback payload can stay compact and implementation-oriented:

```text
watcherHandle
registrationId
eventKind
path
secondaryPath
needsRescan
isDirectory
```

Kotlin uses native registration IDs to validate callback liveness, then matches callback paths against live logical registrations and fans public delivery out per matching `FsWatchSource`.

The transport intentionally remains generic rather than event-type-specific. Public event meaning is assigned in Kotlin from `eventKind` plus payload shape, which keeps the JNI ABI small while still allowing narrow best-effort expansions such as debounced `Moved`.

Testing note:

- `onNativeEvent(...)` and `onNativeError(...)` are part of the required internal runtime bridge and must remain aligned with the Rust callback signatures.
- The module also currently keeps internal `nativeDebugEmit*` JNI exports as test fixtures for native-side liveness/path-matching probes that are not yet covered elsewhere with comparable cost.
- Those debug JNI exports are not public API, are not part of the runtime contract, and should not be expanded further without an explicit follow-up decision.
- They are intentional short-term test debt: keep them scoped to the existing native-side liveness/path-matching probes, do not route production features through them, and prefer eventual replacement with lower-layer native tests if that can be done without adding a heavier multi-artifact test build pipeline.

## Current Event Delivery Scope

The current native `notify` backend stably delivers:

- `FsWatchEvent.Created`
- `FsWatchEvent.Modified`
- `FsWatchEvent.Removed`
- `FsWatchEvent.Overflow`

By default those events are delivered through watcher-level debounced delivery semantics. Raw delivery remains available only through `FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw)`.

`FsWatchEvent.Moved` is now surfaced on the native debounced path as a best-effort event when the host backend reports a paired rename shape with both endpoints. This is an observation-sensitive capability, not a cross-host invariant: some hosts may still emit rename scenarios as `Removed + Created` or other non-paired event shapes, and that remains within contract.

`FsWatcherConfig(deliveryMode = FsWatchDeliveryMode.Raw)` does not add a `Moved` guarantee. Raw delivery forwards native callbacks without the debounced rename pairing semantics. `FsWatchBackendStrategy.Polling(...)` also does not guarantee `Moved`; the polling backend remains scoped to the stable core event surface rather than snapshot-based move inference.

For `FsWatchBackendStrategy.Polling`, the current implementation scope is intentionally narrow:

- it is the minimum viable backend for callers that explicitly choose polling
- it does not imply automatic fallback from `Auto`
- it shares the same public event model, but not identical timing or event granularity, with the default native debounced path
- `compareContents = true` only applies within the current native bridge / backend integration surface; it should be read as "enable backend-supported content comparison where available," not as a broader library guarantee of stable content hashing semantics

The following are still intentionally deferred:

- `Access` events
- full cross-platform semantic normalization

Deferred maintenance notes:

- coverage-aware native installation planning deduplicates overlapping internal native coverage before native watch installation
- Kotlin callback routing is scoped to the native installation that originated the callback instead of scanning watcher-global registrations
- path-to-registration matching is still linear within one native installation's carried registrations
- pending overlap races resolve to one surviving owner or a clean retry path instead of orphaning a logical registration
- local source-tree verification remains host-oriented even though the packaged resource layout is multi-platform shaped; complete artifact integrity still belongs to dedicated CI or release validation
- polling is now implemented and host-verified, but its real behavior should still be described from observed host coverage rather than implied as fully normalized cross-platform equivalence
- the current residual debt set is small and explicit: debug JNI test fixtures, host-oriented validation boundaries, deferred raw/polling rename normalization, deferred `Access` / broader cross-platform normalization work, and the still-open policy question of whether `Auto` should ever fallback to polling

## Watcher-Level Events and `source = null`

The implementation keeps `registrationId = 0L` as a watcher-level sentinel. It is only used for:

- `FsWatchEvent.Overflow(source = null)`
- `FsWatchError(source = null, ...)`

Ordinary `Created` / `Modified` / `Removed` events stay source-scoped. If a native change matches multiple distinct live sources, the public layer fan-outs one event per source instead of publishing an ambiguous null-source event.

## Registration Overlap Contract

- Overlapping registrations are allowed.
- Recursive roots cover the root itself and all descendants.
- Non-recursive roots cover the root itself and its direct children only.
- Coverage matching is still determined by registration coverage, not by lexical nesting alone. A deep grandchild remains outside a non-recursive parent registration unless that parent explicitly covers it.
- Each unique live `FsWatchSource` is treated as an independent logical subscriber for delivery.
- When one native change falls under multiple distinct live sources, the watcher fan-outs public delivery per source, and each public event has exactly one `source`.
- Same-path registrations with the same `recursive` flag may share one native watch. Repeated watches of the same `FsWatchSource` stay ref-counted and do not multiply delivered events.
- Internal overlap dedup and installation-scoped path routing are not a promise to alias-collapse distinct lexical registrations that happen to resolve to the same canonical location.
- Symlink and canonical-path handling is phrased in terms of delivery matching, not ownership transfer: logical matching stays anchored to the original lexical root first, and an unsafe remap drops the logical match instead of re-anchoring it to a different root.
- In practice, this means a resolved callback path only participates in logical matching when the registration opted into symlink following; otherwise the lexical symlink source keeps ownership only for paths that match its original lexical root.

## Flow Semantics

- `events` and `errors` are hot flows fed by native callbacks.
- Late native callbacks after `close()` or after a registration `close()` are silently dropped.
- In the current slice, `close()` does not actively complete `events` or `errors`; both flows remain silent instead of emitting further items.
- If the Kotlin-side event buffer is full, the watcher attempts to emit `Overflow(source = null)`, and callers should trigger a full rescan.
- `eventBufferCapacity` and `errorBufferCapacity` size only these Kotlin-side result buffers; they do not expose coroutine wiring or any alternate backpressure policy.

## Collection Example

```kotlin
val watcher = FsWatchers.create()
watcher.watch(projectRoot)

CoroutineScope(Dispatchers.IO).launch {
    watcher.events.collect { event ->
        println("fs event: $event")
    }
}

CoroutineScope(Dispatchers.IO).launch {
    watcher.errors.collect { error ->
        println("fs watcher error: $error")
    }
}
```
