package org.blaze.presentation.application

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An application-wide command the chrome (the title-bar hamburger menu, and later the tray or global
 * shortcuts) can raise without knowing which screen is currently visible.
 *
 * Each command is handled by whichever collaborator owns the capability: the downloads screen model
 * folds transport / selection / layout ones into its own events, the main screen handles navigation
 * and the About dialog, and the application shell owns exit. Keeping them as a small enum — rather
 * than reaching into a `ScreenModel` from the title bar — is what lets the menu live in the window
 * chrome while the actions stay where they belong.
 */
enum class AppCommand {
    NewDownload,
    OpenSettings,
    PauseAll,
    ResumeAll,
    ToggleDetails,
    SelectAll,
    FocusSearch,
    ShowAbout,
    Exit,
}

/**
 * A fire-and-forget bus for [AppCommand]s. Emits are buffered so a sender never blocks, and there is
 * deliberately no replay: a command is meaningful only to whatever is live right now, and a stale one
 * re-delivered to a freshly composed screen would be a surprise.
 */
class AppCommandBus {
    private val _commands = MutableSharedFlow<AppCommand>(extraBufferCapacity = 32)

    /** The stream subscribers collect to act on commands. */
    val commands: SharedFlow<AppCommand> = _commands.asSharedFlow()

    /** Raises [command]; returns false only if the buffer is saturated, which never happens in practice. */
    fun send(command: AppCommand): Boolean = _commands.tryEmit(command)
}
