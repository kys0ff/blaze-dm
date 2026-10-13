package org.blaze.presentation.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.AppMetrics
import org.blaze.presentation.screens.settings.SettingsNavItem
import org.blaze.presentation.screens.settings.SettingsRegistry
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.island.foundation.DialogButton
import org.island.foundation.dialogButtonOrder
import org.island.theme.ui.IslandSemanticColors
import org.island.ui.toolwindow.ToolWindowSearchField
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The Settings screen's reusable chrome: the category sidebar, its navigation list, the footer button
 * bar and the empty-search panel. They live in a `components` subpackage so the screen body
 * ([org.blaze.presentation.screens.settings.SettingsScreen]) reads as composition rather than as a wall
 * of layout, and so the screenshot harness and UI tests can drive the sidebar and footer directly.
 */

/**
 * Left-hand category list, led by a search box; the Downloads entry shows an error dot when a field is
 * invalid, and every surviving entry carries a count badge while a search is active.
 *
 * The IDE's Settings dialog prunes its tree by *option*, not by page title: typing "proxy" must keep
 * Downloads visible because an option inside it matches, even though the word "Downloads" has no "proxy"
 * in it. So the filter is [SettingsRegistry.categoryMatches] over the very same registry the content pane
 * hides rows with — the sidebar and the section can never disagree about what a query matches.
 */
@Composable
internal fun SettingsSidebar(
    selected: SettingsCategory,
    hasDownloadsError: Boolean,
    strings: BlazeStrings,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SettingsCategory) -> Unit
) {
    Column(
        modifier = Modifier
            .width(AppMetrics.SettingsNavWidth)
            .fillMaxHeight()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        ToolWindowSearchField(
            query = query,
            placeholder = strings.settings.searchPlaceholder,
            clearDescription = strings.settings.clearSearch,
            onQueryChange = onQueryChange,
            fieldWidth = AppMetrics.SettingsNavWidth,
        )
        Spacer(Modifier.height(6.dp))

        val visible = settingsNavCategories(strings).filter { (category, _) ->
            query.isBlank() || SettingsRegistry.categoryMatches(category, query, strings)
        }

        visible.forEach { (category, label) ->
            SettingsNavItem(
                label = label,
                isSelected = selected == category,
                hasError = category == SettingsCategory.DOWNLOADS && hasDownloadsError,
                query = query,
                matchCount = SettingsRegistry.matchCount(category, query, strings),
                onClick = { onSelect(category) }
            )
        }
    }
}

/** The category list in sidebar order, each paired with its localized page label. */
internal fun settingsNavCategories(strings: BlazeStrings): List<Pair<SettingsCategory, String>> = listOf(
    SettingsCategory.GENERAL to strings.settings.generalCategory,
    SettingsCategory.DOWNLOADS to strings.settings.downloadsCategory,
    SettingsCategory.FILES to strings.settings.filesCategory,
    SettingsCategory.EXTENSIONS to strings.settings.handlers.category,
    SettingsCategory.THEMES to strings.settings.themes.category,
    SettingsCategory.NOTIFICATIONS to strings.settings.notifications.category,
    SettingsCategory.SHORTCUTS to strings.settings.shortcutsCategory,
    SettingsCategory.LOGS to strings.settings.logsCategory
)

/** The content pane's empty state when a query matches no option anywhere, with a clear-search link. */
@Composable
internal fun NothingFoundPanel(strings: BlazeStrings, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = strings.settings.searchNoMatches,
            color = JewelTheme.globalColors.text.info,
        )
        Text(
            text = strings.settings.clearSearch,
            color = IslandSemanticColors.accent,
            modifier = Modifier
                .clickable(onClick = onClear)
                .semantics { testTag = "settings_clear_search" }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * OK commits and closes, Apply commits and stays, Cancel discards and closes. Committing is what
 * finally writes the buffered draft to the repositories/registries.
 *
 * The three are laid out by [dialogButtonOrder], the one platform switch: `OK, Cancel, Apply` on
 * Windows/Linux and `Cancel, Apply, OK` on macOS, where the default button belongs in the corner the
 * pointer rests in. Only the button *kind* is fixed per action, so OK stays accent-filled wherever it
 * lands.
 */
@Composable
internal fun SettingsButtonBar(
    okEnabled: Boolean,
    applyEnabled: Boolean,
    onOk: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    strings: BlazeStrings
) {
    // Button bar: top divider only (the old border() drew a frame on all four sides).
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(Orientation.Horizontal)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JewelTheme.globalColors.panelBackground)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dialogButtonOrder(hasApply = true).forEach { button ->
                when (button) {
                    DialogButton.Ok -> DefaultButton(onClick = onOk, enabled = okEnabled) {
                        Text(strings.common.ok)
                    }

                    DialogButton.Cancel -> OutlinedButton(onClick = onCancel) {
                        Text(strings.common.cancel)
                    }

                    DialogButton.Apply -> OutlinedButton(onClick = onApply, enabled = applyEnabled) {
                        Text(strings.common.apply)
                    }
                }
            }
        }
    }
}
