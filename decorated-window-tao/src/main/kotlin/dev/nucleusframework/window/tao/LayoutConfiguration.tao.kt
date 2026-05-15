package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.nativeSystemLayoutDirection

/**
 * Root-level defaults for [androidx.compose.ui.platform.LocalDensity] and
 * [androidx.compose.ui.platform.LocalLayoutDirection], installed before any Tao window scene is
 * mounted. Required so Compose APIs that consult these locals at root composition
 * (e.g. `Font(resource = …)` via compose-resources → `rememberEnvironment` →
 * `LocalDensity.current`) keep working.
 *
 * Per-window [androidx.compose.ui.scene.ComposeScene]s override these with their own
 * platform-correct values once mounted, sourced from Tao's `nativeScaleFactor` and the
 * `SCALE_FACTOR_CHANGED` event.
 */
internal val GlobalDensity: Density = Density(1f)

internal val GlobalLayoutDirection: LayoutDirection
    get() = nativeSystemLayoutDirection()
