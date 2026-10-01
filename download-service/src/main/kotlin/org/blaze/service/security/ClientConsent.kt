package org.blaze.service.security

/**
 * The user's answer to a third-party client that wants to use the local API.
 *
 * The three outcomes mirror the consent dialog's buttons: the client is told exactly what accepting
 * means (full control over downloads through the loopback API) and chooses between a one-time
 * permission, a durable one, or a refusal.
 */
enum class ConsentDecision {
    /** Allow this connection only; the user is asked again when the app restarts. */
    ALLOW_ONCE,

    /** Allow and remember: the client is persisted as approved and never prompts again. */
    ALLOW_ALWAYS,

    /** Refuse; the request is rejected with a permission error. */
    DENY,
}

/**
 * Who a connecting client claims to be, as shown to the user in the consent prompt.
 *
 * [id] is the stable key an approval is remembered under: the client-supplied
 * [org.blaze.download.api.ApiHeaders.CLIENT_ID] when present, otherwise a server-derived fallback
 * (user agent / address). [displayName] is the human label the dialog prints. Identification is
 * advisory, not authentication — the bearer-token gate has already run before a client is ever asked
 * about; the consent layer exists so a *person* decides whether an external program may drive Blaze,
 * not to stop a caller that already stole the token.
 */
data class ClientIdentity(
    val id: String,
    val displayName: String,
    val userAgent: String,
)

/**
 * The port the transport consults before letting an authenticated-but-unrecognized client through.
 *
 * The service itself has no UI, so the decision is delegated: the desktop app implements this by
 * raising a consent dialog and suspending until the user answers, while headless runs install
 * [ALLOW_ALL] (there is no one to ask). The gate must be safe to call concurrently; the transport
 * serializes calls per client id so a chatty client cannot stack up duplicate prompts.
 */
fun interface ClientConsentGate {
    /** Called once per new client per process; blocks the caller's request until answered. */
    suspend fun decide(client: ClientIdentity): ConsentDecision

    companion object {
        /** A gate that never asks — the default for headless/service runs with no UI to prompt in. */
        val ALLOW_ALL: ClientConsentGate = ClientConsentGate { ConsentDecision.ALLOW_ALWAYS }
    }
}
