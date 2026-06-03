package com.otapp.hmis.iam.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Security principal (ADR-0006). Authorities are the flattened privilege codes of all roles.
 *
 * <p>Increment-01 additions: {@code userNo} (USR-NNN-NNN, immutable once assigned), identity
 * name columns, and {@code enabled} toggle. The password hash is mutated only via
 * {@link #changePasswordHash(String)}; no public setter for any field (DIRECTIVE 1).
 *
 * <p>Legacy source: {@code com.orbix.api.domain.User} (code, firstName, middleName, lastName,
 * nickname, active). The {@code active} flag is renamed {@code enabled} for consistency with
 * Spring Security conventions; the DB column is {@code enabled}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(name = "username", length = 80, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    /**
     * CR-21 (07-DECISIONS-RATIFIED §E): new users start INACTIVE, matching legacy
     * {@code User.java:69} where {@code active} defaulted {@code false} and the saveUser
     * create branch never set it to {@code true}. An admin must activate via the update
     * endpoint before the user can log in. The seeded {@code admin} row is not affected —
     * V2 sets {@code enabled=TRUE} explicitly via SQL.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    // -----------------------------------------------------------------------
    // Increment-01: identity columns (legacy User.java: code/firstName/...)
    // -----------------------------------------------------------------------

    /** USR-NNN-NNN — generated from seq_usr_no, immutable once assigned. */
    @Column(name = "user_no", length = 11, unique = true, updatable = true)
    private String userNo;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "middle_name", length = 80)
    private String middleName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    /** Non-unique display alias (nickname column has a plain index, not unique). */
    @Column(name = "nickname", length = 80)
    private String nickname;

    // -----------------------------------------------------------------------
    // Roles association
    // -----------------------------------------------------------------------

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    // -----------------------------------------------------------------------
    // Domain methods
    // -----------------------------------------------------------------------

    /** Assign the USR-NNN-NNN code. Set-once: throws if already assigned. */
    public void assignUserNo(String userNo) {
        if (this.userNo != null) {
            throw new IllegalStateException("userNo is immutable once assigned");
        }
        this.userNo = userNo;
    }

    /**
     * Update name fields (firstName, middleName, lastName, nickname).
     * Legacy: UserServiceImpl saveUser copies names on create and keeps them on update.
     */
    public void rename(String firstName, String middleName, String lastName, String nickname) {
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.nickname = nickname;
    }

    /** Enable or disable this user. Self-toggle is prevented at the service/controller layer. */
    public void setActiveStatus(boolean enabled) {
        this.enabled = enabled;
    }

    /** Add a single role (idempotent). */
    public void assign(Role role) {
        this.roles.add(role);
    }

    /** Replace the full role set (used by assign-roles endpoint). */
    public void replaceRoles(Set<Role> newRoles) {
        this.roles.clear();
        this.roles.addAll(newRoles);
    }

    /** Replace the stored BCrypt hash (e.g. on password change / admin reset). */
    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /** Flatten all role privileges into the distinct set of privilege CODE strings (ADR-0006). */
    public Set<String> privilegeCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (Role role : roles) {
            for (Privilege privilege : role.getPrivileges()) {
                codes.add(privilege.getCode());
            }
        }
        return codes;
    }
}
