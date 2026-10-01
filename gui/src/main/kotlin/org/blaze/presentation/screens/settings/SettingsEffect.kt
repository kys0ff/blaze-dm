package org.blaze.presentation.screens.settings

/** One-shot commands the screen reacts to (navigation, dialogs) rather than stateful UI data. */
sealed interface SettingsEffect {
    /** Close the settings screen (used by OK and Cancel once their work is done). */
    data object Close : SettingsEffect

    /** A line the screen shows as a balloon: an install that failed, or one the consent refused. */
    data class Message(val text: String) : SettingsEffect
}
