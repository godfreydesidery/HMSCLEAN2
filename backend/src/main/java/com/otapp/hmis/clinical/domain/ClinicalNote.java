package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SOAP clinical note for a patient encounter (inc-05 C5, ClinicalNote.java:34-75).
 *
 * <p>Holds 8 nullable free-text SOAP fields. NO Bean Validation annotations — all fields are
 * intentionally optional (legacy parity: ClinicalNote.java has no {@code @NotBlank} or
 * {@code @NotNull} on any SOAP field).
 *
 * <p><strong>Encounter binding (exactly one non-null — V23 CHECK num_nonnulls = 1):</strong>
 * <ul>
 *   <li>{@code consultation} — real intra-module {@code @OneToOne} to {@link Consultation}
 *       (same schema, same module; FK is {@code consultation_id}).</li>
 *   <li>{@code nonConsultation} — real intra-module {@code @OneToOne} to {@link NonConsultation}
 *       (same schema, same module; FK is {@code non_consultation_id}).</li>
 *   <li>{@code admissionUid} — loose {@code VARCHAR(26)} reference only; no FK
 *       (admission module not yet built — DEFERRED, inc-05 C5 scope note).</li>
 * </ul>
 *
 * <p><strong>UPSERT behaviour (CR-INC05-07, V23):</strong>
 * One row per consultation (partial UNIQUE on {@code consultation_id}) and one row per
 * non_consultation (partial UNIQUE on {@code non_consultation_id}). The service layer UPSERTS:
 * if a note already exists for the encounter, its fields are OVERWRITTEN in place; if none
 * exists, a new row is created. The admission path appends (no UNIQUE) — DEFERRED.
 *
 * <p><strong>Side-effecting GET loader (CR-INC05-06 — REPRODUCE faithfully):</strong>
 * {@code loadOrCreate} for a consultation auto-creates an empty persisted note (HTTP 201) when
 * none exists. There is NO non-consultation note loader in the legacy
 * (PatientResource.java survey confirms absence — faithful omission, documented).
 *
 * <p><strong>DEFERRED — admission paths:</strong>
 * The {@code admissionUid} column exists and is mapped as a loose nullable String. No
 * admission-scoped endpoints or append logic are implemented in C5. Full admission support
 * is deferred to the Inpatient/Nursing increment.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: ClinicalNote.java:34-75</li>
 *   <li>SOAP fields: ClinicalNote.java:36-58 (8 nullable Strings)</li>
 *   <li>saveCG UPSERT: PatientResource.java:1469-1598</li>
 *   <li>load_clinical_note_by_consultation_id (side-effecting GET):
 *       PatientResource.java (auto-create empty note)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "clinical_notes")
public class ClinicalNote extends AuditableEntity {

    // -------------------------------------------------------------------------
    // SOAP fields — all nullable, all free-text (no Bean Validation)
    // -------------------------------------------------------------------------

    /**
     * Chief complaint / main reason for the visit (ClinicalNote.java — mainComplain field,
     * VARCHAR(500) as per V23). Free text, no validation.
     */
    @Column(name = "main_complain", length = 500)
    private String mainComplain;

    /**
     * History of the present illness (ClinicalNote.java — presentIllnessHistory).
     * TEXT in V23 — mapped to String.
     */
    @Column(name = "present_illness_history", columnDefinition = "TEXT")
    private String presentIllnessHistory;

    /**
     * Past medical history (ClinicalNote.java — pastMedicalHistory). TEXT in V23.
     */
    @Column(name = "past_medical_history", columnDefinition = "TEXT")
    private String pastMedicalHistory;

    /**
     * Family and social history (ClinicalNote.java — familyAndSocialHistory). TEXT in V23.
     */
    @Column(name = "family_and_social_history", columnDefinition = "TEXT")
    private String familyAndSocialHistory;

    /**
     * Drugs and allergy history (ClinicalNote.java — drugsAndAllergyHistory). TEXT in V23.
     */
    @Column(name = "drugs_and_allergy_history", columnDefinition = "TEXT")
    private String drugsAndAllergyHistory;

    /**
     * Review of other systems (ClinicalNote.java — reviewOfOtherSystems). TEXT in V23.
     */
    @Column(name = "review_of_other_systems", columnDefinition = "TEXT")
    private String reviewOfOtherSystems;

    /**
     * Physical examination findings (ClinicalNote.java — physicalExamination). TEXT in V23.
     */
    @Column(name = "physical_examination", columnDefinition = "TEXT")
    private String physicalExamination;

    /**
     * Management / treatment plan (ClinicalNote.java — managementPlan). TEXT in V23.
     */
    @Column(name = "management_plan", columnDefinition = "TEXT")
    private String managementPlan;

    // -------------------------------------------------------------------------
    // Encounter binding — exactly one of the three must be non-null (V23 CHECK)
    // -------------------------------------------------------------------------

    /**
     * Intra-module real FK to the consultation (@OneToOne, consultation_id).
     * Both entities live in the clinical module / same schema — the FK is correct and safe.
     * NULL when this note belongs to a non-consultation or admission encounter.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Intra-module real FK to the non-consultation (@OneToOne, non_consultation_id).
     * NULL when this note belongs to a consultation or admission encounter.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "non_consultation_id", updatable = false)
    private NonConsultation nonConsultation;

    /**
     * Loose cross-module reference to an admission (VARCHAR(26), no FK).
     * The admissions module is not yet built (DEFERRED, inc-05 C5). This column stores the
     * admission ULID when the admission path is eventually implemented.
     * NULL when this note belongs to a consultation or non-consultation.
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    /**
     * Loose reference to the open business day at time of creation (no FK, ADR-0009).
     */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Factory methods (one per encounter type — enforces exactly-one constraint)
    // -------------------------------------------------------------------------

    /**
     * Create an empty ClinicalNote bound to a {@link Consultation}.
     *
     * @param consultation   the owning consultation (intra-module real @OneToOne)
     * @param businessDayUid loose uid of the open business day
     * @return a new ClinicalNote with all SOAP fields null
     */
    public static ClinicalNote forConsultation(Consultation consultation, String businessDayUid) {
        ClinicalNote note = new ClinicalNote();
        note.consultation = consultation;
        note.businessDayUid = businessDayUid;
        return note;
    }

    /**
     * Create an empty ClinicalNote bound to a {@link NonConsultation}.
     * Note: the legacy has NO non-consultation note loader endpoint — this factory exists
     * for completeness and potential future use, but no side-effecting GET is exposed for
     * non-consultation notes (faithful omission per legacy survey).
     *
     * @param nonConsultation   the owning non-consultation (intra-module real @OneToOne)
     * @param businessDayUid    loose uid of the open business day
     * @return a new ClinicalNote with all SOAP fields null
     */
    public static ClinicalNote forNonConsultation(NonConsultation nonConsultation,
                                                   String businessDayUid) {
        ClinicalNote note = new ClinicalNote();
        note.nonConsultation = nonConsultation;
        note.businessDayUid = businessDayUid;
        return note;
    }

    // -------------------------------------------------------------------------
    // Domain methods — SOAP field updates (UPSERT-in-place)
    // -------------------------------------------------------------------------

    /**
     * Overwrite all 8 SOAP fields in place (saveCG UPSERT — PatientResource.java:1469-1598).
     * All parameters are nullable (all SOAP fields are optional in the legacy).
     *
     * @param mainComplain               chief complaint (max 500 chars per V23)
     * @param presentIllnessHistory      history of present illness
     * @param pastMedicalHistory         past medical history
     * @param familyAndSocialHistory     family and social history
     * @param drugsAndAllergyHistory     drugs and allergy history
     * @param reviewOfOtherSystems       review of other systems
     * @param physicalExamination        physical examination findings
     * @param managementPlan             management / treatment plan
     */
    public void updateSoap(String mainComplain,
                           String presentIllnessHistory,
                           String pastMedicalHistory,
                           String familyAndSocialHistory,
                           String drugsAndAllergyHistory,
                           String reviewOfOtherSystems,
                           String physicalExamination,
                           String managementPlan) {
        this.mainComplain = mainComplain;
        this.presentIllnessHistory = presentIllnessHistory;
        this.pastMedicalHistory = pastMedicalHistory;
        this.familyAndSocialHistory = familyAndSocialHistory;
        this.drugsAndAllergyHistory = drugsAndAllergyHistory;
        this.reviewOfOtherSystems = reviewOfOtherSystems;
        this.physicalExamination = physicalExamination;
        this.managementPlan = managementPlan;
    }
}
