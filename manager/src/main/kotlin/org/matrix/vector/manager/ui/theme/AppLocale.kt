package org.matrix.vector.manager.ui.theme

import androidx.compose.runtime.Composable

/**
 * The manager follows the system language.
 *
 * There used to be an in-app picker here, backed by a controller that pushed a chosen
 * configuration down the tree so every string re-resolved without restarting the activity.
 * Following the system needs none of that: Compose already resolves strings against the system
 * configuration, and the language a user wants is the one their phone is set to.
 *
 * Both wrappers stay, as pass-throughs, because the sheets and dialogs that used to be re-localised
 * one by one still call them -- and a dialog that draws in its own window would otherwise be the
 * one place that needed special handling again if an override ever came back.
 */
@Composable
fun LocalizedContent(content: @Composable () -> Unit) = content()

/** The same, for anything drawn in its own window -- a sheet, a dialog, a dropdown. */
@Composable
fun LocalizedOverlay(content: @Composable () -> Unit) = content()
