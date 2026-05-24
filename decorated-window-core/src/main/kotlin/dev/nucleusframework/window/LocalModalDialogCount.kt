package dev.nucleusframework.window

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * Counts the number of modal dialogs currently open above a decorated window.
 *
 * Provided (with a fresh [MutableState]`<Int>`) by each decorated window in
 * its scene setup, so overlapping windows each have their own counter.
 *
 * When a [DecoratedDialog] opens, it reads this local from the bridged outer
 * context and increments it; the parent window reacts by rendering a
 * full-screen transparent input blocker that consumes all pointer events so
 * the content underneath is not interactive while the dialog is visible.
 * The counter decrements on dialog dispose, restoring interactivity.
 */
val LocalModalDialogCount =
    compositionLocalOf<MutableState<Int>> {
        mutableStateOf(0)
    }
