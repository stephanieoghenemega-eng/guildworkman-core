package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.models.Transaction;
import com.guildworkman.api.data.repository.TransactionRepository;
import com.guildworkman.api.services.ServiceUtils.TransactionHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Query-only history over the {@code Transaction} projection; see {@link TransactionHistoryService}. */
@Service
@RequiredArgsConstructor
public class TransactionHistoryServiceImpl implements TransactionHistoryService {

    /** Bound on {@code limit} so a caller can't ask for the whole table in one request. */
    private static final int MAX_LIMIT = 200;

    private final TransactionRepository transactions;

    @Override
    @Transactional(readOnly = true)
    public List<Transaction> historyForClient(Long clientId, int limit) {
        return transactions.findByClientIdOrderByTransactionIdDesc(clientId, PageRequest.of(0, clamp(limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Transaction> historyForSkilledWorker(Long skilledWorkerId, int limit) {
        return transactions.findBySkilledWorkerIdOrderByTransactionIdDesc(
                skilledWorkerId, PageRequest.of(0, clamp(limit)));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
