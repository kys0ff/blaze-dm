package org.blaze.presentation.screens.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.blaze.i18n.EnStrings
import org.blaze.presentation.screens.settings.components.SettingsSidebar
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.theme.BlazeTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The option-level Settings search, exercised through the composables that actually render it.
 *
 * The point of the registry refactor is that filtering happens *by option*, not by page title: typing a
 * word that lives only in one control's keywords must keep that control's page in the sidebar while
 * hiding every page that has no match, and must hide the non-matching rows (and their now-empty group
 * headers) inside the surviving page. These tests drive the real [SettingsSidebar] and [SettingRow] /
 * [SettingsGroup] composables so the sidebar and the content pane are proven to agree on one query.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSearchUiTest {

    // "proxy" is a curated keyword on downloads.userAgent and on no other option, so it isolates exactly
    // one option (in Downloads) and proves the search reaches past literal labels into the aliases.
    private val query = "proxy"

    private fun navTag(category: SettingsCategory) =
        "settings_nav_" + SettingsRegistry.categoryTitle(category, EnStrings)

    @Test
    fun `sidebar keeps only pages that host a matching option`() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            BlazeTheme(isDark = true) {
                SettingsSidebar(
                    selected = SettingsCategory.GENERAL,
                    hasDownloadsError = false,
                    strings = EnStrings,
                    query = query,
                    onQueryChange = {},
                    onSelect = {},
                )
            }
        }
        waitForIdle()

        // Downloads owns downloads.userAgent ("proxy"); every other page has no match and is pruned.
        assertTrue(
            onAllNodes(hasTestTag(navTag(SettingsCategory.DOWNLOADS)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "Downloads hosts the matching option and must stay visible",
        )
        assertFalse(
            onAllNodes(hasTestTag(navTag(SettingsCategory.FILES)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "Files has no option matching the query and must be pruned",
        )
        assertFalse(
            onAllNodes(hasTestTag(navTag(SettingsCategory.GENERAL)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "General has no option matching the query and must be pruned",
        )

        // A matching page still shows its option-count badge ("1" here).
        onNodeWithText("1").assertExists()
    }

    @Test
    fun `content pane hides non-matching rows and empty group headers`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            setContent {
                BlazeTheme(isDark = true) {
                    CompositionLocalProvider(LocalSettingsFilter provides SettingsFilter(query)) {
                        SettingsGroup("network", EnStrings) {
                            SettingRow("downloads.userAgent") {}
                        }
                        SettingsGroup("bandwidth", EnStrings) {
                            SettingRow("downloads.speedLimit") {}
                        }
                    }
                }
            }
            waitForIdle()

            // The row under the matching group survives; the one under a group with no match vanishes.
            assertTrue(
                onAllNodes(hasTestTag("setting_downloads.userAgent"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "the matching option must stay rendered",
            )
            assertFalse(
                onAllNodes(hasTestTag("setting_downloads.speedLimit"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "a non-matching option must be hidden while the query is active",
            )
            // The empty "Bandwidth" header is gone; the "Network" header (which holds the match) is shown.
            onNodeWithText(SettingsRegistry.groupTitle("network", EnStrings)).assertExists()
            assertFalse(
                onAllNodes(hasText(SettingsRegistry.groupTitle("bandwidth", EnStrings)))
                    .fetchSemanticsNodes().isNotEmpty(),
                "a group whose every option is hidden must drop its header",
            )
        }
}
