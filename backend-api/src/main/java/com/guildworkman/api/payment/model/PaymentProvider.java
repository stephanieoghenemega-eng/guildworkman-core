package com.guildworkman.api.payment.model;

/**
 * Which rail moved the money. The fiat leg is the only one this PR
 * implements; {@link #STELLAR} exists because the reconciliation model has to
 * leave room for the on-chain leg (tracked separately in
 * {@code com.guildworkman.api.escrow}) to post into the same books later.
 *
 * <p>Nothing writes {@link #STELLAR} today. Carrying the column now costs one
 * varchar and means the on-chain leg can be added without a schema change to
 * a table that will, by then, hold production money records — a table this
 * codebase manages with {@code ddl-auto=update}, which cannot express a safe
 * backfill of a new non-null discriminator.
 */
public enum PaymentProvider {

    /** Cards and bank transfers via Paystack. */
    PAYSTACK,

    /** Reserved for the on-chain settlement leg; not yet produced by any code path. */
    STELLAR
}
