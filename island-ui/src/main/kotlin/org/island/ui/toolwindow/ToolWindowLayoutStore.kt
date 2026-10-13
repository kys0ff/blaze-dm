package org.island.ui.toolwindow

/**
 * The persistence seam every dockable tool window shares: it turns a pane's [ToolWindowLayout] to and
 * from wherever it is stored. Injecting this instead of letting each window model reach into the app's
 * settings shape keeps the layout rules in one place and leaves a model holding a single, meaningful
 * collaborator rather than the whole app-settings surface.
 */
interface ToolWindowLayoutStore {
    /** The layout to seed the pane with, read from persisted settings (falling back to defaults). */
    fun load(): ToolWindowLayout

    /** Write the whole layout back. */
    suspend fun save(layout: ToolWindowLayout)
}
