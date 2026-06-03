package com.otapp.hmis.registration.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Thin join record linking a {@link Patient} to the registration-fee bill
 * (Registration.java:34-62).
 *
 * <p>This is a OneToOne companion to {@link Patient}: exactly one registration per patient,
 * enforced by both the {@code uq_registrations_patient} DB constraint and the
 * {@link OneToOne} mapping here.
 *
 * <p>The registration-fee {@code PatientBill} lives in the billing module — this entity
 * carries only a loose uid ref ({@code patientBillUid}) with NO FK (ADR-0008).
 * The bill is created by {@code billing.api.BillingCommands.recordClinicalCharge()}
 * (build-spec §2.3 step 5), never by the registration module directly (build-spec §0 item 2).
 *
 * <p>Status is {@code ACTIVE} only in inc-03 (CR-18); later increments widen via
 * additive migration.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Registration.java:34-62</li>
 *   <li>Creation: PatientServiceImpl.java:293-302</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "registrations")
public class Registration extends AuditableEntity {

    /**
     * Intra-module OneToOne FK to the patient (Registration.java:46-49).
     * UNIQUE constraint ({@code uq_registrations_patient}) enforces one-to-one at DB level.
     */
    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    /**
     * Loose cross-module ref to the registration-fee {@code PatientBill} in the billing
     * module (Registration.java:51-54; ADR-0008 — no FK across modules).
     * NOT NULL: a registration always has a corresponding fee bill.
     */
    @NotBlank
    @Column(name = "patient_bill_uid", length = 26, nullable = false, updatable = false)
    private String patientBillUid;

    /**
     * Lifecycle status.  Only {@link RegistrationStatus#ACTIVE} in inc-03 (CR-18).
     * PatientServiceImpl.java:293-302.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RegistrationStatus status = RegistrationStatus.ACTIVE;

    /**
     * Loose cross-module ref to the open business day at time of registration (no FK).
     * Sourced from {@code BusinessDayService.currentUid()} (ADR-0009 §7).
     */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false, updatable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new ACTIVE registration record.
     *
     * @param patient        the registered patient (intra-module FK)
     * @param patientBillUid loose uid of the registration-fee PatientBill (billing module)
     * @param businessDayUid loose uid of the current open business day
     */
    public Registration(Patient patient, String patientBillUid, String businessDayUid) {
        this.patient = patient;
        this.patientBillUid = patientBillUid;
        this.status = RegistrationStatus.ACTIVE;
        this.businessDayUid = businessDayUid;
    }
}
