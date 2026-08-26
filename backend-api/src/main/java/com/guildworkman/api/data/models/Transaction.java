package com.guildworkman.api.data.models;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.guildworkman.api.data.constants.TransactionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A client-facing view of one payment.
 *
 * <p>Since the payments ledger landed this is a <b>projection</b>, rebuilt
 * from the {@code Payment} aggregate by {@code TransactionService} — see that
 * interface for why it is a view rather than a second set of books.
 * {@link #paymentReference} is the link back to the payment (and through it
 * to the journal entries), and is what makes the projection idempotent:
 * refreshing a payment updates its one row instead of appending another.
 *
 * <p>The column is nullable and uniquely indexed. Nullable because rows
 * created before the ledger existed have no payment to point at; unique
 * because a payment must never acquire two projected rows. Postgres permits
 * any number of NULLs in a unique index, so the legacy rows coexist with the
 * constraint.
 */
@Setter
@Getter
@Entity
@Table(name = "transactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_transaction_payment_reference",
                columnNames = "payment_reference"))
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long transactionId;

    /** {@code Payment.reference} this row projects; null for pre-ledger rows. */
    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    private Long clientId;
    private Long skilledWorkerId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private TransactionStatus transactionStatus;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDate transactionDate;

}
