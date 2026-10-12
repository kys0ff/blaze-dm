package org.blaze.presentation.screens.extensions

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.data.AppSettingsRepository
import org.blaze.presentation.screens.downloads.DetailsMoveTo
import org.blaze.presentation.screens.downloads.DetailsResize
import org.blaze.presentation.screens.downloads.DetailsViewMode
import org.blaze.presentation.screens.downloads.contentFractionForViewMode
import org.blaze.presentation.screens.downloads.detailsMoveToFromName
import org.blaze.presentation.screens.downloads.detailsViewModeFromName
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
import kotlin.time.Duration.Companion.milliseconds

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
    settings: ResolverSettings,
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

/**
 * Everything the Extensions tool window presents and persists: its visibility and docked layout (the
 * same view-mode / move-to / resize model the Details and Server Logs panes use) folded together with
 * the live handler rows. The layout seeds from [AppSettingsRepository] on construction and is written
 * back, debounced, on change; the rows are a pure projection of the registry's handler stream and the
 * resolver settings, so a toggle or option edit made in the panel is reflected the moment the repository
 * republishes — there is no draft to commit.
 */
data class ExtensionsWindowState(
    val visible: Boolean,
    val viewMode: DetailsViewMode,
    val moveTo: DetailsMoveTo,
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
 * The live Extensions tool-window model. Its content folds the registry's handler stream together with
 * the resolver settings, so every control — enable/disable, install/remove, per-handler options and
 * ordering priority — takes effect immediately, the way an IDE tool window edits its own state rather
 * than a modal draft. Its layout (visibility, docked position, split size) is persisted exactly like the
 * Server Logs window's. Global preferences (always-ask, extensions folder, request timeout) intentionally
 * stay in the Settings EXTENSIONS category.
 *
 * Torrent/magnet handling is deliberately absent: resolvers only claim HTTP(S) pages.
 */
class ExtensionsWindowModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val registry: LinkResolverRegistry,
    private val settingsRepository: LinkResolverSettingsRepository,
    private val consentService: ConsentService,
) : ScreenModel {

    /** The live rows: registry handlers folded with the persisted resolver settings. */
    private val rows: StateFlow<List<ExtensionUiRow>> =
        combine(registry.handlers, settingsRepository.settings) { handlers, settings ->
            buildExtensionRows(handlers, settings) { registry.optionDescriptors(it) }
        }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(
        appSettingsRepository.settings.value.let { saved ->
            ExtensionsWindowState(
                visible = saved.extensionsVisible,
                viewMode = detailsViewModeFromName(saved.extensionsViewMode),
                moveTo = detailsMoveToFromName(saved.extensionsMoveTo),
                splitFraction = saved.extensionsSplitFraction,
                innerSplitFraction = saved.extensionsInnerSplitFraction,
                floatingOffsetX = saved.extensionsFloatingOffsetX,
                floatingOffsetY = saved.extensionsFloatingOffsetY,
                floatingWidthDp = saved.extensionsFloatingWidthDp,
                floatingHeightDp = saved.extensionsFloatingHeightDp,
            )
        }
    )

    /** The window's layout folded with the current rows, so a single collect drives the whole pane. */
    val state: StateFlow<ExtensionsWindowState> =
        combine(_state, rows) { window, currentRows -> window.copy(rows = currentRows) }
            .stateIn(screenModelScope, SharingStarted.Eagerly, _state.value)

    private var busy = false
    private var persistJob: Job? = null

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

    // ── window layout ───────────────────────────────────────────────────────────

    /** Raises or lowers the window; the stripe's Extensions toggle drives this. */
    fun toggleVisible() = setVisible(!_state.value.visible)

    fun setVisible(visible: Boolean) {
        if (_state.value.visible == visible) return
        _state.update { it.copy(visible = visible) }
        persist()
    }

    fun setViewMode(mode: DetailsViewMode) {
        _state.update { s ->
            // WINDOW is the one mode that re-negotiates the split, claiming the larger share the mode
            // promises; the other modes leave the user's dragged size untouched. Detaching clears the
            // placement, asking the layer for the corner of the edge the window leaves, while a hop
            // between the two floating modes keeps the spot it already sits in.
            val detaching = mode.isFloatingWindow && !s.viewMode.isFloatingWindow
            s.copy(
                viewMode = mode,
                splitFraction = contentFractionForViewMode(mode, s.splitFraction),
                floatingOffsetX = if (detaching) 0f else s.floatingOffsetX,
                floatingOffsetY = if (detaching) 0f else s.floatingOffsetY,
            )
        }
        persist()
    }

    fun setMoveTo(position: DetailsMoveTo) {
        _state.update { s ->
            // A docked window only changes which split it sits in. A free window *is* its edge, so the
            // same choice moves it to that corner by clearing the placement for the layer to recompute.
            if (s.viewMode.isFloatingWindow) {
                s.copy(moveTo = position, floatingOffsetX = 0f, floatingOffsetY = 0f)
            } else {
                s.copy(moveTo = position)
            }
        }
        persist()
    }

    fun setSplitFraction(fraction: Float) {
        _state.update { it.copy(splitFraction = fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)) }
        persist()
    }

    fun setInnerSplitFraction(fraction: Float) {
        _state.update { it.copy(innerSplitFraction = fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)) }
        persist()
    }

    fun setFloatingBounds(x: Float, y: Float, widthDp: Float, heightDp: Float) {
        _state.update {
            it.copy(
                floatingOffsetX = x,
                floatingOffsetY = y,
                floatingWidthDp = widthDp,
                floatingHeightDp = heightDp,
            )
        }
        persist()
    }

    /** A Resize preset shifts the content's share of the split; the window grows as content shrinks. */
    fun resize(preset: DetailsResize) {
        val fraction = when (preset) {
            DetailsResize.STRETCH_TOP, DetailsResize.STRETCH_LEFT -> _state.value.splitFraction + RESIZE_STEP
            DetailsResize.STRETCH_BOTTOM, DetailsResize.STRETCH_RIGHT -> _state.value.splitFraction - RESIZE_STEP
            DetailsResize.MAXIMIZE -> MIN_FRACTION
        }.coerceIn(MIN_FRACTION, MAX_FRACTION)
        _state.update { it.copy(splitFraction = fraction) }
        persist()
    }

    // ── persistence ──────────────────────────────────────────────────────────────

    private fun persist() {
        persistJob?.cancel()
        persistJob = screenModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS.milliseconds)
            val s = _state.value
            appSettingsRepository.updateSettings {
                it.copy(
                    extensionsVisible = s.visible,
                    extensionsSplitFraction = s.splitFraction,
                    extensionsInnerSplitFraction = s.innerSplitFraction,
                    extensionsFloatingOffsetX = s.floatingOffsetX,
                    extensionsFloatingOffsetY = s.floatingOffsetY,
                    extensionsFloatingWidthDp = s.floatingWidthDp,
                    extensionsFloatingHeightDp = s.floatingHeightDp,
                    extensionsViewMode = s.viewMode.name,
                    extensionsMoveTo = s.moveTo.name,
                )
            }
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 300L
        const val MIN_FRACTION = 0.15f
        const val MAX_FRACTION = 0.85f
        const val RESIZE_STEP = 0.15f
    }
}
