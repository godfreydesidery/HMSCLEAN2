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
 * Accessors are Lombok-generated (DIRECTIVE 1); the password hash is mutated only via the explicit
 * {@link #changePasswordHash(String)} domain method (no public setter).
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

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public void assign(Role role) {
        this.roles.add(role);
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
