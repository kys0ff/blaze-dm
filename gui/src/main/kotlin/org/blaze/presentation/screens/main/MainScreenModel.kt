package org.blaze.presentation.screens.main

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.blaze.domain.usecase.GetDownloadsUseCase
import org.blaze.presentation.application.AppCommand
import org.blaze.presentation.application.AppCommandBus
import org.blaze.presentation.screens.extensions.ExtensionsWindowModel
import org.blaze.presentation.screens.serverlog.ServerLogWindowModel

/**
 * The application shell's screen model. It owns only the window-level, ephemeral UI the chrome sits
 * on — the About dialog, the background-tasks popup, the stripe's selected tool window and the running
 * transfers the status bar counts — and folds the app-wide [AppCommand]s that belong to the shell
 * (About, Server Logs) into that state.
 *
 * It deliberately does not own the downloads list, the Details pane or each dockable window's layout:
 * those stay with [DownloadsScreenModel], [ServerLogWindowModel] and [ExtensionsWindowModel], which the
 * view still drives directly. The shell only *projects* what it needs (running transfers, whether the
 * Extensions window is up) and *delegates* the raise/lower, so there is a single source of truth.
 */
class MainScreenModel(
    commandBus: AppCommandBus,
    getDownloads: GetDownloadsUseCase,
    private val serverLogWindowModel: ServerLogWindowModel,
    private val extensionsWindowModel: ExtensionsWindowModel,
) : ScreenModel {

    private val _ui = MutableStateFlow(MainState())

    val state: StateFlow<MainState> = combine(
        _ui,
        getDownloads(),
        extensionsWindowModel.state,
    ) { ui, downloads, extensions ->
        ui.copy(
            running = runningDownloads(downloads),
            activeToolWindow = activeToolWindowFor(extensions.visible),
        )
    }.stateIn(screenModelScope, SharingStarted.Eagerly, _ui.value)

    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        // The title-bar chrome raises app-wide commands it cannot serve itself. About lives on this
        // screen (its dialog state is here), and the Server Logs window is toggled through its own
        // model — so the shell never reaches into that window's state. The downloads-scoped commands
        // (toggle-details, select-all) are folded by the downloads model, and Settings is a shell-
        // owned modal; everything else is ignored here.
        screenModelScope.launch {
            commandBus.commands.collect { command ->
                when (command) {
                    // Fold the About command into the very event the dialog's own trigger uses, so both
                    // paths reduce the same state; the Server Logs window is toggled through its model.
                    AppCommand.ShowAbout -> onEvent(MainEvent.ShowAbout)
                    AppCommand.ShowServerLog -> serverLogWindowModel.toggleVisible()
                    else -> Unit
                }
            }
        }
    }

    fun onEvent(event: MainEvent) {
        // The shell's own dialog/popup state reduces through the pure [reduceMainUi]; the events that
        // reach into another window (a stripe selection) or out to the view (a task jump) are applied
        // here, keeping the model a thin dispatcher over state it does not itself own.
        _ui.update { reduceMainUi(it, event) }
        when (event) {
            is MainEvent.SelectToolWindow -> when (event.window) {
                // Downloads is the resting content, so picking it simply lowers the Extensions panel;
                // Extensions toggles itself, matching the stripe acting as a raise/lower switch.
                MainToolWindowId.DOWNLOADS -> extensionsWindowModel.setVisible(false)
                MainToolWindowId.EXTENSIONS -> extensionsWindowModel.toggleVisible()
            }

            // The jump is carried by the view's SearchController, so it leaves as a one-shot effect.
            is MainEvent.FocusDownload -> screenModelScope.launch {
                _effects.emit(MainEffect.JumpToDownload(event.id))
            }

            MainEvent.ShowAbout, MainEvent.DismissAbout,
            MainEvent.ToggleTasksPopup, MainEvent.DismissTasksPopup -> Unit
        }
    }
}
