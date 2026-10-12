package org.blaze.presentation.screens.extensions

import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.components.panel.ExtensionsLayoutStore
import org.blaze.presentation.components.panel.MIN_SPLIT_FRACTION
import org.blaze.presentation.components.panel.ToolWindowLayoutAction
import org.blaze.presentation.components.panel.ToolWindowMoveTo
import org.blaze.presentation.components.panel.ToolWindowResize
import org.blaze.presentation.components.panel.ToolWindowScreenModel
import org.blaze.presentation.components.panel.ToolWindowViewMode
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LinkResolverSettingsRepository
import org.blaze.resolver.core.LoadedResolver
import org.blaze.resolver.core.ResolverSettings
import org.blaze.resolver.core.ResolverSource
import org.blaze.security.ConsentService
import org.blaze.security.ConsentSubjectKind
import org.blaze.service.security.ConsentDecision
import java.nio.file.Path

/**
 * One handler as the Extensions panel renders it: identity for the row, the live enable/priority
 * state pulled from the repository, the option descriptors the config gear edits, and the icon bytes
 * preloaded by the model so the row draws its icon without a registry lookup of its own.
 *
 * [resolver] is still carried for callers that need the underlying handler; the panel itself only
 * reads [iconBytes].
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
    val iconBytes: ByteArray? = null,
)

/**
 * Folds the handler list and the persisted settings into the rows the panel renders. Kept as a
 * pure function (the option descriptors arrive through [descriptorsOf] and the icon bytes through
 * [iconBytesOf], so the caller supplies both from the registry) so the state mapping is
 * unit-testable without a coroutine scope.
 */
internal fun buildExtensionRows(
    handlers: List<LoadedResolver>,
    settings: ResolverSettings,
    iconBytesOf: (LoadedResolver) -> ByteArray? = { null },
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
        iconBytes = iconBytesOf(loaded),
    )
}

/**
 * Everything the Extensions tool window presents: its docked layout (inherited from
 * [ToolWindowScreenModel], persisted through [ExtensionsLayoutStore]) folded together with the live
 * handler rows. The rows are a pure projection of the registry's handler stream and the resolver
 * settings, so a toggle or option edit made in the panel is reflected the moment the repository
 * republishes — there is no draft to commit.
 */
data class ExtensionsWindowState(
    val visible: Boolean,
    val viewMode: ToolWindowViewMode,
    val moveTo: ToolWindowMoveTo,
    /** Fraction of the split given to the *content* (everything but the extensions window). */
    val splitFraction: Float,
    val innerSplitFraction: Float = 0.50f,
    val floatingOffsetX: Float = 120f,
    val floatingOffsetY: Float = 120f,
    val floatingWidthDp: Float = 420f,
    val floatingHeightDp: Float = 480f,
    val rows: List<ExtensionUiRow> = emptyList(),
)

/**
 * The live Extensions tool-window model. Its docked layout is owned entirely by [ToolWindowScreenModel]
 * and driven through [onLayoutEvent]; its content folds the registry's handler stream together with the
 * resolver settings, so every control — enable/disable, install/remove, per-handler options and ordering
 * priority — takes effect immediately, the way an IDE tool window edits its own state rather than a
 * modal draft. Global preferences (always-ask, extensions folder, request timeout) intentionally stay in
 * the Settings EXTENSIONS category.
 *
 * Torrent/magnet handling is deliberately absent: resolvers only claim HTTP(S) pages.
 */
class ExtensionsWindowModel(
    appSettingsRepository: AppSettingsRepository,
    private val registry: LinkResolverRegistry,
    private val settingsRepository: LinkResolverSettingsRepository,
    private val consentService: ConsentService,
) : ToolWindowScreenModel(ExtensionsLayoutStore(appSettingsRepository)) {

    /** The live rows: registry handlers folded with the persisted resolver settings. */
    private val rows: StateFlow<List<ExtensionUiRow>> =
        combine(registry.handlers, settingsRepository.settings) { handlers, settings ->
            buildExtensionRows(handlers, settings, iconBytesOf = { registry.loadIconBytes(it) }) {
                registry.optionDescriptors(it)
            }
        }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The window's docked layout folded with the current rows, so a single collect drives the whole pane. */
    val state: StateFlow<ExtensionsWindowState> =
        combine(layout, rows) { l, currentRows ->
            ExtensionsWindowState(
                visible = l.visible,
                viewMode = l.viewMode,
                moveTo = l.moveTo,
                splitFraction = l.splitFraction,
                innerSplitFraction = l.innerSplitFraction,
                floatingOffsetX = l.floatingOffsetX,
                floatingOffsetY = l.floatingOffsetY,
                floatingWidthDp = l.floatingWidthDp,
                floatingHeightDp = l.floatingHeightDp,
                rows = currentRows,
            )
        }.stateIn(screenModelScope, SharingStarted.Eagerly, initialState())

    private fun initialState(): ExtensionsWindowState = layout.value.let { l ->
        ExtensionsWindowState(
            visible = l.visible,
            viewMode = l.viewMode,
            moveTo = l.moveTo,
            splitFraction = l.splitFraction,
            innerSplitFraction = l.innerSplitFraction,
            floatingOffsetX = l.floatingOffsetX,
            floatingOffsetY = l.floatingOffsetY,
            floatingWidthDp = l.floatingWidthDp,
            floatingHeightDp = l.floatingHeightDp,
        )
    }

    private var busy = false

    // ── content events ──────────────────────────────────────────────────────────

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

            // Consent gate first: the jar's code may only run after the user acknowledged what
            // installing a third-party extension means; a refusal copies nothing into the directory.
            is ExtensionsEvent.Install -> screenModelScope.launch {
                val jar = Path.of(event.jarPath)
                if (consentService.awaitExtensionApproval(ConsentSubjectKind.LINK_RESOLVER, jar) != ConsentDecision.DENY) {
                    registry.install(jar)
                }
            }

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

    /**
     * A Resize preset shifts the content's share of the split (the window grows as content shrinks);
     * MAXIMIZE claims everything down to the floor. Composed from the shared [ToolWindowLayoutAction] so
     * it reuses the reducer's clamp rather than a bespoke write.
     */
    fun resize(preset: ToolWindowResize) {
        val fraction = when (preset) {
            ToolWindowResize.STRETCH_TOP, ToolWindowResize.STRETCH_LEFT -> currentLayout().splitFraction + RESIZE_STEP
            ToolWindowResize.STRETCH_BOTTOM, ToolWindowResize.STRETCH_RIGHT -> currentLayout().splitFraction - RESIZE_STEP
            ToolWindowResize.MAXIMIZE -> MIN_SPLIT_FRACTION
        }
        onLayoutEvent(ToolWindowLayoutAction.SetSplitFraction(fraction))
    }

    /**
     * The persisted option values for a handler, read through the registry so the config dialog opens on
     * current state — the panel never resolves the registry itself.
     */
    fun handlerOptionValues(id: String): Map<String, String> = registry.handlerSettings(id).values
}
