package com.otapp.hmis.billing.domain;

import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.domain.AuditableEntity;
import com.otapp.hmis.shared.domain.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The atomic charge line in the billing system (one per chargeable clinical/registration/ward item).
 *
 * <p>Invariant: {@code paid + balance == amount} (enforced by all domain methods).
 * State transitions are via intention-revealing domain methods only — no public setters.
 *
 * <p>Cross-module refs ({@code patientUid}, {@code planUid}) are loose VARCHAR(26) uids with
 * NO FK (module boundary rule, ADR-0008). Intra-module self-references ({@code principalBill},
 * {@code supplementaryBill}) use real FKs for the ward top-up plumbing (CR-11, deferred).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/PatientBill.java:40</li>
 *   <li>Two-step cash-first build: PatientServiceImpl.java:821-835 (lab exemplar)</li>
 *   <li>Insurance override: PatientServiceImpl.java:842-849</li>
 *   <li>Payment status transition: PatientBillResource.java:305-307</li>
 *   <li>Cancel soft-flag: PatientResource.java:627</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_bills")
public class PatientBill extends AuditableEntity {

    /** Loose cross-module ref to the patient (no FK — patient is in a different module). */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Legacy billItem free-text label (e.g. "Registration", "Consultation", "Lab Test").
     * Defaults to 'NA'; populated from {@link ServiceKind} label at construction.
     * PatientBill.java:44.
     */
    @Column(name = "bill_item", length = 60, nullable = false)
    private String billItem = "NA";

    /** Human-readable description of the charge. @NotBlank per PatientBill.java:46. */
    @NotBlank
    @Column(name = "description", length = 500, nullable = false)
    private String description;

    /**
     * Normalised service category (replaces legacy free-text inference from billItem).
     * Stored as VARCHAR via @Enumerated(STRING).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private ServiceKind kind;

    /** Quantity (legacy: double; replaced with NUMERIC(19,6) per pre-approved directive). */
    @NotNull
    @Column(name = "qty", precision = 19, scale = 6, nullable = false)
    private BigDecimal qty = BigDecimal.ONE;

    /**
     * Total charge amount (legacy: double; replaced with Money embeddable NUMERIC(19,2) HALF_UP).
     * Column overrides disambiguate from paid/balance Money columns.
     */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "amount",   precision = 19, scale = 2, nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "amount_currency", length = 3, nullable = false))
    })
    private Money amount;

    /** Amount already paid (0 at creation; set to amount on markPaid() or override()). */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "paid",   precision = 19, scale = 2, nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "paid_currency", length = 3, nullable = false))
    })
    private Money paid;

    /** Outstanding balance (= amount - paid; 0 when COVERED or PAID). */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "balance",   precision = 19, scale = 2, nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "balance_currency", length = 3, nullable = false))
    })
    private Money balance;

    /** Lifecycle status of this charge. PatientBill.java:55. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BillStatus status;

    /** Payment mode applied to this charge. Defaults to CASH at creation. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20, nullable = false)
    private PaymentMode paymentType = PaymentMode.CASH;

    /** Insurance membership number (set when paymentType=INSURANCE). PatientBill.java:58. */
    @Column(name = "membership_no", length = 100)
    private String membershipNo;

    /** Loose cross-module ref to the insurance plan (no FK). Null for cash charges. */
    @Column(name = "plan_uid", length = 26)
    private String planUid;

    /**
     * Self-reference for ward top-up: the covered principal ward bill.
     * Populated on the supplementary (top-up) bill. (CR-11 schema plumbing only in P1.)
     * PatientBill.java:65-68.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "principal_bill_id")
    private PatientBill principalBill;

    /**
     * Self-reference for ward top-up: the UNPAID top-up supplementary bill.
     * Populated on the covered principal ward bill. (CR-11 schema plumbing only in P1.)
     * PatientBill.java:70-73.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplementary_bill_id")
    private PatientBill supplementaryBill;

    /** Loose cross-module ref to the business day. */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor — STEP 1: always build at CASH price, status=UNPAID
    // PatientServiceImpl.java:821-835 (lab exemplar)
    // -------------------------------------------------------------------------

    /**
     * Business constructor for a new cash-first bill (Step 1 of the two-step build).
     *
     * @param patientUid   loose ref to the patient
     * @param kind         service category
     * @param billItem     legacy label (e.g. "Lab Test")
     * @param description  human-readable description
     * @param qty          quantity (1 for most services; multiplied for MEDICINE)
     * @param cashAmount   the cash price (already qty-multiplied for MEDICINE)
     * @param businessDayUid the current open business day uid
     */
    public PatientBill(String patientUid, ServiceKind kind, String billItem,
                       String description, BigDecimal qty, Money cashAmount,
                       String businessDayUid) {
        this.patientUid = patientUid;
        this.kind = kind;
        this.billItem = billItem != null ? billItem : "NA";
        this.description = description != null ? description : "NA";
        this.qty = qty != null ? qty : BigDecimal.ONE;
        this.amount = cashAmount;
        this.paid = Money.zero();
        this.balance = cashAmount;
        this.status = BillStatus.UNPAID;
        this.paymentType = PaymentMode.CASH;
        this.businessDayUid = businessDayUid;
        assertInvariant();
    }

    // -------------------------------------------------------------------------
    // Domain methods — intention-revealing state transitions
    // -------------------------------------------------------------------------

    /**
     * Set status to VERIFIED (registration regFee==0, or inpatient-no-covered fallback).
     * Amount/paid/balance unchanged — only status transitions.
     * PatientServiceImpl.java:276 (regFee==0), :917 (inpatient fallback).
     */
    public void markVerified() {
        this.status = BillStatus.VERIFIED;
    }

    /**
     * STEP 2 insurance override: overwrite the cash bill with plan price and mark COVERED.
     * Sets paid=planAmt, balance=0. PatientServiceImpl.java:842-849.
     *
     * @param planAmount  the insurance plan price (already qty-multiplied for MEDICINE)
     * @param planUid     loose ref to the insurance plan
     * @param membershipNo patient's insurance membership number
     */
    public void overrideWithInsurance(Money planAmount, String planUid, String membershipNo) {
        this.amount = planAmount;
        this.paid = planAmount;
        this.balance = Money.zero();
        this.status = BillStatus.COVERED;
        this.paymentType = PaymentMode.INSURANCE;
        this.planUid = planUid;
        this.membershipNo = membershipNo;
        assertInvariant();
    }

    /**
     * Cashier collection — mark this bill as PAID.
     * Sets paid=amount, balance=0. PatientBillResource.java:305-307.
     */
    public void markPaid() {
        this.paid = this.amount;
        this.balance = Money.zero();
        this.status = BillStatus.PAID;
        assertInvariant();
    }

    /**
     * Soft-cancel (CR-13 standard pattern for all kinds).
     * Sets status=CANCELED; does NOT delete. PatientResource.java:627.
     */
    public void cancel() {
        this.status = BillStatus.CANCELED;
    }

    /**
     * Wire up the ward top-up self-link on the principal covered bill.
     * CR-11 schema plumbing only in P1 — selection logic deferred.
     */
    public void linkSupplementaryBill(PatientBill supplementary) {
        this.supplementaryBill = supplementary;
    }

    /**
     * Wire up the ward top-up self-link on the supplementary (top-up) bill.
     * CR-11 schema plumbing only in P1 — selection logic deferred.
     */
    public void linkPrincipalBill(PatientBill principal) {
        this.principalBill = principal;
    }

    // -------------------------------------------------------------------------
    // Helper: convenience access to the raw BigDecimal amount value
    // -------------------------------------------------------------------------

    /** The amount value as a plain BigDecimal (avoids callers reading into the VO). */
    public BigDecimal amountValue() {
        return amount.getAmount();
    }

    // -------------------------------------------------------------------------
    // Invariant
    // -------------------------------------------------------------------------

    private void assertInvariant() {
        // paid + balance must equal amount (NUMERIC(19,2) HALF_UP)
        BigDecimal paidAmt    = paid.getAmount();
        BigDecimal balanceAmt = balance.getAmount();
        BigDecimal totalAmt   = amount.getAmount();
        BigDecimal sum = paidAmt.add(balanceAmt).setScale(2, RoundingMode.HALF_UP);
        if (sum.compareTo(totalAmt.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalStateException(
                    "PatientBill invariant violated: paid(" + paidAmt
                    + ") + balance(" + balanceAmt + ") != amount(" + totalAmt + ")");
        }
    }
}
