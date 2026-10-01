package org.blaze.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.resolver.api.ResolverOption
import org.blaze.resolver.api.ResolverOptionType
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Renders a [ConfigurableLinkResolver]'s declared option descriptors as a small editor form and
 * hands the whole edited value map back on confirm. Both the buffered Settings dialog and the live
 * Extensions panel reuse it; neither runs plugin code to build the form — the descriptor alone
 * decides the control.
 *
 * Values are carried as plain strings (booleans as `"true"`/`"false"`, integers as decimal text) to
 * match the on-disk slice format, so confirming is a direct write. Each field is validated live
 * against its descriptor: a bad value paints the IDE's error outline and a message under the field,
 * and the confirm button stays disabled until every field is acceptable.
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
    val msgs = blazeStrings.options
    val working = remember {
        mutableStateMapOf<String, String>().apply {
            descriptors.forEach { put(it.key, initialValues[it.key] ?: it.defaultValue) }
        }
    }
    // Priority is only edited where the caller surfaces it (the live panel); the buffered Settings
    // dialog leaves [priorityLabel] null, so no field renders and the value round-trips unchanged.
    var priority by remember { mutableStateOf(initialPriority) }

    // Reading `working` here subscribes to every field, so a keystroke recomputes the error and the
    // enabled state immediately — validation is live, not deferred to confirm.
    fun errorOf(option: ResolverOption): String? =
        validateResolverOption(option, working[option.key] ?: option.defaultValue, msgs)
    val hasErrors = descriptors.any { errorOf(it) != null }

    BaseDialog(onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            descriptors.forEach { option ->
                ResolverOptionField(
                    option = option,
                    value = working[option.key] ?: option.defaultValue,
                    error = errorOf(option),
                    onValueChange = { working[option.key] = it }
                )
            }

            priorityLabel?.let { label ->
                PriorityField(
                    label = label,
                    description = priorityDescription,
                    value = priority,
                    onValueChange = { priority = it }
                )
            }
        }

        // Resetting rewrites every working value from the descriptors' declared defaults; the
        // fields re-read them, so the controls snap back without needing their own keys. It rides
        // the leading slot of the action bar (the same home other dialogs use for secondary
        // actions) so it reads as a real button, not a caption.
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
                onConfirm(
                    descriptors.associate { it.key to (working[it.key] ?: it.defaultValue) },
                    priority
                )
            },
        )
    }
}

/**
 * The handler option's live error for its current text, or null when acceptable. Only numeric
 * options can fail here — text is free-form, and boolean/select can only ever hold a valid choice —
 * validated through the same range check the theme dialog shares.
 */
private fun validateResolverOption(
    option: ResolverOption,
    value: String,
    msgs: BlazeStrings.Options,
): String? = when (val type = option.type) {
    is ResolverOptionType.Integer -> validateIntegerValue(value, type.minimum, type.maximum, msgs)
    else -> null
}

/** A compact stepper for the handler's ordering weight (higher resolves first). */
@Composable
private fun PriorityField(
    label: String,
    description: String?,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label)
        Box(modifier = Modifier.size(56.dp)) {
            OptionValueField(
                value = value.toString(),
                filter = IntegerTextFilter,
                onValueChange = { onValueChange(it.toIntOrNull() ?: 0) },
            )
        }
        if (description != null) {
            OptionFieldHint(description, isError = false)
        }
    }
}

/** One descriptor rendered by kind: text/integer field, checkbox, or a combo box of choices. */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ResolverOptionField(
    option: ResolverOption,
    value: String,
    error: String?,
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

            ResolverOptionType.Text -> OptionValueField(value = value, onValueChange = onValueChange)

            is ResolverOptionType.Integer -> OptionValueField(
                value = value,
                error = error,
                filter = IntegerTextFilter,
                onValueChange = onValueChange,
            )
        }

        // The error supersedes the description, so the red line always states what to fix.
        val hint = error ?: option.description
        if (hint != null) OptionFieldHint(hint, isError = error != null)
    }
}
