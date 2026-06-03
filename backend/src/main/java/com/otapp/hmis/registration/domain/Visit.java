package com.otapp.hmis.registration.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Per-encounter log entry for a patient visit (Visit.java:33-61).
 *
 * <p>A {@link Patient} may have many visits (ManyToOne from Visit to Patient).
 * A FIRST visit is created at registration; a SUBSEQUENT visit is created on each
 * {@code send-to-doctor} call — unconditionally, with no same-day deduplication
 * (PatientServiceImpl.java:499; build-spec §3.2 step 8).
 *
 * <p>{@code type} is copied from {@link Patient#getType()} at the time of visit creation
 * (Visit.java:44-45).
 *
 * <p>{@code status} is {@link VisitStatus#PENDING} only in inc-03 (CR-18).  Later
 * increments that mutate visit status MUST widen {@code ck_visits_status} via an additive
 * migration (ADR-0008-R2).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: domain/Visit.java:33-61</li>
 *   <li>FIRST visit creation: PatientServiceImpl.java:409-419</li>
 *   <li>SUBSEQUENT visit creation: PatientServiceImpl.java:501-512</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "visits")
public class Visit extends AuditableEntity {

    /**
     * The patient this visit belongs to (Visit.java:49-52).
     * ManyToOne: many visits per patient.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    /**
     * Visit sequence within the patient's care journey (Visit.java:42-43).
     * FIRST on registration; SUBSEQUENT on send-to-doctor; SUBSEQUENT_FOR_ADMISSION
     * on admission booking (inc-06, deferred).
     * Stored as VARCHAR(30) — see {@link VisitSequence} for the hyphen→underscore decision.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "sequence", length = 30, nullable = false)
    private VisitSequence sequence;

    /**
     * Patient type at the time of visit creation, copied from {@link Patient#getType()}
     * (Visit.java:44-45).  Stored as VARCHAR(20) free-text to mirror the patient.type column.
     */
    @NotBlank
    @Column(name = "type", length = 20, nullable = false)
    private String type;

    /**
     * Visit status.  Only {@link VisitStatus#PENDING} in inc-03 (CR-18).
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private VisitStatus status = VisitStatus.PENDING;

    /**
     * Loose cross-module ref to the open business day at time of visit creation (no FK).
     * Sourced from {@code BusinessDayService.currentUid()} (ADR-0009 §7).
     */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false, updatable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING visit.
     *
     * @param patient        the patient (intra-module FK)
     * @param sequence       FIRST / SUBSEQUENT / SUBSEQUENT_FOR_ADMISSION
     * @param businessDayUid loose uid of the current open business day
     */
    public Visit(Patient patient, VisitSequence sequence, String businessDayUid) {
        this.patient = patient;
        this.sequence = sequence;
        // type is copied from the patient's current type at creation time
        this.type = patient.getType().name();
        this.status = VisitStatus.PENDING;
        this.businessDayUid = businessDayUid;
    }
}
