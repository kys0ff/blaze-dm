package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

enum class FilePickerMode { Directory, File }

/**
 * IDE-style "Select path" dialog.
 *
 * - Tree of the file system, loaded lazily off the UI thread.
 * - Path field that stays in sync with the tree (type to jump, Enter to confirm, `~` works).
 * - Keyboard navigation in the tree: Up/Down/Home/End, Left/Right to collapse/expand, Enter to confirm.
 * - Toolbar: Home, New folder, Refresh, Show hidden files.
 *
 * In [FilePickerMode.File] mode only files accepted by [fileFilter] are listed (folders always are).
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun FilePickerDialog(
    onDismiss: () -> Unit,
    onPick: (Path) -> Unit,
    title: String = "Select folder",
    description: String? = null,
    mode: FilePickerMode = FilePickerMode.Directory,
    initialPath: Path? = null,
    confirmText: String = "OK",
    fileFilter: (Path) -> Boolean = { true },
) {
    val scope = rememberCoroutineScope()
    val directoriesOnly = mode == FilePickerMode.Directory
    val model = remember { FileTreeModel(scope, directoriesOnly, fileFilter) }
    val listState = rememberLazyListState()
    val treeFocus = remember { FocusRequester() }
    var treeFocused by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }

    val home = remember { Path.of(System.getProperty("user.home")) }
    val start = remember {
        (initialPath ?: home).nearestExisting().let {
            if (directoriesOnly && !Files.isDirectory(it)) it.parent ?: it else it
        }
    }
    var pathText by remember { mutableStateOf(TextFieldValue(start.toString())) }

    val input = remember(pathText.text) { analyzeInput(pathText.text, mode, fileFilter) }
    val pickable: Path? = if (input.isValid) input.path else null
    val rows = model.visibleRows()

    val errorColor = JewelTheme.globalColors.text.error
    val secondary = JewelTheme.globalColors.text.info
    val small = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    fun confirm(path: Path) {
        onPick(path)
        onDismiss()
    }

    // Start with the tree focused so arrow keys work immediately.
    LaunchedEffect(Unit) { treeFocus.requestFocus() }

    // Typing (or the initial path) an existing path reveals it in the tree.
    val typedTarget = input.path?.takeIf { input.exists }
    LaunchedEffect(typedTarget) {
        val target = typedTarget ?: return@LaunchedEffect
        if (target == model.selected) return@LaunchedEffect
        if (model.selected != null) delay(150.milliseconds) // debounce while typing
        model.reveal(target)
    }

    // Tree selection writes back to the path field (unless the field already means the same path).
    LaunchedEffect(model.selected) {
        val selected = model.selected ?: return@LaunchedEffect
        if (input.path != selected) pathText = TextFieldValue(selected.toString())
    }

    // Keep the selected row on screen.
    LaunchedEffect(model.selected) {
        val index = rows.indexOfFirst { it.node.path == model.selected }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        when {
            visible.isEmpty() -> listState.scrollToItem((index - 3).coerceAtLeast(0))
            index <= visible.first().index -> listState.scrollToItem(index)
            index >= visible.last().index ->
                listState.scrollToItem((index - visible.size + 2).coerceAtLeast(0))
        }
    }

    val onTreeKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown || rows.isEmpty()) return@handler false
        val index = rows.indexOfFirst { it.node.path == model.selected }
        val row = rows.getOrNull(index)
        when (event.key) {
            Key.DirectionDown -> {
                model.selected = rows[(index + 1).coerceAtMost(rows.lastIndex)].node.path
                true
            }

            Key.DirectionUp -> {
                model.selected = rows[(index - 1).coerceAtLeast(0)].node.path
                true
            }

            Key.MoveHome -> {
                model.selected = rows.first().node.path
                true
            }

            Key.MoveEnd -> {
                model.selected = rows.last().node.path
                true
            }

            Key.DirectionRight -> {
                if (row != null && row.node.isDirectory) {
                    if (model.expanded[row.node.path] == true) {
                        rows.getOrNull(index + 1)?.takeIf { it.depth > row.depth }
                            ?.let { model.selected = it.node.path }
                    } else {
                        model.expand(row.node.path)
                    }
                }
                true
            }

            Key.DirectionLeft -> {
                if (row != null) {
                    if (row.node.isDirectory && model.expanded[row.node.path] == true) {
                        model.collapse(row.node.path)
                    } else {
                        rows.subList(0, index).lastOrNull { it.depth < row.depth }
                            ?.let { model.selected = it.node.path }
                    }
                }
                true
            }

            Key.Enter, Key.NumPadEnter -> {
                if (pickable != null) {
                    confirm(pickable)
                } else {
                    row?.takeIf { it.node.isDirectory }?.let { model.toggle(it.node.path) }
                }
                true
            }

            else -> false
        }
    }

    val newFolderParent = remember(model.selected) {
        model.selected?.let { if (Files.isDirectory(it)) it else it.parent }
    }

    IdeDialogSurface(onDismiss = onDismiss, width = 620.dp, requestFocus = false) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            if (description != null) {
                Text(text = description, style = small, color = secondary)
            }
        }

        // Toolbar
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIconButton(
                key = AllIconsKeys.Nodes.HomeFolder,
                tooltip = "Home",
                onClick = { scope.launch { model.reveal(home) } }
            )
            ToolbarIconButton(
                key = AllIconsKeys.Actions.NewFolder,
                tooltip = "New folder",
                enabled = newFolderParent != null,
                onClick = { showNewFolder = true }
            )
            ToolbarIconButton(
                key = AllIconsKeys.Actions.Refresh,
                tooltip = "Refresh",
                onClick = { model.refresh() }
            )
            Divider(
                Orientation.Vertical,
                modifier = Modifier.height(16.dp).padding(horizontal = 4.dp)
            )
            ToolbarIconButton(
                key = AllIconsKeys.Actions.ToggleVisibility,
                tooltip = if (model.showHidden) "Hide hidden files" else "Show hidden files",
                onClick = { model.showHidden = !model.showHidden },
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (model.showHidden) {
                            JewelTheme.globalColors.text.normal.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        }
                    )
            )
        }

        // Path field
        TextField(
            value = pathText,
            onValueChange = { pathText = it },
            placeholder = { Text("Path") },
            outline = if (input.problem != null) Outline.Error else Outline.None,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                        pickable != null
                    ) {
                        confirm(pickable)
                        true
                    } else {
                        false
                    }
                }
        )

        // Tree
        val treeShape = RoundedCornerShape(4.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .border(1.dp, JewelTheme.globalColors.borders.normal, treeShape)
                .clip(treeShape)
                .focusRequester(treeFocus)
                .onFocusChanged { treeFocused = it.hasFocus }
                .onPreviewKeyEvent(onTreeKey)
                .focusable()
        ) {
            VerticallyScrollableContainer(listState, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(rows, key = { it.node.path }) { row ->
                        TreeRowItem(
                            row = row,
                            isExpanded = model.expanded[row.node.path] == true,
                            isSelected = row.node.path == model.selected,
                            treeFocused = treeFocused,
                            onSelect = {
                                model.selected = row.node.path
                                treeFocus.requestFocus()
                            },
                            onToggle = {
                                model.toggle(row.node.path)
                                treeFocus.requestFocus()
                            },
                            onDoubleClick = {
                                if (row.node.isDirectory) model.toggle(row.node.path)
                                else confirm(row.node.path)
                            }
                        )
                    }
                }
            }
        }

        // Validation message or hint
        Text(
            text = input.problem
                ?: if (directoriesOnly) {
                    "Select a folder in the tree, or type a path above."
                } else {
                    "Select a file in the tree, or type a path above."
                },
            style = small,
            color = if (input.problem != null) errorColor else secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            DefaultButton(
                onClick = { pickable?.let { confirm(it) } },
                enabled = pickable != null
            ) {
                Text(confirmText)
            }
        }
    }

    if (showNewFolder && newFolderParent != null) {
        NewFolderDialog(
            parent = newFolderParent,
            onDismiss = { showNewFolder = false },
            onCreated = { created ->
                scope.launch {
                    model.refreshNow()
                    model.reveal(created)
                }
            }
        )
    }
}

@Composable
internal fun IdeDialogSurface(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 460.dp,
    requestFocus: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(8.dp)

    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .width(width)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun NewFolderDialog(
    parent: Path,
    onDismiss: () -> Unit,
    onCreated: (Path) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var creationError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val trimmed = name.text.trim()
    val nameProblem = remember(trimmed) { folderNameProblem(trimmed, parent) }
    val message = creationError ?: nameProblem
    val canCreate = trimmed.isNotEmpty() && nameProblem == null

    fun create() {
        if (!canCreate) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Files.createDirectory(parent.resolve(trimmed)) }
            }
            result
                .onSuccess {
                    onCreated(it)
                    onDismiss()
                }
                .onFailure { creationError = it.message ?: "Couldn't create the folder" }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    IdeDialogSurface(onDismiss = onDismiss, width = 420.dp, requestFocus = false) {
        Text(
            text = "New folder",
            style = JewelTheme.defaultTextStyle.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Name:")
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    creationError = null
                },
                outline = if (message != null) Outline.Error else Outline.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            create()
                            true
                        } else {
                            false
                        }
                    }
            )
            Text(
                text = message ?: "Will be created in $parent",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = if (message != null) {
                    JewelTheme.globalColors.text.error
                } else {
                    JewelTheme.globalColors.text.info
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            DefaultButton(onClick = ::create, enabled = canCreate) { Text("OK") }
        }
    }
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    isExpanded: Boolean,
    isSelected: Boolean,
    treeFocused: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = JewelTheme.simpleListItemStyle.colors

    val activeSelection = isSelected && treeFocused
    val background = when {
        activeSelection -> colors.backgroundSelectedActive
        isSelected -> colors.backgroundSelectedActive.copy(alpha = 0.45f)
        hovered -> JewelTheme.globalColors.text.normal.copy(alpha = 0.07f)
        else -> Color.Transparent
    }
    val contentColor = if (activeSelection) colors.contentSelectedActive else Color.Unspecified

    val select by rememberUpdatedState(onSelect)
    val toggle by rememberUpdatedState(onToggle)
    val doubleClick by rememberUpdatedState(onDoubleClick)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .hoverable(interaction)
                .pointerInput(row.node.path) {
                    detectTapGestures(
                        onPress = { select() },
                        onDoubleTap = { doubleClick() }
                    )
                }
                .padding(start = 4.dp + (row.depth * 16).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .pointerInput(row.node.path) {
                        detectTapGestures(onPress = { toggle() })
                    },
                contentAlignment = Alignment.Center
            ) {
                if (row.node.isDirectory) {
                    Icon(
                        key = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = contentColor
                    )
                }
            }
            Icon(
                key = if (row.node.isDirectory) AllIconsKeys.Nodes.Folder else AllIconsKeys.FileTypes.Any_type,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = row.node.displayName,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class FsNode(val path: Path, val isDirectory: Boolean, val isHidden: Boolean) {
    val displayName: String get() = path.fileName?.toString() ?: path.toString()
}

private data class TreeRow(val node: FsNode, val depth: Int)

@Stable
private class FileTreeModel(
    private val scope: CoroutineScope,
    private val directoriesOnly: Boolean,
    private val fileFilter: (Path) -> Boolean
) {
    val roots = mutableStateListOf<FsNode>()
    val expanded = mutableStateMapOf<Path, Boolean>()
    private val children = mutableStateMapOf<Path, List<FsNode>>()

    var selected by mutableStateOf<Path?>(null)
    var showHidden by mutableStateOf(false)

    init {
        FileSystems.getDefault().rootDirectories.forEach {
            roots += FsNode(
                path = it,
                isDirectory = true,
                isHidden = false
            )
        }
    }

    fun visibleRows(): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        fun visit(node: FsNode, depth: Int) {
            out += TreeRow(node, depth)
            if (expanded[node.path] == true) {
                children[node.path]?.forEach { child ->
                    if (showHidden || !child.isHidden) visit(child, depth + 1)
                }
            }
        }
        roots.forEach { visit(it, 0) }
        return out
    }

    private suspend fun ensureLoaded(dir: Path) {
        if (children.containsKey(dir)) return
        children[dir] =
            withContext(Dispatchers.IO) { listChildren(dir, directoriesOnly, fileFilter) }
    }

    fun toggle(dir: Path) {
        if (expanded[dir] == true) collapse(dir) else expand(dir)
    }

    fun collapse(dir: Path) {
        expanded[dir] = false
    }

    fun expand(dir: Path) {
        scope.launch {
            ensureLoaded(dir)
            expanded[dir] = true
        }
    }

    suspend fun reveal(target: Path): Boolean {
        val abs = target.toAbsolutePath().normalize()
        val chain = generateSequence(abs) { it.parent }.toList().asReversed()
        val top = chain.first()
        if (roots.none { it.path == top }) roots += FsNode(
            path = top,
            isDirectory = true,
            isHidden = false
        )

        for (dir in chain.dropLast(1)) {
            ensureLoaded(dir)
            expanded[dir] = true
        }

        val parent = abs.parent
        val listed = parent == null || children[parent]?.any { it.path == abs } == true
        if (!listed) return false

        val hiddenInChain = chain.drop(1).any { p ->
            children[p.parent]?.firstOrNull { it.path == p }?.isHidden == true
        }
        if (hiddenInChain) showHidden = true

        selected = abs
        return true
    }

    suspend fun refreshNow() {
        val loaded = children.keys.toList()
        val fresh = withContext(Dispatchers.IO) {
            loaded.associateWith { listChildren(it, directoriesOnly, fileFilter) }
        }
        children.clear()
        children.putAll(fresh)
    }

    fun refresh() {
        scope.launch { refreshNow() }
    }
}

private fun listChildren(
    dir: Path,
    directoriesOnly: Boolean,
    fileFilter: (Path) -> Boolean
): List<FsNode> =
    try {
        Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { p ->
                val isDir = Files.isDirectory(p)
                if (!isDir && (directoriesOnly || !fileFilter(p))) {
                    null
                } else {
                    FsNode(p, isDir, isHiddenPath(p))
                }
            }
        }.sortedWith(compareBy({ !it.isDirectory }, { it.displayName.lowercase() }))
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

private fun isHiddenPath(path: Path): Boolean =
    path.fileName?.toString()?.startsWith(".") == true ||
            runCatching { Files.isHidden(path) }.getOrDefault(false)

private fun Path.nearestExisting(): Path =
    generateSequence(toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.exists(it) }
        ?: Path.of(System.getProperty("user.home"))

private data class InputState(
    val path: Path?,
    val exists: Boolean,
    val problem: String?,
    val isValid: Boolean
)

private fun analyzeInput(
    raw: String,
    mode: FilePickerMode,
    fileFilter: (Path) -> Boolean
): InputState {
    val text = raw.trim()
    if (text.isEmpty()) return InputState(null, exists = false, problem = null, isValid = false)

    val home = System.getProperty("user.home")
    val expanded = when {
        text == "~" -> home
        text.startsWith("~/") || text.startsWith("~\\") -> home + text.substring(1)
        else -> text
    }

    val path = try {
        Path.of(expanded).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
        return InputState(null, exists = false, problem = "Invalid path", isValid = false)
    }

    if (!Files.exists(path)) {
        return InputState(path, exists = false, problem = "Path doesn't exist", isValid = false)
    }

    val isDirectory = Files.isDirectory(path)
    return when (mode) {
        FilePickerMode.Directory ->
            if (isDirectory) InputState(path, true, null, true)
            else InputState(path, true, "Not a folder", false)

        FilePickerMode.File -> when {
            isDirectory -> InputState(path, true, null, false)
            !fileFilter(path) -> InputState(path, true, "This file type isn't supported", false)
            else -> InputState(path, true, null, true)
        }
    }
}

private fun folderNameProblem(name: String, parent: Path): String? {
    if (name.isEmpty()) return null
    if (name == "." || name == "..") return "Invalid name"

    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val illegal = if (windows) "\\/:*?\"<>|" else "/\u0000"
    if (name.any { it in illegal }) return "Name contains illegal characters"

    return try {
        if (Files.exists(parent.resolve(name))) "A file or folder with this name already exists" else null
    } catch (_: InvalidPathException) {
        "Invalid name"
    }
}
