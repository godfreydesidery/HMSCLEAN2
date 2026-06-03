package com.otapp.hmis.billing.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.domain.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Cashier reconciliation ledger entry — one row per paid bill.
 *
 * <p>This is the REAL legacy cash-up source: the EOD collections report aggregates
 * {@code SUM(amount) GROUP BY (item_name, payment_channel)} over this table
 * (CollectionReport.java / PatientBillResource.java:327-337).
 *
 * <p>{@code paymentChannel} is hard-coded "Cash" at every legacy call site (PARITY).
 * Multi-mode is [GATED:CR-08].
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Collection.java:38</li>
 *   <li>Write site: PatientBillResource.java:327-337</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "collections")
public class Collection extends AuditableEntity {

    /** Loose cross-module ref to the patient (nullable per legacy). */
    @Column(name = "patient_uid", length = 26)
    private String patientUid;

    /**
     * FK to the paid bill (nullable in schema for safety; always non-null in P1 write path).
     * PatientBillResource.java:327.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_bill_id")
    private PatientBill bill;

    /** Amount collected = bill.amount (PatientBillResource.java:329). */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "amount",   precision = 19, scale = 2, nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "amount_currency", length = 3, nullable = false))
    })
    private Money amount;

    /**
     * Bill item label at collection time (PatientBillResource.java:331).
     * Defaults to 'NA'.
     */
    @NotBlank
    @Column(name = "item_name", length = 200, nullable = false)
    private String itemName = "NA";

    /**
     * Payment channel — hard-coded "Cash" at every legacy call site (PARITY).
     * PatientBillResource.java:332.
     */
    @NotBlank
    @Column(name = "payment_channel", length = 60, nullable = false)
    private String paymentChannel = "Cash";

    /**
     * Payment reference number — hard-coded "NA" at every legacy call site (PARITY).
     * PatientBillResource.java:333.
     */
    @NotBlank
    @Column(name = "payment_reference_no", length = 200, nullable = false)
    private String paymentReferenceNo = "NA";

    /** Loose cross-module ref to the business day. */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a collection entry (PatientBillResource.java:327-337).
     *
     * @param patientUid         loose ref to the patient
     * @param bill               the paid bill
     * @param amount             amount collected (= bill.amount)
     * @param itemName           bill item label
     * @param paymentChannel     "Cash" (PARITY)
     * @param paymentReferenceNo "NA" (PARITY)
     * @param businessDayUid     current open business day uid
     */
    public Collection(String patientUid, PatientBill bill, Money amount,
                      String itemName, String paymentChannel, String paymentReferenceNo,
                      String businessDayUid) {
        this.patientUid = patientUid;
        this.bill = bill;
        this.amount = amount;
        this.itemName = itemName != null ? itemName : "NA";
        this.paymentChannel = paymentChannel != null ? paymentChannel : "Cash";
        this.paymentReferenceNo = paymentReferenceNo != null ? paymentReferenceNo : "NA";
        this.businessDayUid = businessDayUid;
    }
}
