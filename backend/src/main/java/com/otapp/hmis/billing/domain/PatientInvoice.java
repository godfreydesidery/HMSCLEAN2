package com.otapp.hmis.billing.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Insurance-claim accumulator header (one PENDING per patient+plan combination).
 *
 * <p>PARITY status: {@link InvoiceStatus#PENDING} at creation;
 * {@link InvoiceStatus#APPROVED} batch-applied at the start of the next charge transaction
 * (PatientServiceImpl.java:586-590). {@code amountPaid} is a running sum of paid detail
 * amounts (PatientBillResource.java:341-349); the header is NEVER transitioned to PAID.
 *
 * <p>CR-14: dead fields {@code amountAllocated}/{@code amountUnallocated} are NOT carried
 * forward (never written in legacy — 01-extract-invoice-payment-core.md §1).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/PatientInvoice.java:43</li>
 *   <li>PENDING creation: PatientServiceImpl.java:342, :631, :871, :940</li>
 *   <li>APPROVED transition: PatientServiceImpl.java:586-590</li>
 *   <li>amountPaid running sum: PatientBillResource.java:341-349</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_invoices")
public class PatientInvoice extends AuditableEntity {

    /** Loose cross-module ref to the patient. */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose cross-module ref to the insurance plan.
     * NULL = cash invoice (no plan); non-NULL = insurance claim accumulator.
     */
    @Column(name = "plan_uid", length = 26)
    private String planUid;

    /** Invoice lifecycle status (PARITY: PENDING | APPROVED). */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    /**
     * Running sum of paid detail amounts.
     * Incremented by {@link #addToPaidAmount(BigDecimal)}.
     * Never decremented in PARITY (amountPaid recompute-on-refund is [GATED:CR-03b]).
     */
    @NotNull
    @Column(name = "amount_paid", precision = 19, scale = 2, nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /** Loose cross-module ref to the business day. */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false)
    private String businessDayUid;

    /**
     * Claim lines. orphanRemoval=true + CASCADE ALL means removing a detail
     * from this collection and flushing will delete the detail row.
     * (CR-10: parent invoice deleted only when zero details remain — NOT always.)
     * Lombok @Getter suppressed — hand-written getDetails() returns unmodifiable view.
     */
    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<PatientInvoiceDetail> details = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING invoice.
     *
     * @param patientUid     loose ref to the patient
     * @param planUid        loose ref to the insurance plan (null = cash invoice)
     * @param businessDayUid current open business day uid
     */
    public PatientInvoice(String patientUid, String planUid, String businessDayUid) {
        this.patientUid = patientUid;
        this.planUid = planUid;
        this.status = InvoiceStatus.PENDING;
        this.amountPaid = BigDecimal.ZERO;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /**
     * Attach a detail line to this invoice.
     * The detail's invoice back-reference must be set on the detail.
     */
    public void addDetail(PatientInvoiceDetail detail) {
        details.add(detail);
    }

    /**
     * Remove a detail line from this invoice (CR-10: parent deleted only when empty).
     */
    public void removeDetail(PatientInvoiceDetail detail) {
        details.remove(detail);
    }

    /**
     * Increment the running amountPaid sum (PatientBillResource.java:341-349).
     * HALF_UP at scale 2 matches NUMERIC(19,2) storage.
     */
    public void addToPaidAmount(BigDecimal detailAmount) {
        this.amountPaid = this.amountPaid.add(detailAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Batch-close this claim: transition PENDING → APPROVED
     * (PatientServiceImpl.java:586-590).
     */
    public void approve() {
        this.status = InvoiceStatus.APPROVED;
    }

    /** Whether this invoice has no detail lines (used for CR-10 delete guard). */
    public boolean isEmpty() {
        return details.isEmpty();
    }

    /** Immutable view of detail lines. */
    public List<PatientInvoiceDetail> getDetails() {
        return Collections.unmodifiableList(details);
    }
}
