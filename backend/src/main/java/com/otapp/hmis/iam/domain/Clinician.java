package com.otapp.hmis.iam.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Clinician personnel extension (07-DECISIONS-RATIFIED §C).
 *
 * <p>Created when a {@link User} is assigned the {@code CLINICIAN} role; deactivated when that
 * role is removed (UserAdminService.saveUser lifecycle). Code and name fields are copied from
 * the parent User on create/update (service enforces equality).
 *
 * <p>{@code clinicUids} is an @ElementCollection of opaque clinic-uid strings (CR-08 /
 * build-spec §5.2). This faithfully reproduces the legacy {@code Clinician.clinics}
 * {@code @ManyToMany} (Clinician.java:69-71) while keeping the {@code iam} module independent
 * of the {@code masterdata} module — no JPA relation to {@code masterdata.Clinic}, only loose
 * VARCHAR(26) uid references. Stored in {@code clinician_clinic_uids} (V10 migration).
 *
 * <p>Legacy source: {@code com.orbix.api.domain.Clinician}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "clinicians")
public class Clinician extends AuditableEntity {

    @Column(name = "code", length = 20)
    private String code;

    /** Free-text specialty/type; nullable; NOT auto-populated on create (AMB-4). */
    @Column(name = "type", length = 60)
    private String type;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "middle_name", length = 80)
    private String middleName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "nickname", length = 80)
    private String nickname;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    /** FK on this table (user_id). UNIQUE enforced by DB (AMB-1: one clinician per user). */
    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * Affiliated clinic UIDs (CR-08 / build-spec §5.2).
     *
     * <p>Opaque string references to {@code masterdata.Clinic.uid} values — stored without a
     * DB-level FK so the {@code iam} module remains independent of {@code masterdata}.
     * The join table {@code clinician_clinic_uids} is created by V10.
     *
     * <p>Legacy model: {@code Clinician.clinics Collection<Clinic>} @ManyToMany owning side
     * (Clinician.java:69-71). Here reproduced as loose uid strings (CR-08 decision).
     *
     * <p>Lombok getter suppressed — a hand-written {@link #getClinicUids()} returns an
     * unmodifiable view so callers cannot bypass the domain methods.
     */
    @Getter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "clinician_clinic_uids",
            joinColumns = @JoinColumn(name = "clinician_id"))
    @Column(name = "clinic_uid", length = 26, nullable = false)
    private Set<String> clinicUids = new HashSet<>();

    // -----------------------------------------------------------------------
    // Domain methods
    // -----------------------------------------------------------------------

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public void copyFrom(User user) {
        this.user = user;
        this.code = user.getUserNo();
        this.firstName = user.getFirstName();
        this.middleName = user.getMiddleName();
        this.lastName = user.getLastName();
        this.nickname = user.getNickname();
    }

    /** Package-visible factory — construction is a service concern, not a public API. */
    public static Clinician forUser(User user) {
        Clinician c = new Clinician();
        c.copyFrom(user);
        return c;
    }

    // -----------------------------------------------------------------------
    // Affiliation domain methods (CR-08 / build-spec §5.2)
    // -----------------------------------------------------------------------

    /**
     * Idempotently adds a clinic-uid to this clinician's affiliation set.
     * The caller (ClinicianAffiliationServiceImpl) is responsible for transaction management.
     *
     * @param clinicUid the masterdata Clinic.uid — an opaque string (no FK)
     */
    public void affiliateClinic(String clinicUid) {
        this.clinicUids.add(clinicUid);
    }

    /**
     * Idempotently removes a clinic-uid from this clinician's affiliation set.
     * Safe to call when the uid is not present (no-op).
     *
     * @param clinicUid the masterdata Clinic.uid to remove
     */
    public void removeClinic(String clinicUid) {
        this.clinicUids.remove(clinicUid);
    }

    /**
     * Returns an unmodifiable view of the affiliated clinic UIDs.
     *
     * @return unmodifiable set of clinic uid strings
     */
    public Set<String> getClinicUids() {
        return Collections.unmodifiableSet(clinicUids);
    }
}
