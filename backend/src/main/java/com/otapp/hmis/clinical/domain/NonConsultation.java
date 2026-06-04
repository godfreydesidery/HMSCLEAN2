package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * NonConsultation aggregate — the walk-in / no-doctor encounter track (inc-05 C4, ADR-0022).
 *
 * <p>A {@code NonConsultation} is the encounter record for OUTSIDER / walk-in patients who place
 * direct lab, radiology, or procedure orders WITHOUT a doctor consultation. Unlike
 * {@link Consultation} there is NO {@code patientBill}, NO {@code clinicUid}, NO
 * {@code clinicianUserUid}, NO {@code followUp}, and NO {@code settled} flag.
 *
 * <p><strong>Cross-module FK discipline (ADR-0022 D2 Correction):</strong>
 * V21 created {@code non_consultations} with real {@code patient_id} / {@code visit_id} FKs.
 * V31 drops those columns after backfilling the loose uid columns {@code patient_uid} /
 * {@code visit_uid} (identical to the V29 treatment of {@code consultations}).
 * The entity never mapped {@code patient_id}/{@code visit_id} as JPA associations — they were
 * schema-level only.
 *
 * <p><strong>paymentType mapping (inc-05 C4 build-spec §1 — design choice documented):</strong>
 * The V21 CHECK allows {@code ('CASH', 'INSURANCE', '')}. The legacy entity declares
 * {@code paymentType String @NotBlank default ''}. The empty-string value {@code ''} is
 * intentionally permitted (a walk-in may arrive before a payment mode is determined).
 * {@code PaymentMode} enum ({@code CASH}, {@code INSURANCE}) has no representation for
 * {@code ''}, so mapping to {@code PaymentMode} would require a nullable enum or a sentinel —
 * neither is faithful. The simplest faithful mapping is a {@code String} field (default {@code ''}),
 * which honours the CHECK, the legacy default, and the {@code @NotBlank} annotation on the
 * legacy entity that allows the empty-string default at the object level. This is a deliberate
 * deviation from the {@link Consultation} pattern ({@code Consultation} uses {@code PaymentMode}
 * because it never stores {@code ''}). See build-spec §1 commentary.
 *
 * <p><strong>Lifecycle (inc-05 C4 build-spec §2):</strong>
 * Created IN_PROCESS inside the order-save paths for OUTSIDER patients
 * (PatientServiceImpl.java:790-806 lab, :1033-1048 radiology, :1280-1296 procedure).
 * The get-or-create pattern: if the patient has an existing IN_PROCESS NonConsultation, reuse it;
 * otherwise create a new one IN_PROCESS. SIGNED_OUT on free/sign-out (PatientResource.java:350).
 * The PENDING branch in the legacy is a dead/defensive path — the OBSERVED written statuses are
 * only IN_PROCESS and SIGNED_OUT.
 *
 * <p><strong>DEFERRED — order-save wiring (inc-05 C4 build-spec §3):</strong>
 * The actual wiring of this entity into the C7-C9 lab/radiology/procedure order-save paths is
 * deferred to those chunks. For C4 the entity, repository, service, controller, and DTO are
 * complete; the service's {@code getOrCreateInProcess} method is the published API that C7-C9
 * will call.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: NonConsultation.java:44-80</li>
 *   <li>Creation (lab): PatientServiceImpl.java:790-806</li>
 *   <li>Creation (radiology): PatientServiceImpl.java:1033-1048</li>
 *   <li>Creation (procedure): PatientServiceImpl.java:1280-1296</li>
 *   <li>Sign-out: PatientResource.java:350</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "non_consultations")
public class NonConsultation extends AuditableEntity {

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 Correction — replaces patient_id FK).
     * Backfilled from patients.uid via V31 migration (additive, loss-free).
     * NOT NULL: every walk-in has a patient.
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose cross-module ref to the visit (ADR-0022 D2 Correction — replaces visit_id FK).
     * NOT NULL: V21 declared visit_id NOT NULL; V31 maintains the same nullability for visit_uid.
     * Backfilled from visits.uid via V31 migration.
     */
    @NotBlank
    @Column(name = "visit_uid", length = 26, nullable = false, updatable = false)
    private String visitUid;

    /**
     * Payment type string — CASH, INSURANCE, or '' (empty, default).
     *
     * <p><strong>Design choice (paymentType as String, not PaymentMode enum):</strong>
     * The V21 CHECK allows {@code ('CASH', 'INSURANCE', '')}. The legacy {@code NonConsultation}
     * entity declares {@code @NotBlank paymentType String} with a default of {@code ''}.
     * The empty string {@code ''} is a legal V21 value (walk-in before payment mode is known).
     * Using {@code PaymentMode} enum (CASH/INSURANCE only) would have no representation for
     * {@code ''} without a nullable field or a sentinel constant — neither is faithful.
     * {@code String} maps the CHECK vocabulary 1-to-1 and avoids an artificial enum entry.
     *
     * <p><strong>Note on @NotBlank:</strong> The legacy entity declares {@code @NotBlank} on
     * {@code paymentType} but also sets {@code default = ''}, which is a contradiction — Bean
     * Validation would reject {@code ''} at the Java layer if {@code @Valid} were applied.
     * In the legacy system the create path bypassed JSR-303 for this field (the service sets
     * paymentType directly before persist; the controller did not pass this field through
     * {@code @Valid @RequestBody}). We do NOT apply {@code @NotBlank} here to avoid breaking
     * the default-empty-string path that the V21 CHECK explicitly permits. The DB-level constraint
     * {@code ck_non_consultations_payment_type} already enforces the vocabulary.
     * (Legacy citation: NonConsultation.java:48-49, V21 CHECK.)
     */
    @Column(name = "payment_type", length = 20, nullable = false)
    private String paymentType = "";

    /**
     * Insurance membership number, denormalised from patient at walk-in creation time.
     * Empty string for CASH / unknown patients; set from patient.membershipNo for INSURANCE.
     * (NonConsultation.java:51; default '' mirrors legacy.)
     */
    @Column(name = "membership_no", length = 100)
    private String membershipNo = "";

    /**
     * Lifecycle status. Exactly two observed values: IN_PROCESS (open) and SIGNED_OUT (closed).
     * Mapped via {@link NonConsultationStatusConverter} (NOT @Enumerated) because both values
     * are hyphenated (IN-PROCESS, SIGNED-OUT) and therefore not valid Java enum constant names.
     * (PatientServiceImpl.java:791; PatientResource.java:350.)
     */
    @NotNull
    @Convert(converter = NonConsultationStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private NonConsultationStatus status = NonConsultationStatus.IN_PROCESS;

    /**
     * Loose cross-module ref to the insurance plan in the masterdata module (no FK, ADR-0008).
     * NULL for CASH / unknown patients; set from patient.insurancePlanUid for INSURANCE.
     * (NonConsultation.java:70-73.)
     */
    @Column(name = "insurance_plan_uid", length = 26)
    private String insurancePlanUid;

    /**
     * Loose cross-module ref to the open business day at time of creation (no FK, ADR-0009).
     * Sourced from {@code BusinessDayService.currentUid()}.
     */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false, updatable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new IN_PROCESS walk-in encounter (PatientServiceImpl.java:790-806).
     *
     * <p>Created IN_PROCESS directly (not PENDING — the PENDING branch in the legacy is a
     * dead/defensive path; the observed written status at creation is always IN-PROCESS).
     *
     * @param patientUid       loose uid of the patient (registration module, ADR-0022 D2)
     * @param visitUid         loose uid of the associated visit (registration module)
     * @param paymentType      payment type string: CASH, INSURANCE, or '' (default)
     * @param membershipNo     insurance membership number (empty for CASH)
     * @param insurancePlanUid loose uid of the insurance plan (null for CASH)
     * @param businessDayUid   loose uid of the current open business day
     */
    public NonConsultation(String patientUid, String visitUid,
                           String paymentType, String membershipNo,
                           String insurancePlanUid, String businessDayUid) {
        this.patientUid = patientUid;
        this.visitUid = visitUid;
        this.paymentType = paymentType != null ? paymentType : "";
        this.membershipNo = membershipNo != null ? membershipNo : "";
        this.insurancePlanUid = insurancePlanUid;
        this.businessDayUid = businessDayUid;
        this.status = NonConsultationStatus.IN_PROCESS;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle
    // -------------------------------------------------------------------------

    /**
     * Sign out the walk-in encounter: IN_PROCESS → SIGNED_OUT.
     *
     * <p>Guard (status == IN_PROCESS) must be verified by the service layer before calling this.
     * (PatientResource.java:350.)
     */
    public void signOut() {
        this.status = NonConsultationStatus.SIGNED_OUT;
    }
}
