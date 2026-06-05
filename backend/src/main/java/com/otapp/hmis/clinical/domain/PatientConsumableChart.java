package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Inpatient consumable issue record — a chart entry that creates a MEDICINE PatientBill
 * when a nurse records a consumable given to an inpatient.
 *
 * <p>Maps the V49 {@code patient_consumable_charts} table (inc-07 07c-i, CR-07-consumable-stock).
 * The consumable bill is created via
 * {@code recordClinicalCharge(kind=MEDICINE, billItem="Medication", description="Consumable: <name>")}
 * (CR-07-Q13-billing-display APPROVED) and its uid is stored here as {@code patientBillUid}
 * (loose ref — ADR-0008 §1).
 *
 * <p><strong>Status quirk (legacy reproduced):</strong> {@code status} is hard-coded "NOT-GIVEN"
 * and never advanced (PatientServiceImpl.java:2305). This is NOT one of the three Q11 bugs;
 * it is a genuine legacy design choice that we reproduce exactly.
 *
 * <p><strong>CR-07-Q11 FIX #1 confirmation:</strong> The billing engine's
 * {@code PatientInvoiceDetail} constructor already uses {@code bill.getQty()} (not hard-coded 1).
 * The fix is satisfied by the as-built billing engine. The invoice detail qty will equal
 * {@code chart.qty} as set from {@code ChargeRequest.qty()}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Field set: PatientConsumableChart.java</li>
 *   <li>Billing logic: PatientServiceImpl.java:2250-2475</li>
 *   <li>24h delete guard: PatientResource.java:3035-3088</li>
 * </ul>
 *
 * <p>inc-07 07c-i / CR-07-consumable-stock / CR-07-Q11 / CR-07-Q13-billing-display.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_consumable_charts")
public class PatientConsumableChart extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Billing + issue fields
    // -------------------------------------------------------------------------

    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 2)
    private BigDecimal qty;

    /**
     * Always "NOT-GIVEN" — legacy hard-coded value (PatientServiceImpl.java:2305).
     * Do NOT advance this field; the legacy system never did. This is not a bug — it
     * is a quirk of the legacy data model that the consumable chart has no GIVEN status.
     * Reproduced verbatim per exact-process mandate.
     */
    @NotNull
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "payment_type", length = 50)
    private String paymentType;

    @Column(name = "membership_no", length = 100)
    private String membershipNo;

    // -------------------------------------------------------------------------
    // Mandatory loose refs (ADR-0008 §1 — no physical FK)
    // -------------------------------------------------------------------------

    /**
     * Loose ref to the created PatientBill. NOT NULL — every consumable chart links to its bill.
     */
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Loose ref to the consumable Medicine. NOT NULL.
     */
    @Column(name = "medicine_uid", length = 26, nullable = false, updatable = false)
    private String medicineUid;

    /**
     * Loose ref to the patient. NOT NULL.
     */
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Nullable loose refs (ADR-0008 §1)
    // -------------------------------------------------------------------------

    @Column(name = "admission_uid", length = 26)
    private String admissionUid;

    @Column(name = "nurse_uid", length = 26)
    private String nurseUid;

    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    /** Stock-source pharmacy (mandatory per CR-07-consumable-stock; nullable in schema for safety). */
    @Column(name = "pharmacy_uid", length = 26)
    private String pharmacyUid;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Create a new consumable chart entry.
     *
     * <p>{@code status} is hard-coded "NOT-GIVEN" per legacy PatientServiceImpl.java:2305.
     *
     * @param qty              quantity issued (NUMERIC 19,2; must be &gt; 0)
     * @param paymentType      payment type string ("CASH" or "INSURANCE")
     * @param membershipNo     insurance membership number (nullable)
     * @param patientBillUid   loose uid of the created PatientBill
     * @param medicineUid      loose uid of the consumable Medicine
     * @param patientUid       loose uid of the patient
     * @param admissionUid     loose uid of the owning admission
     * @param nurseUid         loose uid of the nurse
     * @param insurancePlanUid loose uid of the insurance plan (nullable)
     * @param pharmacyUid      loose uid of the stock-source pharmacy
     * @return new PatientConsumableChart (uid assigned on first persist)
     */
    public static PatientConsumableChart create(
            BigDecimal qty,
            String paymentType,
            String membershipNo,
            String patientBillUid,
            String medicineUid,
            String patientUid,
            String admissionUid,
            String nurseUid,
            String insurancePlanUid,
            String pharmacyUid) {
        PatientConsumableChart c = new PatientConsumableChart();
        c.qty              = qty;
        c.status           = "NOT-GIVEN";  // verbatim legacy, PatientServiceImpl.java:2305
        c.paymentType      = paymentType;
        c.membershipNo     = membershipNo;
        c.patientBillUid   = patientBillUid;
        c.medicineUid      = medicineUid;
        c.patientUid       = patientUid;
        c.admissionUid     = admissionUid;
        c.nurseUid         = nurseUid;
        c.insurancePlanUid = insurancePlanUid;
        c.pharmacyUid      = pharmacyUid;
        return c;
    }
}
