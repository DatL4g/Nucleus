package dev.nucleusframework.window.tao

import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Hook used by higher-level wrappers (e.g. `nucleus-application`'s dialog
 * adapter) to forward a parent composition's [CompositionLocalContext] into a
 * Tao window/dialog's own `ComposeScene` **without hijacking the scene's popup
 * routing**.
 *
 * Each Tao window opens a fresh `ComposeScene`; locals don't propagate across
 * scenes, so wrappers must bridge them manually. The naive approach —
 * `CompositionLocalProvider(currentCompositionLocalContext) { content() }`
 * inside the new scene's content — also re-provides Compose's internal
 * `LocalComposeSceneContext`/`LocalComposeScene` captured from the *parent*
 * scene, which routes every `Popup`/`DropdownMenu`/`Tooltip` layer back into
 * the parent scene. The visible symptom is popups positioned relative to the
 * parent window instead of the dialog.
 *
 * Assigning `ComposeScene.compositionLocalContext` instead is the mechanism
 * Compose Desktop itself uses (`SwingDialog`/`SwingWindow`): Compose applies it
 * **above** the scene's own `LocalComposeSceneContext` and `LocalDensity` (see
 * `RootNodeOwner.setContent`), so theme / user locals flow across the boundary
 * while the dialog scene keeps authority over layer creation and density.
 *
 * Provided by [DecoratedWindow]; the lambda sets the enclosing scene's
 * `compositionLocalContext`. `null` outside a Tao window content lambda.
 */
val LocalTaoCompositionLocalContextBridge: ProvidableCompositionLocal<((CompositionLocalContext?) -> Unit)?> =
    staticCompositionLocalOf { null }
