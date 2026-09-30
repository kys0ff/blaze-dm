package org.blaze.theming.api

/**
 * The optional facet of a [BlazeThemeProvider] that publishes user-editable settings.
 *
 * Mirrors `ConfigurableLinkResolver` in shape: it **extends** the base contract and every
 * member has a default, so a plain provider keeps working untouched and the same
 * `META-INF/services/org.blaze.theming.api.BlazeThemeProvider` entry discovers it — no second
 * service file.
 *
 * Unlike its resolver twin, this facet is deliberately **stateless at render time**: a theme's
 * answers are applied by the app, not by the plugin. [optionDescriptors] is read to build the
 * Settings form, the values are persisted, and the registry merges them into the resolved
 * palette — color options through their declared [ThemeOption.colorTarget], everything else by
 * handing the [ThemeConfig] to [applySettings]. `provide()` is therefore never called with a
 * promise that the plugin remembered the user's edit; a provider that keeps its own state must
 * adopt it in [applySettings].
 *
 * As with [BlazeThemeProvider], every member is invoked on the UI thread and must be cheap,
 * blocking-free and side effect free.
 */
interface ConfigurableBlazeThemeProvider : BlazeThemeProvider {

    /**
     * The options the user may edit for this theme in Settings → Themes. Descriptors are static
     * data: the app renders the form from them and stores the answers itself.
     */
    val optionDescriptors: List<ThemeOption> get() = emptyList()

    /**
     * Adopt [settings] — the values the app merged from descriptor defaults and what the user
     * persisted. Called before [provide] whenever the selection or the stored values change;
     * implementations must treat it as pure assignment, with no I/O.
     */
    fun applySettings(settings: ThemeConfig) {}

    /** The configuration currently in effect. Must mirror the last [applySettings]. */
    fun settings(): ThemeConfig = ThemeConfigDefaults.fromDescriptors(optionDescriptors)
}
