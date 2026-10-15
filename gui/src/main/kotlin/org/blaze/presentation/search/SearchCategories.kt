package org.blaze.presentation.search

/**
 * The tabs Blaze's Search Everywhere popup switches between. [All] interleaves the other kinds under
 * section headers; the rest scope results to a single [SearchKind]. [tabId] is the id the generic
 * island search layer routes on (it matches the corresponding provider's id).
 */
enum class SearchTab(val tabId: String) {
    All("all"),
    Downloads("downloads"),
    Actions("actions"),
    Settings("settings"),
}

/**
 * Which family a result belongs to, driving its section header and its activation. [groupId] is the
 * `SearchProvider.id` the island controller stores on each hit; Blaze's three providers register under
 * exactly these ids.
 */
enum class SearchKind(val groupId: String) {
    Download("downloads"),
    Action("actions"),
    Setting("settings"),
}
