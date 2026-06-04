package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Nurse vitals-capture staging entity (inc-05 C5, PatientVital.java:23-67).
 *
 * <p>The nurse fills in vital signs for a patient before the doctor sees them. The entity mirrors
 * the field set of {@link GeneralExamination} (11 free-text vital fields + encounter binding) and
 * adds a {@link PatientVitalStatus} lifecycle: {@code EMPTY → SUBMITTED → ARCHIVED}.
 *
 * <p><strong>ALL vital-sign fields are free-text String — NO numeric typing (CR-INC05-13).</strong>
 *
 * <p><strong>Staging flow (PatientResource.java:1298-1340):</strong>
 * <ol>
 *   <li>GET /consultations/uid/{uid}/vitals → auto-creates a row with status=EMPTY if none
 *       exists (side-effecting GET, CR-INC05-06 faithful reproduction). Partial UNIQUE on
 *       {@code consultation_id} (V23) ensures one row per consultation.</li>
 *   <li>POST /consultations/uid/{uid}/vitals → nurse fills and submits: status → SUBMITTED.</li>
 *   <li>POST /consultations/uid/{uid}/vitals/request → doctor requests: all vital fields are
 *       copied into the consultation's {@link GeneralExamination} (persisted via upsert);
 *       this PatientVital row is set to ARCHIVED.</li>
 * </ol>
 *
 * <p><strong>Encounter binding (exactly one non-null — V23 CHECK num_nonnulls = 1):</strong>
 * <ul>
 *   <li>{@code consultation} — real intra-module {@code @OneToOne} to {@link Consultation}.</li>
 *   <li>{@code nonConsultation} — real intra-module {@code @OneToOne} to {@link NonConsultation}
 *       (present in schema for completeness; no non-consultation vitals endpoint in C5).</li>
 *   <li>{@code admissionUid} — loose String, no FK (DEFERRED).</li>
 * </ul>
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: PatientVital.java:23-67</li>
 *   <li>Auto-create EMPTY: PatientResource.java:1298-1307</li>
 *   <li>Submitted worklist: PatientResource.java:1321</li>
 *   <li>Request / ARCHIVED: PatientResource.java:1340</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "patient_vitals")
public class PatientVital extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Vital-sign fields — ALL String, ALL nullable (CR-INC05-13)
    // -------------------------------------------------------------------------

    /** Blood pressure, e.g. "120/80" (VARCHAR(40) per V23). */
    @Column(name = "pressure", length = 40)
    private String pressure;

    /** Body temperature (VARCHAR(40) per V23). */
    @Column(name = "temperature", length = 40)
    private String temperature;

    /** Pulse rate (VARCHAR(40) per V23). */
    @Column(name = "pulse_rate", length = 40)
    private String pulseRate;

    /** Body weight (VARCHAR(40) per V23). */
    @Column(name = "weight", length = 40)
    private String weight;

    /** Body height (VARCHAR(40) per V23). */
    @Column(name = "height", length = 40)
    private String height;

    /** Body mass index — free text, NOT computed (CR-INC05-13; VARCHAR(40) per V23). */
    @Column(name = "body_mass_index", length = 40)
    private String bodyMassIndex;

    /** BMI classification comment (VARCHAR(200) per V23). */
    @Column(name = "body_mass_index_comment", length = 200)
    private String bodyMassIndexComment;

    /** Body surface area — free text, NOT computed (CR-INC05-13; VARCHAR(40) per V23). */
    @Column(name = "body_surface_area", length = 40)
    private String bodySurfaceArea;

    /** Oxygen saturation (VARCHAR(40) per V23). */
    @Column(name = "saturation_oxygen", length = 40)
    private String saturationOxygen;

    /** Respiratory rate (VARCHAR(40) per V23). */
    @Column(name = "respiratory_rate", length = 40)
    private String respiratoryRate;

    /** Additional notes (VARCHAR(1000) per V23). */
    @Column(name = "description", length = 1000)
    private String description;

    // -------------------------------------------------------------------------
    // Staging lifecycle
    // -------------------------------------------------------------------------

    /**
     * Current staging status (PatientVital.java:45).
     * {@code @Enumerated(STRING)}: the three values are plain identifiers (no hyphens) so
     * no custom converter is needed. V23 CHECK confirms the three values exactly.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PatientVitalStatus status = PatientVitalStatus.EMPTY;

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
     * Present in schema for completeness; no non-consultation vitals endpoint exposed in C5.
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
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Create a new EMPTY PatientVital for a consultation
     * (PatientResource.java:1298-1307 — auto-create on GET).
     *
     * @param consultation   the owning consultation
     * @param businessDayUid loose uid of the open business day
     * @return new PatientVital with status=EMPTY and all vital fields null
     */
    public static PatientVital forConsultation(Consultation consultation, String businessDayUid) {
        PatientVital pv = new PatientVital();
        pv.consultation = consultation;
        pv.businessDayUid = businessDayUid;
        pv.status = PatientVitalStatus.EMPTY;
        return pv;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle + field updates
    // -------------------------------------------------------------------------

    /**
     * Overwrite all vital-sign fields and transition status to SUBMITTED.
     *
     * <p>Called when the nurse submits the filled vital readings (POST /vitals endpoint).
     * All vital fields are overwritten (any previously null fields remain null if not provided).
     *
     * @param pressure           blood pressure
     * @param temperature        body temperature
     * @param pulseRate          pulse rate
     * @param weight             body weight
     * @param height             body height
     * @param bodyMassIndex      BMI (free text, NOT computed)
     * @param bodyMassIndexComment BMI classification comment
     * @param bodySurfaceArea    body surface area (free text, NOT computed)
     * @param saturationOxygen   oxygen saturation
     * @param respiratoryRate    respiratory rate
     * @param description        additional notes
     */
    public void submitVitals(String pressure,
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
        this.status = PatientVitalStatus.SUBMITTED;
    }

    /**
     * Transition to ARCHIVED (doctor has requested the vitals and they have been copied
     * into the GeneralExamination — PatientResource.java:1340).
     *
     * <p>Guard: the service layer must verify {@code status == SUBMITTED} before calling this.
     */
    public void archive() {
        this.status = PatientVitalStatus.ARCHIVED;
    }
}
