package com.otapp.hmis.billing.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.domain.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payment receipt header — one per cashier payment action
 * (PatientBillResource.java:277-285).
 *
 * <p>The uid serves as the receipt anchor (no separate receipt sequence in legacy).
 * {@code patientUid} is a net-new attribution field (nullable for PARITY with legacy
 * which has no patient FK on PatientPayment.java:39).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/PatientPayment.java:39</li>
 *   <li>Payment creation: PatientBillResource.java:277-285</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_payments")
public class PatientPayment extends AuditableEntity {

    /**
     * Loose cross-module ref to the patient (nullable — legacy has no patient FK
     * on PatientPayment; added as net-new attribution field per build-spec §1.2).
     */
    @Column(name = "patient_uid", length = 26)
    private String patientUid;

    /**
     * Tendered total amount (sum of all selected bill amounts).
     * Exact-tender guard (CR-12): this must equal the sum of linked bill amounts.
     */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "amount",   precision = 19, scale = 2, nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "amount_currency", length = 3, nullable = false))
    })
    private Money amount;

    /** Payment mode for this receipt. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20, nullable = false)
    private PaymentMode paymentType;

    /** Always RECEIVED at creation (no other payment header status in legacy). */
    @NotNull
    @Column(name = "status", length = 20, nullable = false)
    private String status = "RECEIVED";

    /** Loose cross-module ref to the business day. */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new payment receipt header.
     *
     * @param patientUid     loose ref to the patient (nullable)
     * @param amount         tendered total
     * @param paymentType    payment mode
     * @param businessDayUid current open business day uid
     */
    public PatientPayment(String patientUid, Money amount, PaymentMode paymentType,
                          String businessDayUid) {
        this.patientUid = patientUid;
        this.amount = amount;
        this.paymentType = paymentType;
        this.status = "RECEIVED";
        this.businessDayUid = businessDayUid;
    }
}
