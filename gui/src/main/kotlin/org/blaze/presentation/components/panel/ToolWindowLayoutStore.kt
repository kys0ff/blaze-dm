package org.blaze.presentation.components.panel

import org.blaze.data.AppSettingsRepository
import org.island.ui.toolwindow.ToolWindowLayout
import org.island.ui.toolwindow.ToolWindowLayoutStore
import org.island.ui.toolwindow.toolWindowMoveToFromName
import org.island.ui.toolwindow.toolWindowViewModeFromName

/**
 * Blaze's concrete [ToolWindowLayoutStore]s: each dockable tool window's layout persisted in its own
 * `xxx*` fields of [AppSettings] through the app-settings repository. The layout *rules* live in the
 * island layer; these are the storage adapters the screen models are constructed with (Koin wiring in
 * `di/ScreenModelModule`).
 */

/** The Details pane's layout, persisted in the `details*` fields of [AppSettings]. */
class DetailsLayoutStore(private val repository: AppSettingsRepository) : ToolWindowLayoutStore {

    override fun load(): ToolWindowLayout = repository.settings.value.let { s ->
        ToolWindowLayout(
            visible = s.detailsPaneVisible,
            viewMode = toolWindowViewModeFromName(s.detailsViewMode),
            moveTo = toolWindowMoveToFromName(s.detailsMoveTo),
            splitFraction = s.detailsSplitFraction,
            innerSplitFraction = s.detailsInnerSplitFraction,
            floatingOffsetX = s.detailsFloatingOffsetX,
            floatingOffsetY = s.detailsFloatingOffsetY,
            floatingWidthDp = s.detailsFloatingWidthDp,
            floatingHeightDp = s.detailsFloatingHeightDp,
        )
    }

    override suspend fun save(layout: ToolWindowLayout) {
        repository.updateSettings {
            it.copy(
                detailsPaneVisible = layout.visible,
                detailsViewMode = layout.viewMode.name,
                detailsMoveTo = layout.moveTo.name,
                detailsSplitFraction = layout.splitFraction,
                detailsInnerSplitFraction = layout.innerSplitFraction,
                detailsFloatingOffsetX = layout.floatingOffsetX,
                detailsFloatingOffsetY = layout.floatingOffsetY,
                detailsFloatingWidthDp = layout.floatingWidthDp,
                detailsFloatingHeightDp = layout.floatingHeightDp,
            )
        }
    }

    /** The pane's "group tabs into one view" flag lives beside the layout but is Details-only. */
    fun loadGroupTabs(): Boolean = repository.settings.value.groupDetailsTabs

    suspend fun saveGroupTabs(enabled: Boolean) {
        repository.updateSettings { it.copy(groupDetailsTabs = enabled) }
    }
}

/** The Server Logs window's layout, persisted in the `serverLog*` fields of [AppSettings]. */
class ServerLogLayoutStore(private val repository: AppSettingsRepository) : ToolWindowLayoutStore {

    override fun load(): ToolWindowLayout = repository.settings.value.let { s ->
        ToolWindowLayout(
            visible = s.serverLogVisible,
            viewMode = toolWindowViewModeFromName(s.serverLogViewMode),
            moveTo = toolWindowMoveToFromName(s.serverLogMoveTo),
            splitFraction = s.serverLogSplitFraction,
            innerSplitFraction = s.serverLogInnerSplitFraction,
            floatingOffsetX = s.serverLogFloatingOffsetX,
            floatingOffsetY = s.serverLogFloatingOffsetY,
            floatingWidthDp = s.serverLogFloatingWidthDp,
            floatingHeightDp = s.serverLogFloatingHeightDp,
        )
    }

    override suspend fun save(layout: ToolWindowLayout) {
        repository.updateSettings {
            it.copy(
                serverLogVisible = layout.visible,
                serverLogViewMode = layout.viewMode.name,
                serverLogMoveTo = layout.moveTo.name,
                serverLogSplitFraction = layout.splitFraction,
                serverLogInnerSplitFraction = layout.innerSplitFraction,
                serverLogFloatingOffsetX = layout.floatingOffsetX,
                serverLogFloatingOffsetY = layout.floatingOffsetY,
                serverLogFloatingWidthDp = layout.floatingWidthDp,
                serverLogFloatingHeightDp = layout.floatingHeightDp,
            )
        }
    }
}

/** The Extensions window's layout, persisted in the `extensions*` fields of [AppSettings]. */
class ExtensionsLayoutStore(private val repository: AppSettingsRepository) : ToolWindowLayoutStore {

    override fun load(): ToolWindowLayout = repository.settings.value.let { s ->
        ToolWindowLayout(
            visible = s.extensionsVisible,
            viewMode = toolWindowViewModeFromName(s.extensionsViewMode),
            moveTo = toolWindowMoveToFromName(s.extensionsMoveTo),
            splitFraction = s.extensionsSplitFraction,
            innerSplitFraction = s.extensionsInnerSplitFraction,
            floatingOffsetX = s.extensionsFloatingOffsetX,
            floatingOffsetY = s.extensionsFloatingOffsetY,
            floatingWidthDp = s.extensionsFloatingWidthDp,
            floatingHeightDp = s.extensionsFloatingHeightDp,
        )
    }

    override suspend fun save(layout: ToolWindowLayout) {
        repository.updateSettings {
            it.copy(
                extensionsVisible = layout.visible,
                extensionsViewMode = layout.viewMode.name,
                extensionsMoveTo = layout.moveTo.name,
                extensionsSplitFraction = layout.splitFraction,
                extensionsInnerSplitFraction = layout.innerSplitFraction,
                extensionsFloatingOffsetX = layout.floatingOffsetX,
                extensionsFloatingOffsetY = layout.floatingOffsetY,
                extensionsFloatingWidthDp = layout.floatingWidthDp,
                extensionsFloatingHeightDp = layout.floatingHeightDp,
            )
        }
    }
}
