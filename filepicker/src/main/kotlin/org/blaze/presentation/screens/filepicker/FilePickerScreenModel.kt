package org.blaze.presentation.screens.filepicker

import androidx.compose.ui.text.input.TextFieldValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.filepicker.filesystem.FileSystemRepository
import org.blaze.presentation.screens.filepicker.model.FilePickerInputState
import org.blaze.presentation.screens.filepicker.model.FilePickerMode
import org.blaze.presentation.screens.filepicker.model.FileSystemNode
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class FilePickerScreenModel(
    private val fileSystemRepository: FileSystemRepository
) : ScreenModel {

    private val _state = MutableStateFlow(FilePickerState())
    val state: StateFlow<FilePickerState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FilePickerEffect>()
    val effects: SharedFlow<FilePickerEffect> = _effects.asSharedFlow()

    private var revealJob: Job? = null

    fun onEvent(event: FilePickerEvent) {
        when (event) {
            is FilePickerEvent.Init -> handleInit(event)
            is FilePickerEvent.PathChanged -> handlePathChanged(event)
            is FilePickerEvent.SelectPath -> handleSelectPath(event)
            is FilePickerEvent.ToggleDirectory -> handleToggleDirectory(event)
            is FilePickerEvent.ExpandDirectory -> handleExpandDirectory(event)
            is FilePickerEvent.CollapseDirectory -> handleCollapseDirectory(event)
            FilePickerEvent.MoveSelectionDown -> handleMoveSelectionDown()
            FilePickerEvent.MoveSelectionUp -> handleMoveSelectionUp()
            FilePickerEvent.MoveSelectionHome -> handleMoveSelectionHome()
            FilePickerEvent.MoveSelectionEnd -> handleMoveSelectionEnd()
            FilePickerEvent.MoveSelectionRight -> handleMoveSelectionRight()
            FilePickerEvent.MoveSelectionLeft -> handleMoveSelectionLeft()
            is FilePickerEvent.Confirm -> handleConfirm(event)
            FilePickerEvent.Dismiss -> handleDismiss()
            FilePickerEvent.GoHome -> handleGoHome()
            FilePickerEvent.Refresh -> handleRefresh()
            FilePickerEvent.ToggleHiddenFiles -> handleToggleHiddenFiles()
            FilePickerEvent.ShowNewFolder -> handleShowNewFolder()
            FilePickerEvent.DismissNewFolder -> handleDismissNewFolder()
            is FilePickerEvent.NewFolderNameChanged -> handleNewFolderNameChanged(event)
            is FilePickerEvent.CreateNewFolder -> handleCreateNewFolder(event)
        }
    }

    private fun handleInit(event: FilePickerEvent.Init) {
        val home = Path.of(System.getProperty("user.home"))
        val startPath = (event.initialPath ?: home).let {
            fileSystemRepository.nearestExisting(it)
        }.let {
            if (event.mode == FilePickerMode.Directory && !fileSystemRepository.isDirectory(it)) it.parent ?: it else it
        }

        val roots = fileSystemRepository.getRoots()
        val initialInputState = analyzeInputText(startPath.toString(), event.strings, event.mode, event.fileFilter)

        _state.value = _state.value.copy(
            mode = event.mode,
            title = event.title,
            description = event.description,
            confirmText = event.confirmText,
            fileFilter = event.fileFilter,
            roots = roots,
            pathText = TextFieldValue(startPath.toString()),
            inputState = initialInputState
        )

        revealPathInternal(startPath)
    }

    private fun handlePathChanged(event: FilePickerEvent.PathChanged) {
        val currentState = _state.value
        val inputState = analyzeInputText(event.value.text, event.strings, currentState.mode, currentState.fileFilter)

        _state.value = currentState.copy(
            pathText = event.value,
            inputState = inputState
        )

        if (inputState.exists && inputState.path != null) {
            scheduleReveal(inputState.path)
        }
    }

    private fun handleSelectPath(event: FilePickerEvent.SelectPath) {
        val currentState = _state.value
        _state.value = currentState.copy(
            selected = event.path,
            pathText = TextFieldValue(event.path.toString()),
            inputState = FilePickerInputState(event.path, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(event.path))
        )
    }

    private fun handleToggleDirectory(event: FilePickerEvent.ToggleDirectory) {
        screenModelScope.launch {
            val path = event.path
            val isExpanded = _state.value.expanded[path] == true
            if (isExpanded) {
                _state.value = _state.value.copy(
                    expanded = _state.value.expanded + (path to false)
                )
            } else {
                val updatedChildren = ensureLoaded(_state.value, path)
                _state.value = _state.value.copy(
                    children = updatedChildren,
                    expanded = _state.value.expanded + (path to true)
                )
            }
        }
    }

    private fun handleExpandDirectory(event: FilePickerEvent.ExpandDirectory) {
        screenModelScope.launch {
            val updatedChildren = ensureLoaded(_state.value, event.path)
            _state.value = _state.value.copy(
                children = updatedChildren,
                expanded = _state.value.expanded + (event.path to true)
            )
        }
    }

    private fun handleCollapseDirectory(event: FilePickerEvent.CollapseDirectory) {
        _state.value = _state.value.copy(
            expanded = _state.value.expanded + (event.path to false)
        )
    }

    private fun handleMoveSelectionDown() {
        val currentState = _state.value
        val rows = currentState.visibleRows()
        if (rows.isEmpty()) return
        val index = rows.indexOfFirst { it.node.path == currentState.selected }
        val nextIndex = (index + 1).coerceAtMost(rows.lastIndex)
        val nextPath = rows[nextIndex].node.path
        
        _state.value = currentState.copy(
            selected = nextPath,
            pathText = TextFieldValue(nextPath.toString()),
            inputState = FilePickerInputState(nextPath, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(nextPath))
        )
        screenModelScope.launch {
            _effects.emit(FilePickerEffect.ScrollToSelection(nextIndex))
        }
    }

    private fun handleMoveSelectionUp() {
        val currentState = _state.value
        val rows = currentState.visibleRows()
        if (rows.isEmpty()) return
        val index = rows.indexOfFirst { it.node.path == currentState.selected }
        val prevIndex = (index - 1).coerceAtLeast(0)
        val prevPath = rows[prevIndex].node.path
        
        _state.value = currentState.copy(
            selected = prevPath,
            pathText = TextFieldValue(prevPath.toString()),
            inputState = FilePickerInputState(prevPath, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(prevPath))
        )
        screenModelScope.launch {
            _effects.emit(FilePickerEffect.ScrollToSelection(prevIndex))
        }
    }

    private fun handleMoveSelectionHome() {
        val currentState = _state.value
        val rows = currentState.visibleRows()
        if (rows.isEmpty()) return
        val path = rows.first().node.path
        _state.value = currentState.copy(
            selected = path,
            pathText = TextFieldValue(path.toString()),
            inputState = FilePickerInputState(path, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(path))
        )
        screenModelScope.launch {
            _effects.emit(FilePickerEffect.ScrollToSelection(0))
        }
    }

    private fun handleMoveSelectionEnd() {
        val currentState = _state.value
        val rows = currentState.visibleRows()
        if (rows.isEmpty()) return
        val path = rows.last().node.path
        _state.value = currentState.copy(
            selected = path,
            pathText = TextFieldValue(path.toString()),
            inputState = FilePickerInputState(path, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(path))
        )
        screenModelScope.launch {
            _effects.emit(FilePickerEffect.ScrollToSelection(rows.lastIndex))
        }
    }

    private fun handleMoveSelectionRight() {
        val currentState = _state.value
        val rows = currentState.visibleRows()
        val index = rows.indexOfFirst { it.node.path == currentState.selected }
        val row = rows.getOrNull(index)
        if (row != null && row.node.isDirectory) {
            if (currentState.expanded[row.node.path] == true) {
                rows.getOrNull(index + 1)?.takeIf { it.depth > row.depth }?.let { nextRow ->
                    _state.value = currentState.copy(
                        selected = nextRow.node.path,
                        pathText = TextFieldValue(nextRow.node.path.toString()),
                        inputState = FilePickerInputState(nextRow.node.path, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(nextRow.node.path))
                    )
                    screenModelScope.launch {
                        _effects.emit(FilePickerEffect.ScrollToSelection(index + 1))
                    }
                }
            } else {
                screenModelScope.launch {
                    val updatedChildren = ensureLoaded(_state.value, row.node.path)
                    _state.value = _state.value.copy(
                        children = updatedChildren,
                        expanded = _state.value.expanded + (row.node.path to true)
                    )
                }
            }
        }
    }

    private fun handleMoveSelectionLeft() {
        val currentState = _state.value
        val rows = currentState.visibleRows()
        val index = rows.indexOfFirst { it.node.path == currentState.selected }
        val row = rows.getOrNull(index)
        if (row != null) {
            if (row.node.isDirectory && currentState.expanded[row.node.path] == true) {
                _state.value = currentState.copy(
                    expanded = currentState.expanded + (row.node.path to false)
                )
            } else {
                rows.subList(0, index).lastOrNull { it.depth < row.depth }?.let { parentRow ->
                    val pIndex = rows.indexOfFirst { it.node.path == parentRow.node.path }
                    _state.value = currentState.copy(
                        selected = parentRow.node.path,
                        pathText = TextFieldValue(parentRow.node.path.toString()),
                        inputState = FilePickerInputState(parentRow.node.path, exists = true, problem = null, isValid = currentState.mode == FilePickerMode.File || fileSystemRepository.isDirectory(parentRow.node.path))
                    )
                    if (pIndex >= 0) {
                        screenModelScope.launch {
                            _effects.emit(FilePickerEffect.ScrollToSelection(pIndex))
                        }
                    }
                }
            }
        }
    }

    private fun handleConfirm(event: FilePickerEvent.Confirm) {
        screenModelScope.launch {
            _effects.emit(FilePickerEffect.Picked(event.path))
        }
    }

    private fun handleDismiss() {
        screenModelScope.launch {
            _effects.emit(FilePickerEffect.Dismiss)
        }
    }

    private fun handleGoHome() {
        val home = Path.of(System.getProperty("user.home"))
        revealPathInternal(home)
    }

    private fun handleRefresh() {
        screenModelScope.launch {
            val loaded = _state.value.children.keys.toList()
            val fresh = loaded.associateWith {
                fileSystemRepository.listChildren(it, _state.value.mode == FilePickerMode.Directory, _state.value.fileFilter)
            }
            _state.value = _state.value.copy(children = fresh)
        }
    }

    private fun handleToggleHiddenFiles() {
        _state.value = _state.value.copy(showHidden = !_state.value.showHidden)
    }

    private fun handleShowNewFolder() {
        _state.value = _state.value.copy(
            showNewFolder = true,
            newFolderName = TextFieldValue(""),
            newFolderCreationError = null
        )
    }

    private fun handleDismissNewFolder() {
        _state.value = _state.value.copy(showNewFolder = false)
    }

    private fun handleNewFolderNameChanged(event: FilePickerEvent.NewFolderNameChanged) {
        _state.value = _state.value.copy(
            newFolderName = event.value,
            newFolderCreationError = null
        )
    }

    private fun handleCreateNewFolder(event: FilePickerEvent.CreateNewFolder) {
        val currentState = _state.value
        val parent = currentState.selected?.let {
            if (fileSystemRepository.isDirectory(it)) it else it.parent
        } ?: Path.of(System.getProperty("user.home"))

        val trimmedName = currentState.newFolderName.text.trim()
        val problem = validateFolderName(trimmedName, parent, event.strings)
        if (trimmedName.isEmpty() || problem != null) return

        screenModelScope.launch {
            fileSystemRepository.createDirectory(parent, trimmedName)
                .onSuccess { created ->
                    val loaded = _state.value.children.keys.toList()
                    val fresh = loaded.associateWith {
                        fileSystemRepository.listChildren(it, _state.value.mode == FilePickerMode.Directory, _state.value.fileFilter)
                    }
                    _state.value = _state.value.copy(
                        children = fresh,
                        showNewFolder = false,
                        newFolderName = TextFieldValue(""),
                        newFolderCreationError = null
                    )
                    revealPathInternal(created)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        newFolderCreationError = error.message ?: event.strings.filePicker.newFolderError
                    )
                }
        }
    }

    private fun scheduleReveal(target: Path) {
        revealJob?.cancel()
        revealJob = screenModelScope.launch {
            if (_state.value.selected != null) {
                delay(150.milliseconds)
            }
            revealPathInternal(target)
        }
    }

    private fun revealPathInternal(target: Path) {
        screenModelScope.launch {
            val currentState = _state.value
            val abs = try {
                target.toAbsolutePath().normalize()
            } catch (_: Exception) {
                return@launch
            }
            val chain = generateSequence(abs) { it.parent }.toList().asReversed()
            if (chain.isEmpty()) return@launch

            var currentChildren = currentState.children
            var currentExpanded = currentState.expanded
            var showHidden = currentState.showHidden

            val top = chain.first()
            val hasRoot = currentState.roots.any { it.path == top }
            val updatedRoots = if (!hasRoot) {
                currentState.roots + FileSystemNode(path = top, isDirectory = true, isHidden = false)
            } else {
                currentState.roots
            }

            for (dir in chain.dropLast(1)) {
                if (!currentChildren.containsKey(dir)) {
                    val newChildren = fileSystemRepository.listChildren(dir, currentState.mode == FilePickerMode.Directory, currentState.fileFilter)
                    currentChildren = currentChildren + (dir to newChildren)
                }
                currentExpanded = currentExpanded + (dir to true)
            }

            val parent = abs.parent
            val listed = parent == null || currentChildren[parent]?.any { it.path == abs } == true
            if (!listed) return@launch

            val hiddenInChain = chain.drop(1).any { p ->
                currentChildren[p.parent]?.firstOrNull { it.path == p }?.isHidden == true
            }
            if (hiddenInChain) showHidden = true

            _state.value = _state.value.copy(
                roots = updatedRoots,
                children = currentChildren,
                expanded = currentExpanded,
                showHidden = showHidden,
                selected = abs
            )

            val rows = _state.value.visibleRows()
            val index = rows.indexOfFirst { it.node.path == abs }
            if (index >= 0) {
                _effects.emit(FilePickerEffect.ScrollToSelection(index))
            }
        }
    }

    private suspend fun ensureLoaded(state: FilePickerState, dir: Path): Map<Path, List<FileSystemNode>> {
        if (state.children.containsKey(dir)) return state.children
        val newChildren = fileSystemRepository.listChildren(dir, state.mode == FilePickerMode.Directory, state.fileFilter)
        return state.children + (dir to newChildren)
    }

    private fun analyzeInputText(text: String, strings: BlazeStrings, mode: FilePickerMode, fileFilter: (Path) -> Boolean): FilePickerInputState {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FilePickerInputState(null, exists = false, problem = null, isValid = false)

        val home = System.getProperty("user.home")
        val expanded = when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") || trimmed.startsWith("~\\") -> home + trimmed.substring(1)
            else -> trimmed
        }

        return try {
            val path = Path.of(expanded).toAbsolutePath().normalize()
            if (!fileSystemRepository.exists(path)) {
                FilePickerInputState(path, exists = false, problem = strings.filePicker.errorPathNotExists, isValid = false)
            } else {
                val isDirectory = fileSystemRepository.isDirectory(path)
                when (mode) {
                    FilePickerMode.Directory ->
                        if (isDirectory) FilePickerInputState(path, true, null, true)
                        else FilePickerInputState(path, true, strings.filePicker.errorNotAFolder, false)

                    FilePickerMode.File -> when {
                        isDirectory -> FilePickerInputState(path, true, null, false)
                        !fileFilter(path) -> FilePickerInputState(path, true, strings.filePicker.errorUnsupportedFileType, false)
                        else -> FilePickerInputState(path, true, null, true)
                    }
                }
            }
        } catch (_: Exception) {
            FilePickerInputState(null, exists = false, problem = strings.filePicker.errorInvalidPath, isValid = false)
        }
    }

    fun validateFolderName(name: String, parent: Path, strings: BlazeStrings): String? {
        if (name.isEmpty()) return null
        if (name == "." || name == "..") return strings.filePicker.errorInvalidName

        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val illegal = if (windows) "\\/:*?\"<>|" else "/\u0000"
        if (name.any { it in illegal }) return strings.filePicker.errorIllegalCharacters

        return try {
            if (fileSystemRepository.exists(parent.resolve(name))) strings.filePicker.errorAlreadyExists else null
        } catch (_: Exception) {
            strings.filePicker.errorInvalidName
        }
    }
}
