package org.blaze.presentation.screens.extensions

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.LoadedResolver
import org.blaze.resolver.core.ResolverSource
import java.nio.file.Path

/**
 * One handler as the Extensions panel renders it: identity for the row, the live enable/priority
 * state pulled from the repository, and the option descriptors the config gear edits.
 *
 * [resolver] is carried so the row can draw its icon through
 * [org.blaze.presentation.components.ExtensionIcon] without a second registry lookup.
 */
data class ExtensionUiRow(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val isPlugin: Boolean,
    val priority: Int,
    val options: List<ResolverOption>,
    val resolver: LoadedResolver,
)

/**
 * Folds the handler list and the persisted settings into the rows the panel renders. Kept as a
 * pure function (the option descriptors arrive through [descriptorsOf], so the caller can supply
 * them from the registry) so the state mapping is unit-testable without a coroutine scope.
 */
internal fun buildExtensionRows(
    handlers: List<LoadedResolver>,
    settings: org.blaze.resolver.core.ResolverSettings,
    descriptorsOf: (LoadedResolver) -> List<ResolverOption>,
): List<ExtensionUiRow> = handlers.map { loaded ->
    ExtensionUiRow(
        id = loaded.resolver.id,
        displayName = loaded.resolver.displayName,
        description = loaded.resolver.description,
        enabled = settings.isEnabled(loaded.resolver.id),
        isPlugin = loaded.source == ResolverSource.PLUGIN,
        priority = settings.slice(loaded.resolver.id).priority,
        options = descriptorsOf(loaded),
        resolver = loaded,
    )
}

data class ExtensionsPanelState(
    val rows: List<ExtensionUiRow> = emptyList(),
    val busy: Boolean = false,
)

/**
 * The live Extensions tool-window model. Its state folds the registry's handler stream together
 * with the resolver settings, so a toggle or option edit made here is reflected the moment the
 * repository republishes — there is no draft to commit.
 *
 * Torrent/magnet handling is deliberately absent: resolvers only claim HTTP(S) pages.
 */
class ExtensionsScreenModel(
    private val registry: LinkResolverRegistry,
    private val settingsRepository: LinkResolverSettingsRepository,
) : ScreenModel {

    val state: StateFlow<ExtensionsPanelState> =
        combine(registry.handlers, settingsRepository.settings) { handlers, settings ->
            ExtensionsPanelState(rows = buildExtensionRows(handlers, settings) { registry.optionDescriptors(it) })
        }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), ExtensionsPanelState())

    private var busy = false

    fun onEvent(event: ExtensionsEvent) {
        when (event) {
            is ExtensionsEvent.Toggle -> screenModelScope.launch {
                registry.setEnabled(event.id, event.enabled)
            }

            is ExtensionsEvent.SaveOptions -> screenModelScope.launch {
                settingsRepository.setOptionValues(event.id, event.values)
            }

            is ExtensionsEvent.SetPriority -> screenModelScope.launch {
                settingsRepository.setPriority(event.id, event.priority)
            }

            is ExtensionsEvent.Install -> launchRescan { registry.install(Path.of(event.jarPath)) }

            is ExtensionsEvent.Uninstall -> launchRescan { registry.uninstall(event.id) }

            ExtensionsEvent.Reload -> screenModelScope.launch { registry.reload() }
        }
    }

    /**
     * Run a directory-mutating action (install / uninstall), then rescan. Those calls already
     * reload internally, so this just guards against a user firing two while one is in flight —
     * the busy flag keeps the header buttons from double-submitting during a slower disk op.
     */
    private fun launchRescan(block: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        screenModelScope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
}
