package com.guildworkman.api.data.constants;

/**
 * Status of a derived {@code Transaction} row.
 *
 * <p>{@code PAID} and {@code RECEIVED} predate the ledger and are kept so
 * existing rows keep their meaning. The remaining values were added when
 * {@code Transaction} became a projection of the payments ledger — a payment
 * can end up failed, refunded or reversed, and a projection that could only
 * say "paid" would have to misreport three of the five outcomes.
 *
 * <p><b>Adding a value here is not a code-only change.</b> The column is
 * {@code @Enumerated(STRING)}, so no existing row changes and no ordinal
 * shifts underneath one — but Hibernate also generated a {@code CHECK}
 * constraint over the values it knew about when it first created the table,
 * and {@code ddl-auto=update} cannot widen an existing check constraint. Any
 * database that already has a {@code transactions} table needs
 * {@code ALTER TABLE transactions DROP CONSTRAINT IF EXISTS
 * transactions_transaction_status_check;} run once, or every insert of a new
 * status fails. A freshly-created database gets the full constraint and needs
 * nothing. See backend-api/docs/PAYMENTS_LEDGER.md, "Schema / migrations".
 */
public enum TransactionStatus {

    /** The client's money was captured. */
    PAID,

    /** Funds reached the skilled worker. */
    RECEIVED,

    /** Initiated but not yet resolved by the provider. */
    PENDING,

    /** The provider declined the charge, or the client never completed it. */
    FAILED,

    /** All or part of the captured amount was returned to the client. */
    REFUNDED,

    /** The provider clawed the payment back. */
    REVERSED
}
