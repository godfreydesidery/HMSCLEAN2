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
 * Nurse personnel extension (07-DECISIONS-RATIFIED §C).
 *
 * <p>Created when a {@link User} is assigned the {@code NURSE} role; deactivated when removed.
 * Legacy source: {@code com.orbix.api.domain.Nurse}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "nurses")
public class Nurse extends AuditableEntity {

    @Column(name = "code", length = 20)
    private String code;

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

    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id")
    private User user;

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

    public static Nurse forUser(User user) {
        Nurse n = new Nurse();
        n.copyFrom(user);
        return n;
    }
}
