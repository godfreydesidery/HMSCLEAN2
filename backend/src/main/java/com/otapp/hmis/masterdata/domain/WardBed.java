package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Physical bed master record (legacy {@code com.orbix.api.domain.WardBed}, WardBed.java:38-55).
 *
 * <p>{@code no} is NOT unique — legacy {@code @Column(nullable=false)} has no {@code unique=true}
 * (WardBed.java:41-42, CR-16). {@code status} is free-text with no enum (WardBed.java:43).
 * {@code ward} FK is {@code updatable=false} (WardBed.java:46-49).
 *
 * <p>This is the physical-bed master, distinct from {@code AdmissionBed} (occupancy/billing
 * transaction, OUT OF SCOPE for P1).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "ward_beds")
public class WardBed extends AuditableEntity {

    /** Bed number/label. NOT unique (CR-16 / legacy WardBed.java:41-42). */
    @NotBlank
    @Column(name = "no", length = 40, nullable = false)
    private String no;

    /** Free-text status (no enum in legacy). */
    @Column(name = "status", length = 40)
    private String status;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * Mandatory FK to the owning {@link Ward} (WardBed.java:46-49, optional=false,
     * updatable=false, @OnDelete NO_ACTION). EAGER fetch mirrors legacy.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ward_id", nullable = false, updatable = false)
    private Ward ward;

    public WardBed(String no, String status, boolean active, Ward ward) {
        this.no = no;
        this.status = status;
        this.active = active;
        this.ward = ward;
    }

    /**
     * Mutates the mutable fields. {@code ward} is intentionally excluded — the FK is
     * {@code updatable=false} in legacy (WardBed.java:49).
     */
    public void update(String no, String status, boolean active) {
        this.no = no;
        this.status = status;
        this.active = active;
    }

    /**
     * Mutate ONLY the {@code status} field, leaving {@code no} and {@code active} unchanged.
     *
     * <p>Used by {@code WardBedClaimImpl} (in {@code masterdata.application}) for the three
     * atomic bed-state transitions driven by the inpatient lifecycle:
     * EMPTY → WAITING ({@code claimBed}), WAITING → OCCUPIED ({@code occupyBed}),
     * * → EMPTY ({@code freeBed}).
     *
     * <p>The broader {@link #update} method is NOT used here because the bed-claim seam must
     * not overwrite {@code no} or {@code active} as a side-effect — those fields are managed
     * by the masterdata admin path only (WardBed.java:64-68, inc-07 SEAM-1).
     *
     * <p>Legacy citation: PatientServiceImpl.java:1703-1711 (claimBed guard sets WAITING);
     * PatientBillResource.java:352-365 (payment-driven OCCUPIED); get_*_summary frees bed.
     * CR-07-Q3 / ADR-0017 ratified.
     *
     * @param status the new free-text status value (EMPTY / WAITING / OCCUPIED — CR-16)
     */
    public void changeStatus(String status) {
        this.status = status;
    }
}
