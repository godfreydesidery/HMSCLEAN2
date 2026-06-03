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
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Links one paid {@link PatientBill} to one {@link PatientPayment}.
 *
 * <p>PARITY: NO amount column (PatientPaymentDetail.java:37 — legacy has no amount field;
 * the paid amount is implicit = the linked bill's amount). [GATED:CR-02] adds the amount
 * column for partial-payment support. [GATED:CR-03] would use signed amounts for refund.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/PatientPaymentDetail.java:37</li>
 *   <li>Creation: PatientBillResource.java:310-320 (description=bill.description, status=RECEIVED)</li>
 *   <li>REFUNDED flip on cancel: PatientResource.java:636-639</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_payment_details")
public class PatientPaymentDetail extends AuditableEntity {

    /** The payment receipt this detail belongs to. */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_payment_id", nullable = false, updatable = false)
    private PatientPayment payment;

    /**
     * The bill this payment detail covers. One-to-one; NOT NULL.
     * Each bill has at most one payment detail (UNIQUE on patient_bill_id).
     */
    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_bill_id", nullable = false, unique = true, updatable = false)
    private PatientBill bill;

    /** Description copied from the bill at creation time (PatientBillResource.java:311). */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * RECEIVED at creation; flipped to REFUNDED on cancel (PatientResource.java:636-639).
     * This flag IS the reversal signal — no negative amount row is created.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PaymentDetailStatus status = PaymentDetailStatus.RECEIVED;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a payment detail linking a bill to a payment.
     * PatientBillResource.java:310-320.
     */
    public PatientPaymentDetail(PatientPayment payment, PatientBill bill) {
        this.payment = payment;
        this.bill = bill;
        this.description = bill.getDescription();
        this.status = PaymentDetailStatus.RECEIVED;
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /**
     * Soft-reverse this payment detail on bill cancellation.
     * PatientResource.java:636-639 (CR-13 standard pattern).
     */
    public void markRefunded() {
        this.status = PaymentDetailStatus.REFUNDED;
    }
}
