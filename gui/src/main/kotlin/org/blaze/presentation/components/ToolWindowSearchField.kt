package org.blaze.presentation.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.blaze.presentation.theme.BlazeTypography
import org.blaze.presentation.theme.blazeSearchFieldStyle
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Compact IntelliJ-style filter field shared by the tool-window chrome: the Downloads toolbar filters
 * the list with it, and the Settings sidebar filters its category tree with the same control.
 *
 * Both uses want the same picture, which is why the styling lives here rather than at the call sites —
 * island-level fill, a hairline rim that takes the accent only on focus, the search glyph inside and a
 * clear button once there is text. Typing filters live; [Escape] empties the query. [placeholder] and
 * [clearDescription] are supplied by the caller because they name what is being filtered.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ToolWindowSearchField(
    query: String,
    placeholder: String,
    clearDescription: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldWidth: Dp = BlazeLayout.SearchFieldWidth,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(query)) }

    // Keep the local field in sync when the query is changed elsewhere (e.g. cleared externally).
    LaunchedEffect(query) {
        if (fieldValue.text != query) fieldValue = TextFieldValue(query)
    }

    TextField(
        value = fieldValue,
        onValueChange = {
            fieldValue = it
            onQueryChange(it.text)
        },
        // The IDE's search field is a hole cut in its own island, not a brighter plate sitting on top
        // of one: island-level fill, a 1dp hairline rim that takes the accent only on focus (see
        // [org.blaze.presentation.theme.blazeSearchFieldStyle]).
        style = blazeSearchFieldStyle(),
        // The placeholder is set explicitly rather than inherited: left to the field's own text style it
        // comes in at 13sp in the normal text color, which reads as a heavier face next to the table's
        // 11sp cells. The IDE draws it small and in the hint color — Jewel's `TextColors` has no
        // `placeholder` token (only normal/selected/disabled/disabledSelected/info/error/warning), and
        // `text.info` is the one its own fields use for hint-level text.
        placeholder = {
            Text(
                text = placeholder,
                style = BlazeTypography.fieldPlaceholder,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
            )
        },
        leadingIcon = {
            Icon(
                key = AllIconsKeys.Actions.Find,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            if (fieldValue.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        fieldValue = TextFieldValue("")
                        onQueryChange("")
                    },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = clearDescription,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        },
        modifier = modifier
            .width(fieldWidth)
            .height(26.dp)
            .padding(horizontal = 6.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    fieldValue.text.isNotEmpty()
                ) {
                    fieldValue = TextFieldValue("")
                    onQueryChange("")
                    true
                } else {
                    false
                }
            },
    )
}
