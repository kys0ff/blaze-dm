package org.blaze.presentation.screens.settings

/** One-shot commands the screen reacts to (navigation, dialogs) rather than stateful UI data. */
sealed interface SettingsEffect {
    /** Close the settings screen (used by OK and Cancel once their work is done). */
    data object Close : SettingsEffect
}
