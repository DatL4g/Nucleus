# Migration from Nucleus 1.x to 2.0

Nucleus 2.0 is a major release that consolidates the framework around a single entry point — `nucleusApplication` — and renames the project namespace to `dev.nucleusframework`. The migration is mostly mechanical: search/replace for the namespace, then move your `application { }` block into `nucleusApplication(args) { }`. The DSL that emerges removes ~30 lines of bootstrap boilerplate from a typical `main()`.

This guide walks through the changes in order. Apply them top-to-bottom.

---

## Prerequisites

Before touching any code, bump these — 2.0 will not resolve otherwise.

### JDK toolchain

2.0 artifacts target JDK 17 (`nucleus-application`) and JDK 25 (`decorated-window-jewel`, the Jewel/IntelliJ stack). Bump every Kotlin module that depends on Nucleus:

```kotlin
// Single-target modules
kotlin { jvmToolchain(25) }

// KMP modules — set it on the top-level kotlin block (applies to the jvm() target)
kotlin {
    jvmToolchain(25)
    jvm()
    androidLibrary { … } // Android compilations still produce JVM 11 bytecode via their own jvmTarget
}
```

Symptom if you skip this: `Dependency resolution is looking for a library compatible with JVM runtime version 11, but 'dev.nucleusframework:nucleus.decorated-window-jewel' is only compatible with JVM runtime version 25 or newer.`

### IntelliJ snapshots repository

2.0 pulls Jewel `0.37.0-262.4852.74`, which only lives in the IntelliJ **snapshots** repository — not releases. Add it to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots") // ← new
    }
}
```

If your project also declares Jewel directly (e.g. `jewel-int-ui-standalone`), bump it to the same coordinate Nucleus brings in transitively — otherwise Gradle resolves two incompatible Jewel versions side-by-side:

```toml
intellijIcons = "262.4852.74"
jewel        = "0.37.0-262.4852.74"
```

---

## At a Glance

| Area | 1.x | 2.0 |
|---|---|---|
| Plugin ID | `io.github.kdroidfilter.nucleus` | `dev.nucleusframework` |
| Maven group | `io.github.kdroidfilter` | `dev.nucleusframework` |
| Kotlin package root | `io.github.kdroidfilter.nucleus.*` | `dev.nucleusframework.*` |
| Entry point | `application { … }` | `nucleusApplication(args) { … }` |
| Window | `Window(…)` | `DecoratedWindow(…)` (or `MaterialDecoratedWindow`, `JewelDecoratedWindow`) |
| GraalVM bootstrap | Manual `GraalVmInitializer.initialize()` | Automatic |
| Single instance | Manual `SingleInstanceManager.isSingleInstance(…)` | Automatic |
| Window restore on 2nd-instance | Manual `LaunchedEffect` + `toFront()` | Automatic |
| AOT training timer | Manual `Thread` + `exitProcess` | `aotTraining(duration = …)` |
| AutoLaunch cache prime | Manual `AutoLaunch.wasStartedAtLogin(args)` | Automatic |
| Windows AUMID | Manual `WindowsJumpListManager.setProcessAppId()` | Automatic |

---

## Step 1 — Plugin ID & Maven Coordinates

```diff
 plugins {
-    id("io.github.kdroidfilter.nucleus") version "1.3.0"
+    id("dev.nucleusframework") version "2.0.0"
 }
```

Module dependencies follow the same rename:

```diff
 dependencies {
-    implementation("io.github.kdroidfilter:nucleus.core-runtime:1.3.0")
-    implementation("io.github.kdroidfilter:nucleus.aot-runtime:1.3.0")
-    implementation("io.github.kdroidfilter:nucleus.nucleus-application:1.3.0")
+    implementation("dev.nucleusframework:nucleus.core-runtime:2.0.0")
+    implementation("dev.nucleusframework:nucleus.aot-runtime:2.0.0")
+    implementation("dev.nucleusframework:nucleus.nucleus-application:2.0.0")
 }
```

The build-script DSL types move too:

```diff
-import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
-import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
+import dev.nucleusframework.desktop.application.dsl.TargetFormat
+import dev.nucleusframework.desktop.application.dsl.CompressionLevel
```

---

## Step 2 — Rename All Kotlin Imports

Every runtime import shifts root package. The cleanest way is a project-wide find & replace:

```
io.github.kdroidfilter.nucleus  →  dev.nucleusframework
```

Examples:

```diff
-import io.github.kdroidfilter.nucleus.core.runtime.DeepLinkHandler
-import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
-import io.github.kdroidfilter.nucleus.core.runtime.Platform
-import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
-import io.github.kdroidfilter.nucleus.updater.NucleusUpdater
-import io.github.kdroidfilter.nucleus.updater.provider.GitHubProvider
+import dev.nucleusframework.core.runtime.DeepLinkHandler
+import dev.nucleusframework.core.runtime.NucleusApp
+import dev.nucleusframework.core.runtime.Platform
+import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
+import dev.nucleusframework.updater.NucleusUpdater
+import dev.nucleusframework.updater.provider.GitHubProvider
```

There are no class renames at this step — only the package prefix changes.

---

## Step 3 — Switch to `nucleusApplication`

In 1.x you had Compose Desktop's `application { }` and called Nucleus init helpers around it. In 2.0 `nucleusApplication` runs all of that for you in the correct order and exposes a unified `NucleusApplicationScope`.

### Before — `main()` in 1.x

```kotlin
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()
    AutoLaunch.wasStartedAtLogin(args)
    if (Platform.Current == Platform.Windows) {
        WindowsJumpListManager.setProcessAppId()
    }

    if (AotRuntime.isTraining()) {
        Thread({
            Thread.sleep(45_000)
            kotlin.system.exitProcess(0)
        }, "aot-timer").apply { isDaemon = false }.start()
    }

    application {
        val isFirstInstance = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
                onRestoreRequest = {
                    DeepLinkHandler.readUriFrom(this)
                    // hand-rolled state to bring window back to front …
                },
            )
        }
        if (!isFirstInstance) {
            exitApplication()
            return@application
        }

        DeepLinkHandler.register(args) { uri -> handleDeepLink(uri) }

        Window(onCloseRequest = ::exitApplication, title = "My App") {
            App()
        }
    }
}
```

### After — `main()` in 2.0

```kotlin
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.application.aotTraining
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) = nucleusApplication(args) {
    aotTraining(duration = 45.seconds)

    onDeepLink { uri -> handleDeepLink(uri) }

    MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
        App()
    }
}
```

### Early-exits that must happen *before* `nucleusApplication`

`nucleusApplication` runs the full bootstrap (GraalVM init, single-instance lock, Compose loop). If your `main()` has invocation modes that should bypass all of that — e.g. a desktop scheduler/boot receiver re-launching the binary to run a single background task — keep them above the `nucleusApplication(args) { … }` call:

```kotlin
fun main(args: Array<String>) {
    if (DesktopBootReceiver.isSchedulerInvocation(args)) {
        DesktopBootReceiver.handle(args, registry = MyTaskRegistry.registry)
        exitProcess(0) // never reach Compose / single-instance
    }

    nucleusApplication(args) { … }
}
```

Putting these checks inside the scope would acquire the single-instance lock (and fight with the running primary instance) before short-circuiting — exactly what you don't want.

What `nucleusApplication` now handles for you, in order:

1. `GraalVmInitializer.initialize()` — fonts, charsets, HiDPI, `java.home`.
2. AOT training timer (when running with `-Dnucleus.aot.mode=training` and you call `aotTraining(…)`).
3. Single-instance lock acquisition. **If a second instance launches it relays its CLI deep link to the primary and exits with code 0 — Compose never starts on the secondary.**
4. Platform priming: `AutoLaunch.wasStartedAtLogin(args)` cache is warmed up and, on Windows, `WindowsJumpListManager.setProcessAppId()` is called before any window is created. Both run reflectively — they only fire if the `autolaunch` / `launcher-windows` modules are on the classpath.
5. Backend resolution (`NucleusBackend.Auto` picks AWT or Tao based on the classpath).
6. The Compose application loop.

You no longer need to call `AutoLaunch.wasStartedAtLogin(args)` or `WindowsJumpListManager.setProcessAppId()` from `main()` — they happen automatically. Other platform helpers (dock menus, Unity launcher quicklists, …) keep their native shape and live inside the scope.

---

## Step 4 — Replace `Window { }` with `DecoratedWindow { }`

Inside `nucleusApplication` you compose a decorated window. Three flavours are available — pick the one that matches your design system. Each lives in its own module, so add the matching dependency:

| Composable | Module |
|---|---|
| `DecoratedWindow(…)` — bare, bring your own title bar / theming | `nucleus.decorated-window-core` |
| `MaterialDecoratedWindow(…)` — Material 3 colors + decorated title bar | `nucleus.decorated-window-material` |
| `JewelDecoratedWindow(…)` — Jewel (IntelliJ) theme | `nucleus.decorated-window-jewel` |

All three expose `nucleusWindow` inside their content — a backend-agnostic handle for `show()`, `toFront()`, `setMinimized()`, etc.

### These are extension functions now — wrappers must propagate the scope

This is the most common breakage when porting an existing app: in 1.x, `JewelDecoratedWindow` (and friends) were plain `@Composable` functions, so you could wrap them in your own composable freely. In 2.0 they are **extensions on `NucleusApplicationScope`** (or `ApplicationScope` for the legacy variant):

```kotlin
fun NucleusApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit, …,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
)
```

Any wrapper composable you wrote in 1.x must become an extension on the same scope, otherwise you'll get `Unresolved reference 'JewelDecoratedWindow'` even though the import is correct.

```diff
 @Composable
-fun MyOnboardingWindow(vmFactory: ViewModelFactory) {
+fun NucleusApplicationScope.MyOnboardingWindow(vmFactory: ViewModelFactory) {
     JewelDecoratedWindow(onCloseRequest = {}, title = "…") { … }
 }
```

The call site (inside `nucleusApplication { … }`) doesn't change — the receiver is implicit.

```diff
-application {
-    Window(onCloseRequest = ::exitApplication, title = "My App") {
+nucleusApplication(args) {
+    MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
         App()
     }
 }
```

The plain Compose Desktop `Window` still works inside `nucleusApplication`, but you lose the unified `nucleusWindow` handle and the automatic restore-on-second-instance behavior described below.

### Dialogs follow the same rule

`JewelDecoratedDialog` ships in two flavours, mirroring `JewelDecoratedWindow`:

| Receiver | Backend support |
|---|---|
| `JewelDecoratedDialog(…)` (no receiver) | AWT only (JBR / JNI). Crashes on Tao with `NoClassDefFoundError: dev/nucleusframework/window/DecoratedDialogKt`. |
| `NucleusApplicationScope.JewelDecoratedDialog(…)` | Backend-agnostic. Dispatches to AWT or Tao under the hood. |

Use the scoped variant for anything composed inside `nucleusApplication { … }` — your "About", "Settings", and confirmation dialogs all need it:

```diff
 @Composable
-fun MyAboutDialog(onClose: () -> Unit) {
+fun NucleusApplicationScope.MyAboutDialog(onClose: () -> Unit) {
     JewelDecoratedDialog(onCloseRequest = onClose, title = "About") { … }
 }
```

The same applies to `MaterialDecoratedDialog` / the generic `DecoratedDialog` extension on `NucleusApplicationScope`.

### CompositionLocals propagate across the Tao scene boundary

The Tao backend opens a fresh `ComposeScene` per window/dialog. As of 2.0.0-alpha-202605131305 the full parent `CompositionLocalContext` (theme, `LocalDensity`, `LocalLayoutDirection`, user locals, …) is bridged into the new scene automatically — same behavior as Compose's own `Dialog`/`Popup`.

This means you do **not** need to wrap content twice anymore:

```kotlin
// Before — needed on Tao to avoid "No TextStyle provided" / "No IsDarkTheme provided"
IntUiTheme(theme, styling) {
    JewelDecoratedWindow(…) {
        IntUiTheme(theme, styling) { …content… }   // duplicate
    }
}

// After — a single wrap in the parent scope is enough on every backend
IntUiTheme(theme, styling) {
    JewelDecoratedWindow(…) { …content… }
}
```

If you previously threaded `theme` / `styling` parameters through every custom window or dialog (`JewelOnboardingWindow`, `JewelAboutWindow`, …) to re-apply `IntUiTheme` inside the scene, you can drop the threading: read the theme from the outer scope once.

---

## Step 5 — Single Instance Is Automatic

`nucleusApplication` acquires the single-instance lock synchronously, **before** Compose starts.

- Primary instance: a watcher fires whenever another launch happens, and any `DecoratedWindow` currently composed is automatically restored: `show()` + `setMinimized(false)` + `toFront()` + `requestFocus()`.
- Secondary instance: its CLI deep-link argument (if any) is written to the IPC file via `DeepLinkHandler.writeUriTo`, then the process exits with code 0. The primary receives the URI through its `onDeepLink { }` handler.

Delete the manual block from 1.x:

```diff
-val isFirstInstance = remember {
-    SingleInstanceManager.isSingleInstance(
-        onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
-        onRestoreRequest = {
-            DeepLinkHandler.readUriFrom(this)
-            isWindowVisible = true
-            restoreRequestCount++
-        },
-    )
-}
-if (!isFirstInstance) {
-    exitApplication()
-    return@application
-}
-
-LaunchedEffect(restoreRequestCount) {
-    if (restoreRequestCount > 0) {
-        nucleusWindow.toFront()
-        nucleusWindow.requestFocus()
-    }
-}
```

To opt out — for editor-style apps that allow multiple concurrent instances — pass `enableSingleInstance = false`:

```kotlin
nucleusApplication(args, enableSingleInstance = false) { … }
```

---

## Step 6 — AOT Training Uses `aotTraining { }`

The manual thread-sleep-then-exit pattern is replaced by a one-liner.

```diff
-if (AotRuntime.isTraining()) {
-    Thread({
-        Thread.sleep(45_000)
-        kotlin.system.exitProcess(0)
-    }, "aot-timer").apply { isDaemon = false }.start()
-
-    preloadNavigationScreens()
-    preloadFontsAndImages()
-}

 nucleusApplication(args) {
+    aotTraining(duration = 45.seconds)
+
+    if (isAotTraining) {
+        preloadNavigationScreens()
+        preloadFontsAndImages()
+    }
     …
 }
```

`aotTraining` is a no-op outside training mode and idempotent if called multiple times. The scope also exposes `aotMode`, `isAotTraining`, and `isAotRuntime` properties for branching on the JVM's current AOT state. See [AOT Cache](runtime/aot-cache.md) for the full story.

---

## Step 7 — Deep Links Use `onDeepLink { }`

The scope's `onDeepLink { }` replaces direct calls to `DeepLinkHandler.register(args, …)`. It picks the right code path for the active backend (AWT or Tao) and parses the CLI [args] you handed to `nucleusApplication`.

```diff
-DeepLinkHandler.register(args) { uri -> handleDeepLink(uri) }
+onDeepLink { uri -> handleDeepLink(uri) }
```

URIs delivered before the handler is registered (cold-start macOS Apple Events on the Tao backend, second-instance relays before Compose mounts) are buffered and replayed.

`DeepLinkHandler` is still public and useful for low-level work — `onDeepLink` is the convenience front door.

---

## Step 8 — Drop Explicit `GraalVmInitializer.initialize()`

Anywhere you called it manually, remove it:

```diff
-GraalVmInitializer.initialize()
-application { … }
+nucleusApplication(args) { … }
```

`nucleusApplication` runs it for you as the very first bootstrap step. Calling it again is harmless but unnecessary.

---

## Final Result

A typical `main()` after the migration:

```kotlin
fun main(args: Array<String>) = nucleusApplication(args) {
    // Prime platform side-effects on the first composition (runs only on primary instance).
    remember {
        AutoLaunch.wasStartedAtLogin(args)
        if (Platform.Current == Platform.Windows) {
            WindowsJumpListManager.setProcessAppId()
        }
        true
    }

    aotTraining(duration = 45.seconds)

    onDeepLink { uri -> handleDeepLink(uri) }

    MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
        App()
    }
}
```

No ordered init list. No `SingleInstanceManager` plumbing. No restore counter and `LaunchedEffect` to bring the window back. Each platform integration (jump lists, dock menus, Unity launcher, notifications, …) keeps its native shape — what changed is the boilerplate around them.

---

## Troubleshooting

**My imports won't resolve after the rename.**
Search the project for `io.github.kdroidfilter.nucleus` — anything left over is a stale import. The replacement is always `dev.nucleusframework`.

**`nucleusApplication` is unresolved.**
Add `implementation("dev.nucleusframework:nucleus.nucleus-application:2.0.0")` to the module's dependencies. The runtime split moved `nucleusApplication` out of `core-runtime`.

**My window doesn't come back when I click the dock icon / taskbar of a second launch.**
That auto-behavior is wired inside `DecoratedWindow`. If you use plain Compose Desktop `Window`, you keep the 1.x manual pattern. Switch to `DecoratedWindow` (or one of the styled variants) to get it for free.

**I want multiple concurrent instances.**
Pass `enableSingleInstance = false` to `nucleusApplication`. The lock is skipped entirely.

**`Unresolved reference 'JewelDecoratedWindow'` even though the import is correct.**
The composable became an extension on `NucleusApplicationScope` in 2.0. Wrap-style helper composables must propagate the receiver — see [Step 4](#step-4--replace-window---with-decoratedwindow--).

**`NoClassDefFoundError: dev/nucleusframework/window/DecoratedDialogKt` on the Tao backend.**
You're calling the AWT-only `JewelDecoratedDialog` (no receiver) under `NucleusBackend.Tao`. Switch the host composable to an extension on `NucleusApplicationScope` so the call resolves to `NucleusApplicationScope.JewelDecoratedDialog`, which dispatches to the right backend — see [Step 4 → Dialogs](#dialogs-follow-the-same-rule).

**`IllegalStateException: No TextStyle provided` / `No IsDarkTheme provided` on Tao but not on AWT.**
Older Tao builds (pre-`v2.0.0-alpha-202605131225`) did not bridge `CompositionLocals` across the per-window `ComposeScene`. Bump to `2.0.0-alpha-202605131305` or newer and remove any duplicate `IntUiTheme { … }` you added inside the window/dialog content lambda — a single wrap in the outer scope is enough.

**`Could not find org.jetbrains.jewel:jewel-foundation:0.37.…`**
The IntelliJ snapshots repo is missing. Add `maven("https://www.jetbrains.com/intellij-repository/snapshots")` to `dependencyResolutionManagement.repositories` — see [Prerequisites](#prerequisites).

**`Dependency resolution is looking for a library compatible with JVM runtime version 11`.**
Bump the toolchain — Nucleus 2.0 requires JDK 25 for the Jewel stack and JDK 17 for `nucleus-application`. See [Prerequisites](#prerequisites).
