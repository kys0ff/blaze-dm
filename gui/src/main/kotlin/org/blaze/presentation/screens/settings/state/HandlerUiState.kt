package org.blaze.presentation.screens.settings.state

/** UI-facing snapshot of a registered link handler. */
data class HandlerUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val isPlugin: Boolean
)