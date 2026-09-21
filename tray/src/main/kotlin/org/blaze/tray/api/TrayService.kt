package org.blaze.tray.api

import java.awt.image.BufferedImage

/**
 * Contract for a system-tray / status-bar presence. Implementations must degrade
 * gracefully: when the desktop environment offers no tray, [isSupported] is false
 * and [install] is a no-op, so callers never need platform checks of their own.
 *
 * The library carries no application knowledge: identity, icon artwork and the
 * menu are all supplied by the embedding app through [TrayConfig].
 */
interface TrayService {
    /** Whether this platform/environment can actually show a tray icon. */
    val isSupported: Boolean

    /**
     * (Re)install the tray icon from [config]. Installing over an existing icon
     * replaces it. No-op when unsupported.
     */
    fun install(config: TrayConfig)

    /**
     * Keep every [TrayMenuItem.WindowToggle] label in sync with the window's
     * visibility (a hidden window offers its hidden label, a visible one its
     * shown label).
     */
    fun setWindowVisible(visible: Boolean)

    /** Remove the tray icon; safe to call when nothing was ever installed. */
    fun dispose()
}

/**
 * Everything the tray needs to know about the host application: who it is
 * ([id]/[title]), which artwork to render ([icon]) and what its context menu
 * contains ([menu]). Callbacks in [menu] are invoked on the UI thread and must
 * not block; long-running work should be dispatched elsewhere by the caller.
 */
data class TrayConfig(
    /** Stable application identifier (surfaced as the SNI `Id` on Linux). */
    val id: String,
    /** Human-readable application name (tooltip / tray title). */
    val title: String,
    /** Supplies the tray artwork scaled to the size the platform asks for. */
    val icon: TrayIconProvider,
    /** Context-menu contents, top to bottom. */
    val menu: List<TrayMenuItem>,
)

/**
 * Provides the tray icon at a concrete pixel size; platforms vary (16px classic
 * trays, 22px Linux, 32px HiDPI), so the caller decides how to source/scale it.
 */
fun interface TrayIconProvider {
    fun create(size: Int): BufferedImage
}

/** One row of the tray context menu. */
sealed interface TrayMenuItem {
    /** A plain clickable entry. */
    data class Item(
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : TrayMenuItem

    /** A non-interactive dividing line. */
    data object Separator : TrayMenuItem

    /**
     * An entry whose caption flips between [hiddenLabel] and [shownLabel] as
     * [TrayService.setWindowVisible] reports the window's state; double-clicking
     * the tray icon triggers the same callback where the platform supports it.
     */
    data class WindowToggle(
        val hiddenLabel: String,
        val shownLabel: String,
        val onClick: () -> Unit,
    ) : TrayMenuItem {
        fun labelFor(windowVisible: Boolean): String =
            if (windowVisible) shownLabel else hiddenLabel
    }
}
