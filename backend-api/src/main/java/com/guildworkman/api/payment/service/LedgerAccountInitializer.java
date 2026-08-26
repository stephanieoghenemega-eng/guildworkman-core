package com.guildworkman.api.payment.service;

import com.guildworkman.api.payment.model.LedgerAccount;
import com.guildworkman.api.payment.model.LedgerAccounts;
import com.guildworkman.api.payment.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Seeds the chart of accounts on startup, once, idempotently.
 *
 * <p>Runs as an {@link ApplicationRunner} rather than a {@code data.sql}
 * script so the account list has exactly one definition
 * ({@link LedgerAccounts}) that both the seeding and the posting rules read —
 * a SQL script would be a second copy to drift out of step with the code that
 * posts to it.
 *
 * <p>Two instances starting together both see "missing" and both insert; the
 * unique index on {@code code} decides, and the loser's violation is caught
 * and ignored, because "someone else already created it" is precisely the
 * outcome this wants.
 */
@Component
@RequiredArgsConstructor
public class LedgerAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LedgerAccountInitializer.class);

    private final LedgerAccountRepository accounts;

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (LedgerAccount account : LedgerAccounts.chartOfAccounts()) {
            if (accounts.findByCode(account.getCode()).isPresent()) {
                continue;
            }
            try {
                accounts.save(account);
                created++;
            } catch (DataIntegrityViolationException ex) {
                log.debug("Ledger account {} was created concurrently by another instance", account.getCode());
            }
        }
        if (created > 0) {
            log.info("Seeded {} ledger account(s); chart of accounts now has {}", created, accounts.count());
        }
    }
}
