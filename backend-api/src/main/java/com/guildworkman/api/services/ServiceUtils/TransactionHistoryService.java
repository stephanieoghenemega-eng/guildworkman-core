package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.data.models.Transaction;

import java.util.List;

/**
 * Read-side history over the {@code Transaction} projection.
 *
 * <p><b>Why nothing writes the {@code TransactionHistory} entity.</b> A
 * persisted history table would be a third copy of facts the ledger already
 * holds — one that can drift, and that has to be repaired by hand when it
 * does. History here is a query, so it cannot disagree with the projection it
 * reads, and the projection cannot disagree with the ledger it is rebuilt
 * from. The existing {@code TransactionHistory} entity and its repository are
 * left untouched: its mapping predates this work and changing its identity
 * column would be a destructive schema change under
 * {@code ddl-auto=update}. See docs/PAYMENTS_LEDGER.md, "Derived views".
 */
public interface TransactionHistoryService {

    /** Most recent first. */
    List<Transaction> historyForClient(Long clientId, int limit);

    /** Most recent first. */
    List<Transaction> historyForSkilledWorker(Long skilledWorkerId, int limit);
}
