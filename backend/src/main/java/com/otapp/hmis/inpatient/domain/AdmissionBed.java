package com.otapp.hmis.inpatient.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Inpatient-owned occupancy/billing ledger entry for a bed stay within an admission (inc-07 07a).
 *
 * <p>DISTINCT from {@code masterdata.WardBed} (which tracks the physical bed's current status).
 * {@code AdmissionBed} is the inpatient module's own record of which bed was used, when it was
 * opened/closed, and which ward-bed bill was created for this occupancy period.
 *
 * <p><strong>Why this entity exists (inc-07 07a ADR):</strong>
 * The legacy {@code AdmissionBed} (AdmissionBed.java) is the join point between an admission,
 * a physical bed, and the billing bill. It also carries the OPENED/CLOSED lifecycle flag needed
 * for the discharge gap-check in 07a-3. One AdmissionBed row is created per occupancy period
 * (future: a patient transferred between beds creates a second AdmissionBed row on the new bed).
 *
 * <p><strong>Cross-module FK discipline (ADR-0008 §1):</strong>
 * <ul>
 *   <li>{@code admissionUid} → inpatient Admission: intra-module reference; implemented as a
 *       loose uid (no JPA association) to keep the entity graph flat and avoid N+1 on the
 *       settlement listener's lookup.</li>
 *   <li>{@code wardBedUid}  → masterdata WardBed: loose uid, NO physical FK.</li>
 *   <li>{@code patientUid}  → registration Patient: loose uid, NO physical FK.</li>
 *   <li>{@code patientBillUid} → billing PatientBill: loose uid, NO physical FK.
 *       This is the key used by {@link com.otapp.hmis.inpatient.application.AdmissionSettlementListener}
 *       to match the {@link com.otapp.hmis.shared.event.BillSettledEvent} to the correct admission.</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/AdmissionBed.java</li>
 *   <li>Creation: PatientServiceImpl.java:1776-1783</li>
 *   <li>Settlement match: PatientBillResource.java:352-365 (ward-bed bill PAID → admission IN-PROCESS)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "admission_beds")
public class AdmissionBed extends AuditableEntity {

    /**
     * Loose intra-module ref to the owning {@link Admission}.
     * NOT a JPA {@code @ManyToOne} — kept as a uid for flat lookup in the settlement listener.
     * PatientServiceImpl.java:1777.
     */
    @NotBlank
    @Column(name = "admission_uid", length = 26, nullable = false, updatable = false)
    private String admissionUid;

    /**
     * Loose cross-module ref to the physical bed (masterdata module, ADR-0008 §1).
     * PatientServiceImpl.java:1779.
     */
    @NotBlank
    @Column(name = "ward_bed_uid", length = 26, nullable = false, updatable = false)
    private String wardBedUid;

    /**
     * Loose cross-module ref to the patient (registration module, ADR-0008 §1).
     * Denormalised for fast lookups without a join to the Admission row.
     * PatientServiceImpl.java:1778.
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose cross-module ref to the ward-bed {@code PatientBill} created at doAdmission
     * (billing module, ADR-0008 §1).
     *
     * <p>This uid is the KEY used by
     * {@link com.otapp.hmis.inpatient.application.AdmissionSettlementListener} to match a
     * {@link com.otapp.hmis.shared.event.BillSettledEvent} to its admission — the event carries
     * ONLY the bill uid (no patientUid). The lookup is
     * {@code admissionBedRepository.findByPatientBillUid(billUid)}.
     *
     * <p>PatientServiceImpl.java:1780.
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Free-text occupancy status: {@code "OPENED"} when the bed is assigned at admission;
     * {@code "CLOSED"} when the bed is vacated at discharge (07a-3).
     * Legacy: AdmissionBed.java:status — free-text String (no enum per legacy pattern).
     * PatientServiceImpl.java:1781.
     */
    @NotBlank
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /**
     * Instant the bed was assigned (doAdmission timestamp).
     * NOT NULL. PatientServiceImpl.java:1782.
     */
    @NotNull
    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    /**
     * Instant the bed was vacated (discharge / referral / deceased sign-out — 07a-3).
     * Nullable until discharged. PatientServiceImpl.java — discharge completion path.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create an OPENED admission-bed record (PatientServiceImpl.java:1776-1783).
     *
     * @param admissionUid   loose uid of the owning admission
     * @param wardBedUid     loose uid of the physical bed
     * @param patientUid     loose uid of the patient
     * @param patientBillUid loose uid of the ward-bed PatientBill (settlement-listener key)
     * @param openedAt       the admission timestamp
     */
    public AdmissionBed(String admissionUid, String wardBedUid,
                        String patientUid, String patientBillUid,
                        Instant openedAt) {
        this.admissionUid = admissionUid;
        this.wardBedUid = wardBedUid;
        this.patientUid = patientUid;
        this.patientBillUid = patientBillUid;
        this.status = "OPENED";
        this.openedAt = openedAt;
    }

    // -------------------------------------------------------------------------
    // Domain methods
    // -------------------------------------------------------------------------

    /**
     * Close this bed record on discharge (07a-3).
     *
     * <p>Sets {@code status = "CLOSED"} and stamps {@code closedAt}. The physical bed is freed
     * by the inpatient service via {@link com.otapp.hmis.masterdata.lookup.WardBedClaim#freeBed}.
     * This method only updates the inpatient-owned ledger row (CR-07-Q10, owner-APPROVED).
     *
     * @param closedAt the discharge/sign-out instant
     */
    public void close(Instant closedAt) {
        this.status = "CLOSED";
        this.closedAt = closedAt;
    }
}
