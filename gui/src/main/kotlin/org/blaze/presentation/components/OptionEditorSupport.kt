package org.blaze.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import org.blaze.i18n.BlazeStrings
import org.island.theme.ui.IslandSemanticColors
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Support shared by the two extension-option editors ([ResolverOptionsDialog] and
 * [ThemeOptionsDialog]): a caret-stable text field that can paint the IDE's error outline, the
 * one-line hint that sits under every field, and the numeric validation both dialogs run so a bad
 * value is flagged live and the confirm button is disabled until it is fixed.
 */

/** The digits-only filter used by numeric option fields (a single leading sign is kept). */
val IntegerTextFilter: (String) -> String = { it.filter { c -> c.isDigit() || c == '-' } }

/**
 * Validates a numeric option's current text against its inclusive range. Returns null when the value
 * is a whole number inside `[minimum, maximum]`, otherwise the app-owned message for the failure: a
 * non-numeric (or empty) entry reads [msgs].integerRequired, an in-range miss reads
 * [msgs.integerRange].
 */
fun validateIntegerValue(
    value: String,
    minimum: Long,
    maximum: Long,
    msgs: BlazeStrings.Options,
): String? {
    val parsed = value.toLongOrNull()
    return when {
        value.isBlank() || parsed == null -> msgs.integerRequired
        parsed < minimum || parsed > maximum -> msgs.integerRange(minimum, maximum)
        else -> null
    }
}

/**
 * A single-line option editor whose model string is the source of truth: [filter] normalizes each
 * edit (digits, hex...) before it reaches [onValueChange], and the field re-seeds from the model
 * whenever it changes from outside — Restore defaults, or a keystroke the filter dropped — keeping
 * the caret where the edit left it. A non-null [error] paints the IDE's error outline.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun OptionValueField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    filter: (String) -> String = { it },
) {
    var editor by remember { mutableStateOf(TextFieldValue(value)) }
    // Re-seed from the model only when it diverges from what is printed (an external edit, or a
    // filtered keystroke); the caret follows the surviving text so typing mid-string stays natural.
    if (editor.text != value) {
        val dropped = editor.text.length - value.length
        val caret = (editor.selection.end - dropped).coerceIn(0, value.length)
        editor = TextFieldValue(value, TextRange(caret))
    }
    TextField(
        value = editor,
        onValueChange = { onValueChange(filter(it.text)) },
        outline = if (error != null) Outline.Error else Outline.None,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The one-line helper under an option field: the live [text] in the IDE's error red when
 * [isError], otherwise in the neutral secondary color used for a field's description or format hint.
 */
@Composable
fun OptionFieldHint(text: String, isError: Boolean) {
    Text(
        text = text,
        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
        color = if (isError) IslandSemanticColors.error else JewelTheme.globalColors.text.info,
    )
}
