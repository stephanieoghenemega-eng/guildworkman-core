package com.guildworkman.api.payment.service;

/** No reconciliation finding exists with that id. Rendered as a 404. */
public class DiscrepancyNotFoundException extends RuntimeException {

    public DiscrepancyNotFoundException(Long id) {
        super("No reconciliation discrepancy found with id " + id);
    }
}
