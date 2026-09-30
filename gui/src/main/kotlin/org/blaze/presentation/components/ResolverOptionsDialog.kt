package org.blaze.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.ResolverOptionType
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Renders a [ConfigurableLinkResolver]'s declared option descriptors as a small editor form and
 * hands the whole edited value map back on confirm. Both the buffered Settings dialog and the live
 * Extensions panel reuse it; neither runs plugin code to build the form — the descriptor alone
 * decides the control.
 *
 * Values are carried as plain strings (booleans as `"true"`/`"false"`, integers as decimal text) to
 * match the on-disk slice format, so confirming is a direct write. Integer text is clamped only at
 * read time by the handler; the editor keeps the keystrokes it was given.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ResolverOptionsDialog(
    title: String,
    descriptors: List<ResolverOption>,
    initialValues: Map<String, String>,
    okLabel: String,
    cancelLabel: String,
    restoreDefaultsLabel: String,
    priorityLabel: String? = null,
    initialPriority: Int = 0,
    priorityDescription: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (values: Map<String, String>, priority: Int) -> Unit,
) {
    val working = remember {
        mutableStateMapOf<String, String>().apply {
            descriptors.forEach { put(it.key, initialValues[it.key] ?: it.defaultValue) }
        }
    }
    // Priority is only edited where the caller surfaces it (the live panel); the buffered Settings
    // dialog leaves [priorityLabel] null, so no field renders and the value round-trips unchanged.
    var priority by remember { mutableStateOf(initialPriority) }
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    BaseDialog(onDismiss = onDismiss) {
        IdeDialogTitle(title)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            descriptors.forEach { option ->
                val value = working[option.key] ?: option.defaultValue
                ResolverOptionField(
                    option = option,
                    value = value,
                    infoStyle = infoStyle,
                    onValueChange = { working[option.key] = it }
                )
            }

            priorityLabel?.let { label ->
                PriorityField(
                    label = label,
                    description = priorityDescription,
                    value = priority,
                    infoStyle = infoStyle,
                    onValueChange = { priority = it }
                )
            }
        }

        // Resetting rewrites every working value from the descriptors' declared defaults; the
        // fields re-read them, so the controls snap back without needing their own keys.
        Text(
            text = restoreDefaultsLabel,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    descriptors.forEach { working[it.key] = it.defaultValue }
                }
        )

        IdeDialogActions(
            dismissText = cancelLabel,
            onDismiss = onDismiss,
            confirmText = okLabel,
            onConfirm = {
                onConfirm(
                    descriptors.associate { it.key to (working[it.key] ?: it.defaultValue) },
                    priority
                )
            },
        )
    }
}

/** A compact stepper for the handler's ordering weight (higher resolves first). */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun PriorityField(
    label: String,
    description: String?,
    value: Int,
    infoStyle: androidx.compose.ui.text.TextStyle,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label)
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.size(56.dp)) {
                LabeledFreeText(
                    value = value.toString(),
                    onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '-' }.toIntOrNull() ?: 0) }
                )
            }
        }
        if (description != null) {
            Text(text = description, style = infoStyle, color = JewelTheme.globalColors.text.info)
        }
    }
}

/** One descriptor rendered by kind: text/integer field, checkbox, or a combo box of choices. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ResolverOptionField(
    option: ResolverOption,
    value: String,
    infoStyle: androidx.compose.ui.text.TextStyle,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = option.label)

        when (val type = option.type) {
            ResolverOptionType.Boolean -> Checkbox(
                checked = value.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onValueChange(it.toString()) },
            )

            is ResolverOptionType.Select -> {
                val choices = type.choices
                val index = choices.indexOf(value).coerceAtLeast(0)
                ListComboBox(
                    items = choices,
                    selectedIndex = index,
                    onSelectedItemChange = { onValueChange(choices[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ResolverOptionType.Text -> LabeledFreeText(value, onValueChange)

            is ResolverOptionType.Integer -> LabeledFreeText(
                value = value,
                // Keep only digits and one leading minus; the handler clamps the rest to its range.
                onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '-' }) },
            )
        }

        option.description?.let {
            Text(text = it, style = infoStyle, color = JewelTheme.globalColors.text.info)
        }
    }
}

/**
 * A single-line editor whose cursor survives typing: the [TextFieldValue] is remembered per call
 * site, so recomposition (driven by the parent's working map) doesn't reset it to the raw string.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun LabeledFreeText(value: String, onValueChange: (String) -> Unit) {
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
