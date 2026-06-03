package com.otapp.hmis.billing.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One claim line per covered {@link PatientBill} attached to a {@link PatientInvoice}.
 *
 * <p>CR-14: {@code patient_bill_id} is NOT NULL and UNIQUE (de-facto one detail per bill).
 * The legacy {@code optional=true} on the annotation was an internal inconsistency
 * (nullable=false on the JoinColumn) — we resolve it as NOT NULL here (CR-14 ratified).
 *
 * <p>ON DELETE CASCADE from invoice is handled by JPA orphanRemoval=true on the parent
 * {@link PatientInvoice#details} collection.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/PatientInvoiceDetail.java:39</li>
 *   <li>detail.status = "PAID": PatientBillResource.java:341</li>
 *   <li>coverage_status: denormalized snapshot at attach time (build-spec §1.2)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_invoice_details")
public class PatientInvoiceDetail extends AuditableEntity {

    /** The invoice header this line belongs to. */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_invoice_id", nullable = false, updatable = false)
    private PatientInvoice invoice;

    /**
     * The bill this detail line represents. NOT NULL + UNIQUE (CR-14).
     * Each bill can appear on at most one invoice detail.
     */
    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_bill_id", nullable = false, unique = true, updatable = false)
    private PatientBill bill;

    /** Human-readable description (mirrors bill description at attach time). */
    @NotBlank
    @Column(name = "description", length = 500, nullable = false)
    private String description;

    /** Quantity at attach time. */
    @NotNull
    @Column(name = "qty", precision = 19, scale = 6, nullable = false)
    private BigDecimal qty;

    /**
     * Amount at attach time (mirrors bill.amount at attach; NUMERIC(19,2)).
     * This is the claim value for this line.
     */
    @NotNull
    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Payment status for this detail line.
     * NULL at creation; set to PAID when the cashier pays the bill
     * (PatientBillResource.java:341).
     * Plain nullable String (legacy is a free String: NULL | "PAID") — NOT an enum, so no @Enumerated.
     */
    @Column(name = "status", length = 20)
    private String status;                // nullable: NULL | "PAID" (PARITY)

    /**
     * Denormalized snapshot of bill coverage at attach time.
     * COVERED = insurance hit; VERIFIED = inpatient cash fallback; UNPAID = cash.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_status", length = 20, nullable = false)
    private CoverageStatus coverageStatus = CoverageStatus.UNPAID;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create an invoice detail attached to the given invoice and bill.
     *
     * @param invoice        the parent invoice
     * @param bill           the bill being claimed
     * @param coverageStatus snapshot of the bill's coverage at attach time
     */
    public PatientInvoiceDetail(PatientInvoice invoice, PatientBill bill,
                                CoverageStatus coverageStatus) {
        this.invoice = invoice;
        this.bill = bill;
        this.description = bill.getDescription();
        this.qty = bill.getQty();
        this.amount = bill.amountValue();
        this.status = null;  // null = unpaid detail (PARITY)
        this.coverageStatus = coverageStatus;
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /**
     * Mark this detail as PAID (cashier collected the linked bill).
     * PatientBillResource.java:341.
     */
    public void markPaid() {
        this.status = "PAID";
    }

    /** Whether this detail has been paid. */
    public boolean isPaid() {
        return "PAID".equals(this.status);
    }
}
