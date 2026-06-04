package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Prescription batch linkage — near-inert traceability child (PrescriptionBatch.java:34-48).
 *
 * <p>Maps the V27 {@code prescription_batches} table. No consuming logic — this entity exists
 * to record which pharmacy batch number was used when dispensing. No status field; no lifecycle.
 *
 * <p>Fields (PrescriptionBatch.java:38-47):
 * <ul>
 *   <li>{@code no} — free-text batch number (NOT NULL, no generator, @NotBlank).</li>
 *   <li>{@code manufacturedDate} — manufacturing date (nullable DATE).</li>
 *   <li>{@code expiryDate} — expiry date (nullable DATE).</li>
 *   <li>{@code qty} — quantity in this batch (NUMERIC(19,6), legacy double → BigDecimal).</li>
 *   <li>{@code prescription} — intra-module @ManyToOne FK to the parent prescription.</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PrescriptionBatch.java:34-48 (:38-39 free-text no, :44-47 prescription FK)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "prescription_batches")
public class PrescriptionBatch extends AuditableEntity {

    /**
     * Free-text batch number (not generated — supplied by caller).
     * NOT NULL and non-blank (PrescriptionBatch.java:38-39).
     */
    @NotBlank
    @Column(name = "no", length = 100, nullable = false)
    private String no;

    /** Manufacturing date (nullable DATE — PrescriptionBatch.java:40-41). */
    @Column(name = "manufactured_date")
    private LocalDate manufacturedDate;

    /** Expiry date (nullable DATE — PrescriptionBatch.java:42-43). */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * Quantity in this batch (legacy double → NUMERIC(19,6), pre-approved).
     * PrescriptionBatch.java:44-45.
     */
    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal qty = BigDecimal.ZERO;

    /**
     * Parent prescription (intra-module real FK — PrescriptionBatch.java:46-47).
     * @ManyToOne — a prescription may have multiple batch records.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id", nullable = false, updatable = false)
    private Prescription prescription;

    /**
     * Business constructor (PrescriptionBatch.java:34-48).
     *
     * @param prescription    the parent prescription (intra-module FK)
     * @param no              free-text batch number (not blank)
     * @param manufacturedDate manufacturing date (nullable)
     * @param expiryDate       expiry date (nullable)
     * @param qty              quantity in this batch
     */
    public PrescriptionBatch(Prescription prescription, String no,
                              LocalDate manufacturedDate, LocalDate expiryDate,
                              BigDecimal qty) {
        this.prescription = prescription;
        this.no = no;
        this.manufacturedDate = manufacturedDate;
        this.expiryDate = expiryDate;
        this.qty = qty != null ? qty : BigDecimal.ZERO;
    }
}
