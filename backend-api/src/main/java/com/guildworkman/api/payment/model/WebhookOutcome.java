package com.guildworkman.api.payment.model;

/**
 * What handling a provider event actually did. Returned to the webhook
 * endpoint so the response body can say which case applied — all of them
 * answer HTTP 200, because every one of them means "we have durably taken
 * responsibility for this event, stop retrying it".
 */
public enum WebhookOutcome {

    /** The event advanced state and (where money moved) posted a journal entry. */
    APPLIED,

    /** Already handled — a provider retry of an event whose effect is already in the books. */
    DUPLICATE,

    /**
     * Understood, but not applicable to the platform's state — e.g. an event
     * type this service does not act on. Recorded so the audit trail shows it
     * arrived.
     */
    IGNORED,

    /**
     * Refused: the event could not legally be applied to the current state
     * (see {@link PaymentStatus}), or it named a payment this platform has no
     * record of. A {@code ReconciliationDiscrepancy} is recorded for an
     * operator. Still a 200 — retrying will not make it legal, and leaving
     * the provider to retry forever hides the divergence instead of
     * surfacing it.
     */
    REJECTED
}
