package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.presentation.theme.toColor
import org.blaze.theming.api.ThemeConfig
import org.blaze.theming.api.ThemeOption
import org.blaze.theming.api.ThemeOptionType
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Renders a [org.blaze.theming.api.ConfigurableBlazeThemeProvider]'s declared option descriptors as
 * a small editor form and hands the whole edited value map back on confirm — the theming twin of
 * [ResolverOptionsDialog], so neither runs plugin code to build the form: the descriptor alone picks
 * the control.
 *
 * Values are carried as plain strings to match the on-disk `themes.json` slice (booleans as
 * `"true"`/`"false"`, integers as decimal text, colors as `#AARRGGBB`), so confirming is a direct
 * write. Color text is validated only when the palette is resolved; the editor keeps the keystrokes
 * it was given and paints a swatch from whatever currently parses, falling back to a hatch for an
 * incomplete value so the field never blocks typing.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ThemeOptionsDialog(
    title: String,
    descriptors: List<ThemeOption>,
    initialValues: Map<String, String>,
    okLabel: String,
    cancelLabel: String,
    restoreDefaultsLabel: String,
    colorFormatHint: String,
    onDismiss: () -> Unit,
    onConfirm: (values: Map<String, String>) -> Unit,
) {
    val working = remember {
        mutableStateMapOf<String, String>().apply {
            descriptors.forEach { put(it.key, initialValues[it.key] ?: it.defaultValue) }
        }
    }
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    BaseDialog(onDismiss = onDismiss) {
        DialogHeader(title)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            descriptors.forEach { option ->
                val value = working[option.key] ?: option.defaultValue
                ThemeOptionField(
                    option = option,
                    value = value,
                    infoStyle = infoStyle,
                    colorFormatHint = colorFormatHint,
                    onValueChange = { working[option.key] = it }
                )
            }
        }

        // Resetting rewrites every working value from the descriptors' declared defaults; the fields
        // re-read them, so the controls snap back without needing their own keys. It rides the
        // leading slot of the action bar (the same home other dialogs use for secondary actions)
        // so it reads as a real button, not a caption.
        StandardDialogButtons(
            dismissText = cancelLabel,
            onDismiss = onDismiss,
            confirmText = okLabel,
            leading = {
                OutlinedButton(
                    onClick = { descriptors.forEach { working[it.key] = it.defaultValue } }
                ) {
                    Text(restoreDefaultsLabel)
                }
            },
            onConfirm = {
                onConfirm(descriptors.associate { it.key to (working[it.key] ?: it.defaultValue) })
            },
        )
    }
}

/** One descriptor rendered by kind: text/integer/color field, checkbox, or a combo box of choices. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ThemeOptionField(
    option: ThemeOption,
    value: String,
    infoStyle: TextStyle,
    colorFormatHint: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = option.label)

        when (val type = option.type) {
            ThemeOptionType.Boolean -> Checkbox(
                checked = value.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onValueChange(it.toString()) },
            )

            is ThemeOptionType.Select -> {
                val choices = type.choices
                val index = choices.indexOf(value).coerceAtLeast(0)
                ListComboBox(
                    items = choices,
                    selectedIndex = index,
                    onSelectedItemChange = { onValueChange(choices[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ThemeOptionType.Text -> CursorStableField(value, onValueChange)

            is ThemeOptionType.Integer -> CursorStableField(
                value = value,
                // Keep only digits and one leading minus; the registry clamps the rest to its range.
                onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '-' }) },
            )

            is ThemeOptionType.Color -> ColorField(
                value = value,
                alphaAllowed = type.alphaAllowed,
                hint = colorFormatHint,
                infoStyle = infoStyle,
                onValueChange = onValueChange,
            )
        }

        option.description?.let {
            Text(text = it, style = infoStyle, color = JewelTheme.globalColors.text.info)
        }
    }
}

/**
 * A hex color editor with a live swatch. The text is filtered to valid hex so the field can't hold
 * a character the parser would reject; the swatch paints from the value [ThemeConfig.parseArgbHex]
 * yields (6-digit RGB is treated as opaque, matching the parser) and falls back to a neutral hatch
 * while the field is incomplete. A `#`/`0x` prefix is preserved as typed.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ColorField(
    value: String,
    alphaAllowed: Boolean,
    hint: String,
    infoStyle: TextStyle,
    onValueChange: (String) -> Unit,
) {
    val parsed = ThemeConfig.parseArgbHex(value)
    val swatch = parsed?.toColor() ?: Color.Unspecified

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(if (swatch == Color.Unspecified) Color.LightGray else swatch)
                .border(1.dp, JewelTheme.globalColors.borders.normal)
        )
        CursorStableField(
            value = value,
            onValueChange = { raw ->
                val keepHex = raw.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }
                // Preserve a leading # / 0x marker so the swatch parser still recognizes the format.
                val prefix = when {
                    raw.startsWith("#") -> "#"
                    raw.startsWith("0x") || raw.startsWith("0X") -> "0x"
                    else -> ""
                }
                onValueChange(prefix + keepHex)
            },
        )
    }
    Text(
        text = hint,
        style = infoStyle,
        color = JewelTheme.globalColors.text.info,
    )
}

/**
 * A single-line editor whose cursor survives typing: the [TextFieldValue] is remembered per call
 * site, so recomposition (driven by the parent's working map) doesn't reset it to the raw string.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun CursorStableField(value: String, onValueChange: (String) -> Unit) {
    var editor by remember { mutableStateOf(TextFieldValue(value)) }
    TextField(
        value = editor,
        onValueChange = {
            editor = it
            onValueChange(it.text)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
