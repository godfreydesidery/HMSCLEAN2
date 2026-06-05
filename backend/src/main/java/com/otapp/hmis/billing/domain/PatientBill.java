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

    /**
     * Loose cross-module ref to the admission that generated this charge (nullable).
     *
     * <p>Populated only for ward-bed and consumable charges created during an inpatient
     * admission (inc-07 07a). Null for all outpatient / OTC / registration charges.
     * Used by {@link com.otapp.hmis.billing.api.BillingQueries#admissionHasOutstandingBills}
     * to scan all bills linked to an admission for the discharge gate
     * (PatientResource.java:5342-5357).
     *
     * <p>Column added by V45 migration (nullable VARCHAR(26) — no physical FK; the admission
     * lives in the inpatient module, a different bounded context, ADR-0008 §1).
     */
    @Column(name = "admission_uid", length = 26)
    private String admissionUid;

    /** Loose cross-module ref to the business day. */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false)
    private String businessDayUid;

    /**
     * Settlement flag (CR-05, RATIFIED scoped) — net-new; legacy has none. {@code true} once the
     * charge's cash payment obligation has cleared at the cashier. Set by
     * {@link com.otapp.hmis.billing.application.SettlementDispatcher} on the PAID transition.
     * COVERED (insurance) bills leave this {@code false} — the pay-before-service policy auto-passes
     * them on {@code paymentType} ({@link com.otapp.hmis.billing.api.SettlementPolicy}).
     */
    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    /** Instant the bill was settled (cash collected); null until settled. */
    @Column(name = "settled_at")
    private java.time.Instant settledAt;

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
     * Mark this bill as a follow-up no-charge (CR-20 — PatientServiceImpl.java:467-469).
     * Sets status=NONE with amount/paid/balance all zero, satisfying the invariant.
     * Called when {@code followUp == true} on a CONSULTATION charge — no price resolution
     * is performed; the bill is created with {@code Money.zero()} and immediately marked NONE.
     */
    public void markNoCharge() {
        this.amount  = Money.zero();
        this.paid    = Money.zero();
        this.balance = Money.zero();
        this.status  = BillStatus.NONE;
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
     * Mark the charge settled (CR-05, RATIFIED scoped) — set by
     * {@link com.otapp.hmis.billing.application.SettlementDispatcher} in the same tx as the cash
     * PAID transition. Idempotent; net-new (no legacy equivalent).
     *
     * @param at the settlement instant (from the operation's audit context)
     */
    public void markSettled(java.time.Instant at) {
        this.settled = true;
        this.settledAt = at;
    }

    /**
     * Override the {@code billItem} label and/or {@code description} on this bill
     * (inc-07 CR-07-Q13-billing-display).
     *
     * <p>Used exclusively by {@code BillingCommandsImpl.recordClinicalCharge} when the
     * {@link com.otapp.hmis.billing.api.ChargeRequest} carries non-null {@code billItem} or
     * {@code description} overrides — e.g. {@code "Medication"} / {@code "Consumable: <name>"}
     * for inpatient consumable/dressing charges. When the caller passes {@code null} for either
     * field this method is not called; the bill retains the {@code labelFor(kind)} default set
     * at construction. Existing caller output is therefore fully preserved (no behavioural change
     * on any existing path).
     *
     * <p>Both parameters are nullable; a null value leaves the corresponding field unchanged.
     * Neither field may ever contain PHI.
     *
     * @param billItem    replacement bill-item label (nullable)
     * @param description replacement description (nullable)
     */
    public void overrideBillLabels(String billItem, String description) {
        if (billItem != null) {
            this.billItem = billItem;
        }
        if (description != null) {
            this.description = description;
        }
    }

    /**
     * Link this bill to an inpatient admission (inc-07 07a).
     *
     * <p>Set at ward-bed charge creation time so that
     * {@link com.otapp.hmis.billing.api.BillingQueries#admissionHasOutstandingBills} can scan
     * outstanding bills by admission uid (discharge gate — PatientResource.java:5342-5357).
     * Null for all non-admission charges (outpatient, OTC, registration).
     *
     * <p>Called by {@code BillingCommandsImpl.recordClinicalCharge} when
     * {@link com.otapp.hmis.billing.api.ChargeRequest#admissionUid()} is non-null.
     *
     * @param admissionUid loose uid of the owning admission (no FK — ADR-0008 §1)
     */
    public void linkAdmission(String admissionUid) {
        this.admissionUid = admissionUid;
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
