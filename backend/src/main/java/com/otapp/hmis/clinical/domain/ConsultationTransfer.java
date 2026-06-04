package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ConsultationTransfer aggregate — a clinic-to-clinic hand-off request on a consultation
 * (inc-05 C3, ADR-0022 D4).
 *
 * <p>Lifecycle: {@link ConsultationTransferStatus#PENDING} → {@link ConsultationTransferStatus#COMPLETED}
 * (when patient is re-booked to the destination clinic) or
 * {@link ConsultationTransferStatus#CANCELED} (explicit cancel).
 *
 * <p><strong>Cross-module discipline (ADR-0022 D2 Correction):</strong>
 * <ul>
 *   <li>{@code consultation_id} → real intra-module FK to {@code consultations.id}. RETAINED:
 *       both tables live in the {@code clinical} module; this is a legal intra-module association.</li>
 *   <li>{@code patient_uid} → loose VARCHAR(26), NO FK. V22 originally created this as a real FK
 *       {@code patient_id → patients(id)}; V30 backfills and drops that column exactly as V29 did
 *       for {@code consultations}. Clinical references registration aggregates by uid only
 *       (ADR-0008 §1).</li>
 *   <li>{@code destination_clinic_uid} → loose cross-module ref (masterdata). No FK (ADR-0008).</li>
 *   <li>{@code business_day_uid} → loose cross-module ref. No FK (ADR-0008).</li>
 * </ul>
 *
 * <p><strong>Fields NOT on this entity (ADR-verified):</strong>
 * No clinician field, no source-clinic field, no acceptedBy field — the destination clinician
 * is chosen at the rebook/accept time (via the ConsultationBookingService.book clinicianUserUid
 * parameter). The source clinic is derivable from the linked source consultation.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: ConsultationTransfer.java:35-65</li>
 *   <li>RAISE: PatientServiceImpl.java:2756-2808 (createConsultationTransfer)</li>
 *   <li>CANCEL: PatientServiceImpl.java:2810-2830 (cancelConsultationTransfer)</li>
 *   <li>COMPLETE (seam): PatientServiceImpl.java:431-435 (doConsultation pending-transfer check)</li>
 *   <li>Queue: PatientResource.java:599 (get_consultation_transfers — findAllByStatus PENDING)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "consultation_transfers")
public class ConsultationTransfer extends AuditableEntity {

    /**
     * Lifecycle status — PENDING / COMPLETED / CANCELED.
     *
     * <p>All three values are valid Java identifiers; {@code @Enumerated(STRING)} persists them
     * verbatim. The V22 DB CHECK enforces the same three values.
     * (ConsultationTransfer.java:40-41, V22 CHECK constraint)
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ConsultationTransferStatus status = ConsultationTransferStatus.PENDING;

    /**
     * Free-text rationale for the transfer. Never validated — nullable, no length constraint.
     * (ConsultationTransfer.java:43)
     */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /**
     * Intra-module real FK to the source consultation (updatable=false — set at creation,
     * the source consultation never changes on a transfer).
     *
     * <p>Legal intra-module JPA association: both {@code consultation_transfers} and
     * {@code consultations} live in the {@code clinical} module. This is NOT a cross-module
     * boundary violation (ADR-0022 D5, ADR-0008 §1).
     * (ConsultationTransfer.java:50-53)
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false, updatable = false)
    private Consultation consultation;

    /**
     * Loose cross-module ref to the patient (ADR-0022 D2 Correction — replaces patient_id FK).
     *
     * <p>V22 originally created {@code patient_id BIGINT NOT NULL FK → patients(id)}.
     * V30 backfills this column from {@code patients.uid} and drops {@code patient_id} + its FK.
     * Clinical references registration aggregates by uid only (ADR-0008 §1).
     */
    @NotBlank
    @Column(name = "patient_uid", length = 26, nullable = false, updatable = false)
    private String patientUid;

    /**
     * Loose cross-module ref to the DESTINATION clinic in the masterdata module.
     * No FK (ADR-0008). Updatable=true: the destination can be corrected before completion
     * (ConsultationTransfer.java:55-58, updatable=true).
     */
    @NotBlank
    @Column(name = "destination_clinic_uid", length = 26, nullable = false)
    private String destinationClinicUid;

    /**
     * Loose cross-module ref to the open business day at creation time. No FK (ADR-0008).
     */
    @NotBlank
    @Column(name = "business_day_uid", length = 26, nullable = false, updatable = false)
    private String businessDayUid;

    // -------------------------------------------------------------------------
    // Business constructor
    // -------------------------------------------------------------------------

    /**
     * Create a new PENDING transfer.
     *
     * @param consultation        the source consultation (intra-module FK — clinical-owned entity)
     * @param patientUid          loose uid of the patient (registration module, ADR-0008)
     * @param destinationClinicUid loose uid of the destination clinic (masterdata module, ADR-0008)
     * @param reason              free-text rationale (nullable, never validated)
     * @param businessDayUid      loose uid of the current open business day
     */
    public ConsultationTransfer(Consultation consultation,
                                 String patientUid,
                                 String destinationClinicUid,
                                 String reason,
                                 String businessDayUid) {
        this.consultation = consultation;
        this.patientUid = patientUid;
        this.destinationClinicUid = destinationClinicUid;
        this.reason = reason;
        this.businessDayUid = businessDayUid;
        this.status = ConsultationTransferStatus.PENDING;
    }

    // -------------------------------------------------------------------------
    // Domain methods — lifecycle
    // -------------------------------------------------------------------------

    /**
     * Mark this transfer COMPLETED.
     *
     * <p>Called when the patient is re-booked to the destination clinic via the
     * ConsultationBookingService.book path (the completion seam — PatientServiceImpl.java:431-435).
     */
    public void complete() {
        this.status = ConsultationTransferStatus.COMPLETED;
    }

    /**
     * Mark this transfer CANCELED.
     *
     * <p>Called by the cancelConsultationTransfer service method.
     * (PatientServiceImpl.java:2821)
     */
    public void cancel() {
        this.status = ConsultationTransferStatus.CANCELED;
    }
}
