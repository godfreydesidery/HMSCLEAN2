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
 * Dressing billing record — a chart entry that creates a PatientBill for a dressing procedure.
 *
 * <p>Maps the V48 {@code patient_dressing_charts} table (inc-07 07b, AC-07B-DRS-01).
 * The dressing bill is created via {@code recordClinicalCharge(kind=PROCEDURE)} and its uid
 * is stored here as {@code patientBillUid} (loose ref — ADR-0008 §1).
 *
 * <p>NO wound_status/wound_description columns per AC-07B-DRS-01.
 * The bill-line display literals ('Procedure'/'Dressing: <name>') are BLOCKED on
 * CR-07-Q13-billing-display — only amount/status are frozen per AC-07B-DRS-03.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Field set: PatientDressingChart.java:40-95</li>
 *   <li>Billing logic: PatientServiceImpl.java:2078-2245</li>
 *   <li>24h delete guard: PatientResource.java:2967-3179</li>
 * </ul>
 *
 * <p>inc-07 07b / AC-07B-DRS-01 / AC-07B-FLY-01.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_dressing_charts")
public class PatientDressingChart extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Billing fields (PatientDressingChart.java:40-43)
    // -------------------------------------------------------------------------

    @NotNull
    @Column(name = "qty", nullable = false, precision = 19, scale = 2)
    private BigDecimal qty;

    @Column(name = "payment_type", length = 50)
    private String paymentType;

    @Column(name = "membership_no", length = 100)
    private String membershipNo;

    // -------------------------------------------------------------------------
    // Mandatory loose refs to the created PatientBill and ProcedureType
    // (ADR-0008 §1 — no physical FK to billing or masterdata tables)
    // -------------------------------------------------------------------------

    /**
     * Loose ref to the created PatientBill (mandatory @OneToOne equivalent).
     * NOT NULL — every dressing chart entry links to its bill.
     * (PatientDressingChart.java:66-69)
     */
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Loose ref to the ProcedureType (mandatory @ManyToOne equivalent).
     * NOT NULL — every dressing chart entry links to its procedure type.
     * (PatientDressingChart.java:61-64)
     */
    @Column(name = "procedure_type_uid", length = 26, nullable = false, updatable = false)
    private String procedureTypeUid;

    // -------------------------------------------------------------------------
    // Nullable loose refs (ADR-0008 §1)
    // -------------------------------------------------------------------------

    @Column(name = "admission_uid", length = 26)
    private String admissionUid;

    @Column(name = "clinician_uid", length = 26)
    private String clinicianUid;

    @Column(name = "nurse_uid", length = 26)
    private String nurseUid;

    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Create a new dressing chart entry.
     *
     * @param qty              quantity (legacy literal: bill qty=1, chart qty=request qty)
     * @param paymentType      payment type string (CASH/INSURANCE)
     * @param membershipNo     insurance membership number (nullable)
     * @param patientBillUid   loose uid of the created PatientBill
     * @param procedureTypeUid loose uid of the ProcedureType
     * @param admissionUid     loose uid of the admission (nullable for consultation context)
     * @param clinicianUid     loose uid of the ordering clinician (nullable)
     * @param nurseUid         loose uid of the nurse (nullable)
     * @param insurancePlanUid loose uid of the insurance plan (nullable)
     * @param patientUid       loose uid of the patient
     * @return new PatientDressingChart (uid assigned on first persist)
     */
    public static PatientDressingChart create(
            BigDecimal qty,
            String paymentType,
            String membershipNo,
            String patientBillUid,
            String procedureTypeUid,
            String admissionUid,
            String clinicianUid,
            String nurseUid,
            String insurancePlanUid,
            String patientUid) {
        PatientDressingChart d = new PatientDressingChart();
        d.qty              = qty;
        d.paymentType      = paymentType;
        d.membershipNo     = membershipNo;
        d.patientBillUid   = patientBillUid;
        d.procedureTypeUid = procedureTypeUid;
        d.admissionUid     = admissionUid;
        d.clinicianUid     = clinicianUid;
        d.nurseUid         = nurseUid;
        d.insurancePlanUid = insurancePlanUid;
        d.patientUid       = patientUid;
        return d;
    }
}
