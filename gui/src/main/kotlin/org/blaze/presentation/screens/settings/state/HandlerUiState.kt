package org.blaze.presentation.screens.settings.state

import org.blaze.resolver.core.LoadedResolver

/**
 * UI-facing snapshot of a registered link handler.
 *
 * [resolver] is the underlying plugin record so the settings screen can render its icon
 * through [org.blaze.presentation.components.ExtensionIcon] without doing a second lookup
 * against [org.blaze.resolver.core.LinkResolverRegistry.handlers].
 */
data class HandlerUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val isPlugin: Boolean,
    val resolver: LoadedResolver
)
