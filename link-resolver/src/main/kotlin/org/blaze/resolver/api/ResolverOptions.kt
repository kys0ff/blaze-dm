package org.blaze.resolver.api

import kotlinx.serialization.Serializable

/**
 * The kind of value a [ResolverOption] holds, flattened onto the option itself
 * ([choices] / [minimum] / [maximum] are only meaningful for the matching kind).
 *
 * A sealed hierarchy rather than a plain enum because a select option needs its choice
 * list and an integer option its bounds carried with the descriptor; the UI renders
 * each type from these fields without asking the plugin anything.
 */
@Serializable
sealed interface ResolverOptionType {

    /** A free-form single-line string (a URL, a user agent, a token header name...). */
    @Serializable data object Text : ResolverOptionType

    /** A checked / unchecked preference. Persisted as `"true"` / `"false"`. */
    @Serializable data object Boolean : ResolverOptionType

    /** A whole number clamped to [[minimum], [maximum]]. Persisted as its decimal text. */
    @Serializable data class Integer(val minimum: Long, val maximum: Long) : ResolverOptionType

    /** One value out of [choices]. Persisted as the chosen choice verbatim. */
    @Serializable data class Select(val choices: List<String>) : ResolverOptionType
}

/**
 * One user-editable setting a [ConfigurableLinkResolver] declares, in the shape the
 * Settings screen and the Extensions panel render directly — no plugin code runs to
 * build the form.
 *
 * [label] and [description] are plain strings owned by the plugin: extensions are
 * third-party jars without access to the app's localization catalog, so they ship
 * their own wording.
 */
@Serializable
data class ResolverOption(
    /** Stable key the handler reads its value from; unique within one resolver. */
    val key: String,
    /** Short label rendered next to the control. */
    val label: String,
    /** Optional one-line explanation shown under the control. */
    val description: String? = null,
    val type: ResolverOptionType = ResolverOptionType.Text,
    /** Value used when the user has never touched this option; also the reset target. */
    val defaultValue: String = "",
)

/** Convenience constructors so descriptors read without naming the carrier type. */
fun textOption(key: String, label: String, description: String? = null, default: String = "") =
    ResolverOption(key, label, description, ResolverOptionType.Text, default)

fun booleanOption(key: String, label: String, description: String? = null, default: Boolean = false) =
    ResolverOption(key, label, description, ResolverOptionType.Boolean, default.toString())

fun integerOption(key: String, label: String, description: String? = null, range: LongRange, default: Long) =
    ResolverOption(
        key, label, description,
        ResolverOptionType.Integer(range.first, range.last), default.toString()
    )

fun selectOption(key: String, label: String, description: String? = null, choices: List<String>, default: String) =
    ResolverOption(key, label, description, ResolverOptionType.Select(choices), default)

/**
 * A handler's own persisted option values, keyed by [ResolverOption.key]. Missing keys
 * fall back to the descriptor's default when read through [value]/[booleanValue]/...
 *
 * Named *Config* (not *Settings*) to keep it clearly distinct from the subsystem's
 * persisted [org.blaze.resolver.core.ResolverSettings]; values are plain strings
 * (booleans as `"true"`/`"false"`, integers as decimal text) so the on-disk format in
 * `resolvers.json` stays trivially extensible: a new option type never changes the
 * file schema.
 */
@Serializable
data class ResolverConfig(
    val values: Map<String, String> = emptyMap()
) {
    fun value(key: String, default: String): String = values[key] ?: default

    fun booleanValue(key: String, default: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: default

    /** The stored integer, clamped into [range]; [default] when absent or unparsable. */
    fun intValue(key: String, default: Long, range: LongRange): Long =
        (values[key]?.toLongOrNull() ?: default).coerceIn(range)

    /** The stored choice, or [default] when the value is absent or no longer offered. */
    fun selectValue(key: String, choices: List<String>, default: String): String =
        values[key]?.takeIf { it in choices } ?: default
}

/**
 * Subsystem-wide settings handed to a [ConfigurableLinkResolver] at resolve time —
 * values that belong to Blaze as a whole rather than to one handler.
 */
data class ResolverContext(
    /** HTTP connect/read timeout the handler should give its requests, seconds. */
    val requestTimeoutSeconds: Int,
)
