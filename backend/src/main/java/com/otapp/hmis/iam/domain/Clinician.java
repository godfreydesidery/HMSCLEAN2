package com.otapp.hmis.iam.domain;

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
 * Clinician personnel extension (07-DECISIONS-RATIFIED §C).
 *
 * <p>Created when a {@link User} is assigned the {@code CLINICIAN} role; deactivated when that
 * role is removed (UserAdminService.saveUser lifecycle). Code and name fields are copied from
 * the parent User on create/update (service enforces equality).
 *
 * <p>Deferred: {@code clinics} collection (clinicians_clinics join table) — the {@code clinics}
 * table does not exist in this increment. Add in the clinical/masterdata increment.
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
}
