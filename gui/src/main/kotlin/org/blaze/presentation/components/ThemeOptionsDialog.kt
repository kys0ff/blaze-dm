package org.blaze.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
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

/**
 * Renders a [org.blaze.theming.api.ConfigurableBlazeThemeProvider]'s declared option descriptors as
 * a small editor form and hands the whole edited value map back on confirm — the theming twin of
 * [ResolverOptionsDialog], so neither runs plugin code to build the form: the descriptor alone picks
 * the control.
 *
 * Values are carried as plain strings to match the on-disk `themes.json` slice (booleans as
 * `"true"`/`"false"`, integers as decimal text, colors as `#AARRGGBB`), so confirming is a direct
 * write. Each field is validated live: an out-of-range number or an unparseable color paints the
 * IDE's error outline with a message under the field, and the confirm button stays disabled until
 * every option is acceptable.
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
    val msgs = blazeStrings.options
    val working = remember {
        mutableStateMapOf<String, String>().apply {
            descriptors.forEach { put(it.key, initialValues[it.key] ?: it.defaultValue) }
        }
    }

    // Reading `working` recomputes on every keystroke, so the field's error and the button's enabled
    // state track the current text live rather than only at confirm.
    fun errorOf(option: ThemeOption): String? =
        validateThemeOption(option, working[option.key] ?: option.defaultValue, msgs)
    val hasErrors = descriptors.any { errorOf(it) != null }

    BaseDialog(onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            descriptors.forEach { option ->
                ThemeOptionField(
                    option = option,
                    value = working[option.key] ?: option.defaultValue,
                    error = errorOf(option),
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
            confirmEnabled = !hasErrors,
            leading = {
                OutlinedButton(
                    onClick = { descriptors.forEach { working[it.key] = it.defaultValue } }
                ) {
                    Text(restoreDefaultsLabel)
                }
            },
            onConfirm = {
                // Only answers that differ from the descriptor's declared default are persisted. A
                // value left at its default is an *override for neither mode* — dropping it lets the
                // registry resolve the light/dark default per appearance instead of pinning the
                // (dark) default across both, which is what kept a day palette from ever showing.
                onConfirm(
                    descriptors
                        .mapNotNull { option ->
                            val value = working[option.key] ?: option.defaultValue
                            if (value != option.defaultValue) option.key to value else null
                        }
                        .toMap(),
                )
            },
        )
    }
}

/**
 * The theme option's live error for its current text, or null when acceptable. Numbers are checked
 * against their declared range through the same validation the handler dialog shares; colors are
 * invalid only when the hex cannot be parsed at all (the [alphaAllowed] clamp in [ColorField] keeps
 * an alpha-less option from ever carrying extra digits). Text, boolean and select can't fail here.
 */
private fun validateThemeOption(
    option: ThemeOption,
    value: String,
    msgs: BlazeStrings.Options,
): String? = when (val type = option.type) {
    is ThemeOptionType.Integer -> validateIntegerValue(value, type.minimum, type.maximum, msgs)
    is ThemeOptionType.Color -> if (ThemeConfig.parseArgbHex(value) == null) msgs.colorInvalid else null
    else -> null
}

/** One descriptor rendered by kind: text/integer/color field, checkbox, or a combo box of choices. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ThemeOptionField(
    option: ThemeOption,
    value: String,
    error: String?,
    colorFormatHint: String,
    onValueChange: (String) -> Unit,
) {
    val isColor = option.type is ThemeOptionType.Color
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

            ThemeOptionType.Text -> OptionValueField(value = value, onValueChange = onValueChange)

            is ThemeOptionType.Integer -> OptionValueField(
                value = value,
                error = error,
                filter = IntegerTextFilter,
                onValueChange = onValueChange,
            )

            is ThemeOptionType.Color -> ColorField(
                value = value,
                alphaAllowed = type.alphaAllowed,
                error = error,
                onValueChange = onValueChange,
            )
        }

        // While a value is invalid the red error is the only helper shown; once it is fixed the
        // neutral lines come back — a color keeps its format hint, and any descriptor its note.
        if (error != null) {
            OptionFieldHint(error, isError = true)
        } else {
            if (isColor) OptionFieldHint(colorFormatHint, isError = false)
            option.description?.let { OptionFieldHint(it, isError = false) }
        }
    }
}

/**
 * A hex color editor with a live swatch. The text is filtered to valid hex (and clamped to the
 * digits the option allows) so the field can't hold a character the parser would reject; the swatch
 * paints from the value [ThemeConfig.parseArgbHex] yields (6-digit RGB is treated as opaque, matching
 * the parser) and falls back to a neutral swatch while the field is incomplete. A `#`/`0x` prefix is
 * preserved as typed. The [error] — an unparseable value — paints the field's error outline; the
 * hint line under it is drawn by [ThemeOptionField].
 *
 * [alphaAllowed] decides how many hex digits the option can carry: an alpha-capable color takes the
 * full `#AARRGGBB` (8 digits), while an RGB-only option is clamped to `#RRGGBB` (6) so no alpha can
 * be typed into a value the palette would ignore.
 */
@Composable
private fun ColorField(
    value: String,
    alphaAllowed: Boolean,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    val parsed = ThemeConfig.parseArgbHex(value)
    val swatch = parsed?.toColor() ?: Color.Unspecified
    val maxDigits = if (alphaAllowed) 8 else 6

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
        OptionValueField(
            value = value,
            error = error,
            onValueChange = onValueChange,
            filter = { raw ->
                val keepHex = raw
                    .filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }
                    .take(maxDigits)
                // Preserve a leading # / 0x marker so the swatch parser still recognizes the format.
                val prefix = when {
                    raw.startsWith("#") -> "#"
                    raw.startsWith("0x") || raw.startsWith("0X") -> "0x"
                    else -> ""
                }
                prefix + keepHex
            },
        )
    }
}
