package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Death-recording note for an OPD consultation (DeceasedNote.java:36-76, inc-05 C12).
 *
 * <p><strong>Encounter binding (V28 CHECK num_nonnulls=1):</strong>
 * Exactly one of {@code consultation} (intra-module real FK) or {@code admissionUid} (loose)
 * must be non-null. OPD path uses {@code consultation}; admission path is DEFERRED.
 *
 * <p><strong>Patient ref (V28 then V37):</strong>
 * Originally a real FK {@code patient_id → patients(id)} (V28). Converted to a loose
 * {@code patient_uid VARCHAR(26)} by V37 (mirrors V30-V36 pattern). The entity maps only
 * {@code patientUid} (the loose ref, post-V37 form). No {@code @ManyToOne Patient} — the
 * clinical module must not import the registration domain.
 *
 * <p><strong>Status (DeceasedNoteStatus):</strong>
 * PENDING / APPROVED / ARCHIVED — all valid Java identifiers, plain {@code @Enumerated(STRING)}.
 * No converter needed.
 *
 * <p><strong>Date/time columns (legacy naming):</strong>
 * {@code @Column(name = "death_date")} mapped to {@code LocalDate} and
 * {@code @Column(name = "death_time")} mapped to {@code LocalTime}. These are supplied
 * verbatim from the client request (DeceasedNote.java:61-64, legacy {@code date_}/{@code time_}).
 *
 * <p><strong>Approver (CR-INC05-03 — captures REAL approver, not creator):</strong>
 * The legacy code copies {@code approved_by} from the creator (DeceasedNote.java:71-72 — a bug).
 * CR-INC05-03 requires the REAL approver's username to be recorded. In this implementation,
 * {@code approvedByUserUid} is set from the approving {@code ctx.actorUsername()} at
 * {@code get_deceased_summary} time, NOT from the creator. The column shape is unchanged.
 *
 * <p><strong>Admission path DEFERRED:</strong>
 * {@code admissionUid} is mapped as a loose nullable String. No admission-scoped endpoints
 * are implemented in C12. Deferred to the Inpatient/Nursing increment.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: DeceasedNote.java:36-76</li>
 *   <li>Status values: DeceasedNote.java:48</li>
 *   <li>Encounter FKs: DeceasedNote.java:50-58</li>
 *   <li>Patient FK: DeceasedNote.java:60-63 (replaced with loose uid per ADR-0022 D2, V37)</li>
 *   <li>Approver-copies-creator bug: DeceasedNote.java:71-72 (corrected in CR-INC05-03)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "deceased_notes")
public class DeceasedNote extends AuditableEntity {

    // -------------------------------------------------------------------------
    // Clinical narrative
    // -------------------------------------------------------------------------

    /**
     * Patient summary at time of death.
     * REST layer enforces non-blank; no JPA constraint (mirrors legacy — the DB column is TEXT).
     * Legacy citation: DeceasedNote.java (patientSummary field).
     */
    @Column(name = "patient_summary", columnDefinition = "TEXT")
    private String patientSummary;

    /**
     * Cause of death narrative.
     * REST layer enforces non-blank; no JPA constraint.
     * Legacy citation: DeceasedNote.java (causeOfDeath field).
     */
    @Column(name = "cause_of_death", columnDefinition = "TEXT")
    private String causeOfDeath;

    /**
     * Date of death — client-supplied (verbatim).
     * Column name: {@code death_date} (V28). Legacy used {@code @Column(name="date_")};
     * V28 schema uses {@code death_date} — the legacy column-name quirk applies WITHIN
     * the legacy schema but V28 normalised the column names; we map to V28's name.
     */
    @Column(name = "death_date")
    private LocalDate deathDate;

    /**
     * Time of death — client-supplied (verbatim).
     * Column name: {@code death_time} (V28).
     */
    @Column(name = "death_time")
    private LocalTime deathTime;

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status (PENDING / APPROVED / ARCHIVED).
     * Plain {@code @Enumerated(STRING)} — all values are valid Java identifiers.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DeceasedNoteStatus status = DeceasedNoteStatus.PENDING;

    // -------------------------------------------------------------------------
    // Encounter binding (exactly one non-null — V28 CHECK)
    // -------------------------------------------------------------------------

    /**
     * Intra-module real FK to the owning consultation (OPD path).
     * NULL when bound to an admission (DEFERRED).
     * DeceasedNote.java:55-58.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", updatable = false)
    private Consultation consultation;

    /**
     * Loose ref to an admission (VARCHAR(26), nullable, no FK).
     * The admissions module is DEFERRED. NULL for OPD consultations.
     * DeceasedNote.java:50-53.
     */
    @Column(name = "admission_uid", length = 26, updatable = false)
    private String admissionUid;

    // -------------------------------------------------------------------------
    // Cross-module patient ref (loose, post-V37)
    // -------------------------------------------------------------------------

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2, V37).
     * Replaces the original V28 patient_id BIGINT FK dropped by V37.
     * No {@code @ManyToOne Patient} — clinical must not import registration domain.
     */
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    // -------------------------------------------------------------------------
    // Approval audit (CR-INC05-03 — captures REAL approver, not creator)
    // -------------------------------------------------------------------------

    /**
     * Loose uid of the user who approved the note.
     * CR-INC05-03: set from the APPROVING user's uid (ctx.actorUsername()), NOT from the creator.
     * Legacy bug (DeceasedNote.java:71-72): legacy copied this from the creator — corrected here.
     */
    @Column(name = "approved_by_user_uid", length = 26)
    private String approvedByUserUid;

    /**
     * Loose uid of the business day on which approval occurred.
     */
    @Column(name = "approved_on_day_uid", length = 26)
    private String approvedOnDayUid;

    /**
     * Timestamp when the note was approved (UTC).
     */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /**
     * Business day uid at time of note creation (V28 column).
     */
    @Column(name = "business_day_uid", length = 26)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor — OPD/consultation path
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING deceased note bound to a consultation (OPD path).
     *
     * <p>Guards (enforced by the service layer before calling this):
     * <ul>
     *   <li>patientSummary and causeOfDeath are both non-blank.</li>
     *   <li>No existing DeceasedNote for this consultation (reuse if exists).</li>
     * </ul>
     *
     * <p>Legacy citation: DeceasedNote.java:36-76 (OPD constructor shape).
     *
     * @param consultation   the owning consultation (intra-module)
     * @param patientUid     loose uid of the patient
     * @param patientSummary the patient summary narrative (non-blank, enforced by caller)
     * @param causeOfDeath   the cause of death narrative (non-blank, enforced by caller)
     * @param deathDate      date of death (client-supplied, verbatim)
     * @param deathTime      time of death (client-supplied, verbatim)
     * @param businessDayUid loose uid of the current open business day
     */
    public DeceasedNote(Consultation consultation,
                        String patientUid,
                        String patientSummary,
                        String causeOfDeath,
                        LocalDate deathDate,
                        LocalTime deathTime,
                        String businessDayUid) {
        this.consultation = consultation;
        this.admissionUid = null; // OPD path — no admission
        this.patientUid = patientUid;
        this.patientSummary = patientSummary;
        this.causeOfDeath = causeOfDeath;
        this.deathDate = deathDate;
        this.deathTime = deathTime;
        this.status = DeceasedNoteStatus.PENDING;
        this.businessDayUid = businessDayUid;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle
    // -------------------------------------------------------------------------

    /**
     * Update the narrative fields of a PENDING note (reuse-if-exists pattern).
     *
     * <p>Called when {@code save_deceased_note} is invoked but an existing PENDING note
     * already exists for the consultation. The note is updated in-place rather than creating
     * a duplicate (legacy reuse pattern).
     *
     * @param patientSummary the updated patient summary
     * @param causeOfDeath   the updated cause of death
     * @param deathDate      the updated date of death
     * @param deathTime      the updated time of death
     */
    public void updateNarrative(String patientSummary, String causeOfDeath,
                                LocalDate deathDate, LocalTime deathTime) {
        this.patientSummary = patientSummary;
        this.causeOfDeath = causeOfDeath;
        this.deathDate = deathDate;
        this.deathTime = deathTime;
    }

    /**
     * Approve the note: PENDING → APPROVED.
     *
     * <p>Sets the approval audit triplet. The approver is the REAL approving user
     * (CR-INC05-03 — NOT copied from the creator).
     *
     * <p>Guard: caller must verify status == PENDING before calling (or handle the
     * HELD-gate check at the service layer).
     *
     * @param approverUserUid loose uid of the approving user (from ctx.actorUsername())
     * @param dayUid          current business day uid
     * @param now             current instant
     */
    public void approve(String approverUserUid, String dayUid, Instant now) {
        this.status = DeceasedNoteStatus.APPROVED;
        this.approvedByUserUid = approverUserUid;
        this.approvedOnDayUid = dayUid;
        this.approvedAt = now;
    }
}
