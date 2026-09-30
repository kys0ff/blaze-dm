package org.blaze.presentation.screens.settings

import org.blaze.i18n.EnStrings
import org.blaze.presentation.screens.settings.state.SettingsCategory
import org.blaze.presentation.search.SearchSources
import org.blaze.presentation.search.SearchTab
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The invariant the whole Settings search rests on: the [SettingsRegistry] *is* the UI. Every page's
 * section composable renders exactly its registered options, and nothing renders that is not registered
 * — otherwise a row would appear on screen but be invisible to search, or the search index would advertise
 * an option no page ever shows.
 *
 * These checks read the section *sources* rather than a composition on purpose: they are deterministic,
 * run without a display, and fail with the offending id — which is what makes them a real guard, not a
 * smoke test. [SettingRow] independently throws at runtime through [SettingsRegistry.require] if an id
 * is unknown; test [everyRenderedRowIdIsRegistered] proves the same thing statically so CI catches the
 * typo even on a page no UI test happens to open.
 */
class SettingsRegistryGuardTest {

    private val sectionDir = File(repoRoot(), "gui/src/main/kotlin/org/blaze/presentation/screens/settings")

    private val fileForCategory = mapOf(
        SettingsCategory.GENERAL to "SettingsGeneralSection.kt",
        SettingsCategory.DOWNLOADS to "SettingsDownloadsSection.kt",
        SettingsCategory.FILES to "SettingsFilesSection.kt",
        SettingsCategory.EXTENSIONS to "SettingsExtensionsSection.kt",
        SettingsCategory.THEMES to "SettingsThemesSection.kt",
        SettingsCategory.NOTIFICATIONS to "SettingsNotificationsSection.kt",
        SettingsCategory.SHORTCUTS to "SettingsShortcutsSection.kt",
        SettingsCategory.LOGS to "SettingsLogsSection.kt",
    )

    private val settingRowRegex = Regex("""SettingRow\(\s*"([^"]+)"""")
    private val settingsGroupRegex = Regex("""SettingsGroup\(\s*"([^"]+)"""")

    /** The ids of the `SettingRow(...)` calls inside [category]'s section file, in source order. */
    private fun renderedRowIds(category: SettingsCategory): List<String> {
        val fileName = fileForCategory.getValue(category)
        val file = File(sectionDir, fileName)
        assertTrue(file.exists(), "missing section source: ${file.absolutePath}")
        return settingRowRegex.findAll(file.readText()).map { it.groupValues[1] }.toList()
    }

    private fun renderedGroupKeys(category: SettingsCategory): List<String> {
        val file = File(sectionDir, fileForCategory.getValue(category))
        return settingsGroupRegex.findAll(file.readText()).map { it.groupValues[1] }.toList()
    }

    // ── (a) every registry entry is rendered on its declared page ────────────────────────────────

    @Test
    fun everyRegistryOptionIsRenderedOnItsDeclaredPage() {
        for (category in SettingsCategory.entries) {
            val rendered = renderedRowIds(category).toSet()
            val registered = SettingsRegistry.optionsFor(category).map { it.id }.toSet()
            assertEquals(
                registered,
                rendered,
                "Page $category and the registry disagree about which options it owns " +
                    "(missing on screen: ${(registered - rendered)}, not in registry: ${(rendered - registered)})",
            )
        }
    }

    // ── (b) every rendered row is registered, and registered to *this* page ──────────────────────

    @Test
    fun everyRenderedRowIdIsRegistered() {
        SettingsCategory.entries.forEach { category ->
            renderedRowIds(category).forEach { id ->
                assertNotNull(SettingsRegistry.find(id), "SettingRow(\"$id\") on $category has no registry entry")
                // require() is the runtime guard the app relies on; assert it does not throw here either.
                SettingsRegistry.require(id)
            }
        }
    }

    @Test
    fun everyRenderedRowBelongsToItsOwnPage() {
        for (category in SettingsCategory.entries) {
            renderedRowIds(category).forEach { id ->
                val option = SettingsRegistry.find(id)
                assertNotNull(option, "SettingRow(\"$id\") on $category is not registered")
                assertEquals(
                    category,
                    option.category,
                    "SettingRow(\"$id\") is rendered on $category but registered to ${option.category}",
                )
            }
        }
    }

    @Test
    fun noPageRendersTheSameRowTwice() {
        for (category in SettingsCategory.entries) {
            val ids = renderedRowIds(category)
            assertEquals(ids.size, ids.toSet().size, "Duplicate SettingRow id on $category: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        }
    }

    @Test
    fun everyRenderedGroupHeaderIsBackedByTheRegistry() {
        for (category in SettingsCategory.entries) {
            val groupsForPage = SettingsRegistry.optionsFor(category).map { it.groupKey }.toSet()
            // A header renders only when it can title itself and holds at least one option on this page.
            renderedGroupKeys(category).forEach { key ->
                assertTrue(key in groupsForPage, "SettingsGroup(\"$key\") on $category hosts no registered option")
                assertTrue(
                    SettingsRegistry.optionsInGroup(key).isNotEmpty(),
                    "SettingsGroup(\"$key\") would render an empty header",
                )
            }
        }
    }

    // ── (c) searching an option's title finds it at (or tied for) the top ────────────────────────

    @Test
    fun searchingAnOptionTitleReturnsItFirstOrNearTop() {
        // The Settings tab indexes the English titles when the UI is English; a query equal to an option's
        // own title must surface that option ahead of unrelated rows. Titles shared by several options all
        // tie for the top slots, so the tolerance is the number of options carrying that exact title.
        val candidates = SearchSources.settingCandidates(EnStrings)
        val hits = HashMap<String, List<String>>()

        for (option in SettingsRegistry.options) {
            val title = option.title(EnStrings)
            val ranked = hits.getOrPut(title) {
                SearchSources.hits(SearchTab.Settings, title, emptyList(), EnStrings).map { it.id }
            }
            val tieCount = candidates.count { it.primary.equals(title, ignoreCase = true) }
            val position = ranked.indexOf(option.id)
            assertTrue(
                position in 0 until tieCount,
                "Searching \"$title\" did not surface ${option.id} at the top (position=$position, ranked=${ranked.take(5)})",
            )
        }
    }

    /** Walks up from the test's working dir to the repo root (the dir holding settings.gradle.kts). */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from ${File("").absolutePath}")
    }
}
