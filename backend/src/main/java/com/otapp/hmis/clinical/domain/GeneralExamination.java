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
 * General examination / vitals record for a patient encounter
 * (inc-05 C5, GeneralExamination.java:32-75).
 *
 * <p>Holds 11 free-text vital-sign and examination fields. ALL fields are {@code String}
 * with NO numeric typing, NO range validation, and NO server-side BMI/BSA computation.
 * BMI and BSA arrive as free-text strings from the client — the backend stores them verbatim
 * (CR-INC05-13 REJECT, 11-DECISIONS-RATIFIED.md §2).
 *
 * <p><strong>Encounter binding (exactly one non-null — V23 CHECK num_nonnulls = 1):</strong>
 * <ul>
 *   <li>{@code consultation} — real intra-module {@code @OneToOne} to {@link Consultation}.</li>
 *   <li>{@code nonConsultation} — real intra-module {@code @OneToOne} to {@link NonConsultation}.</li>
 *   <li>{@code admissionUid} — loose {@code VARCHAR(26)}, no FK (DEFERRED — admission module).</li>
 * </ul>
 *
 * <p><strong>Side-effecting GET loaders (CR-INC05-06 — REPRODUCE faithfully):</strong>
 * Both the consultation loader and the non-consultation loader auto-create an empty persisted
 * row on GET when none exists. This differs from {@link ClinicalNote} which has a consultation
 * loader ONLY (the non-consultation clinical-note loader is absent from the legacy).
 *
 * <p><strong>Vitals-request flow:</strong>
 * When the doctor requests {@link PatientVital} readings, the service copies all vital fields
 * from the submitted PatientVital into this entity (upsert via the consultation), persisting
 * it as the consultation's GeneralExamination. The PatientVital is then ARCHIVED.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: GeneralExamination.java:32-75</li>
 *   <li>Free-text vitals: GeneralExamination.java:42-51</li>
 *   <li>saveCG UPSERT: PatientResource.java:1469-1598</li>
 *   <li>load_general_examination_by_consultation_id: PatientResource.java (auto-create)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "general_examinations")
public class GeneralExamination extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Vital-sign fields — ALL String, ALL nullable (CR-INC05-13)
    // -------------------------------------------------------------------------

    /**
     * Blood pressure reading, e.g. "120/80" (GeneralExamination.java — pressure).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "pressure", length = 40)
    private String pressure;

    /**
     * Body temperature reading (GeneralExamination.java — temperature).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "temperature", length = 40)
    private String temperature;

    /**
     * Pulse rate (GeneralExamination.java — pulseRate).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "pulse_rate", length = 40)
    private String pulseRate;

    /**
     * Body weight (GeneralExamination.java — weight).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "weight", length = 40)
    private String weight;

    /**
     * Body height (GeneralExamination.java — height).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "height", length = 40)
    private String height;

    /**
     * Body mass index (GeneralExamination.java — bodyMassIndex).
     * Free text; VARCHAR(40) per V23. NOT computed server-side (CR-INC05-13).
     */
    @Column(name = "body_mass_index", length = 40)
    private String bodyMassIndex;

    /**
     * BMI classification comment (GeneralExamination.java — bodyMassIndexComment).
     * Free text; VARCHAR(200) per V23.
     */
    @Column(name = "body_mass_index_comment", length = 200)
    private String bodyMassIndexComment;

    /**
     * Body surface area (GeneralExamination.java — bodySurfaceArea).
     * Free text; VARCHAR(40) per V23. NOT computed server-side (CR-INC05-13).
     */
    @Column(name = "body_surface_area", length = 40)
    private String bodySurfaceArea;

    /**
     * Oxygen saturation (GeneralExamination.java — saturationOxygen).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "saturation_oxygen", length = 40)
    private String saturationOxygen;

    /**
     * Respiratory rate (GeneralExamination.java — respiratoryRate).
     * Free text; VARCHAR(40) per V23.
     */
    @Column(name = "respiratory_rate", length = 40)
    private String respiratoryRate;

    /**
     * Free-text description / additional examination notes
     * (GeneralExamination.java — description).
     * VARCHAR(1000) per V23.
     */
    @Column(name = "description", length = 1000)
    private String description;

    // -------------------------------------------------------------------------
    // Encounter binding
    // -------------------------------------------------------------------------

    /**
     * Intra-module real FK to the consultation (@OneToOne, consultation_id).
     * NULL when bound to a non-consultation or admission.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Intra-module real FK to the non-consultation (@OneToOne, non_consultation_id).
     * NULL when bound to a consultation or admission.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "non_consultation_id", updatable = false)
    private NonConsultation nonConsultation;

    /**
     * Loose cross-module reference to an admission (VARCHAR(26), no FK).
     * DEFERRED — admission module not yet built (inc-05 C5 scope note).
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    /**
     * Loose reference to the open business day at time of creation (no FK, ADR-0009).
     */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Create an empty GeneralExamination bound to a {@link Consultation}.
     *
     * @param consultation   the owning consultation
     * @param businessDayUid loose uid of the open business day
     * @return new GeneralExamination with all vital fields null
     */
    public static GeneralExamination forConsultation(Consultation consultation,
                                                      String businessDayUid) {
        GeneralExamination ge = new GeneralExamination();
        ge.consultation = consultation;
        ge.businessDayUid = businessDayUid;
        return ge;
    }

    /**
     * Create an empty GeneralExamination bound to a {@link NonConsultation}.
     *
     * @param nonConsultation   the owning non-consultation
     * @param businessDayUid    loose uid of the open business day
     * @return new GeneralExamination with all vital fields null
     */
    public static GeneralExamination forNonConsultation(NonConsultation nonConsultation,
                                                         String businessDayUid) {
        GeneralExamination ge = new GeneralExamination();
        ge.nonConsultation = nonConsultation;
        ge.businessDayUid = businessDayUid;
        return ge;
    }

    // -------------------------------------------------------------------------
    // Domain methods — field updates (UPSERT-in-place)
    // -------------------------------------------------------------------------

    /**
     * Overwrite all 11 vital-sign / examination fields in place
     * (saveCG UPSERT — PatientResource.java:1469-1598; vitals-request copy —
     * PatientResource.java:1340).
     *
     * <p>All parameters nullable — the legacy has no non-null constraint on any vital field.
     * BMI and BSA are accepted as-is; no computation is performed (CR-INC05-13).
     */
    public void updateVitals(String pressure,
                             String temperature,
                             String pulseRate,
                             String weight,
                             String height,
                             String bodyMassIndex,
                             String bodyMassIndexComment,
                             String bodySurfaceArea,
                             String saturationOxygen,
                             String respiratoryRate,
                             String description) {
        this.pressure = pressure;
        this.temperature = temperature;
        this.pulseRate = pulseRate;
        this.weight = weight;
        this.height = height;
        this.bodyMassIndex = bodyMassIndex;
        this.bodyMassIndexComment = bodyMassIndexComment;
        this.bodySurfaceArea = bodySurfaceArea;
        this.saturationOxygen = saturationOxygen;
        this.respiratoryRate = respiratoryRate;
        this.description = description;
    }
}
