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
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A role aggregates a set of {@link Privilege} codes (ADR-0006: User -&gt; Role -&gt; Privilege).
 *
 * <p>Increment-01 additions: {@code owner} discriminator ({@code SYSTEM} for seeded roles that
 * cannot be deleted/renamed; {@code ORGANIZATION} for user-created roles). The reserved-name
 * guard (15-list from legacy) is enforced in the service layer, not here.
 *
 * <p>Legacy source: {@code com.orbix.api.domain.Role} (owner defaulted to "SYSTEM").
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "roles")
public class Role extends AuditableEntity {

    @Column(name = "name", length = 80, nullable = false, unique = true)
    private String name;

    /**
     * {@code SYSTEM} — seeded role (cannot be renamed or deleted by users).
     * {@code ORGANIZATION} — user-created role.
     */
    @Column(name = "owner", length = 20, nullable = false)
    private String owner = "ORGANIZATION";

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_privileges",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "privilege_id"))
    private Set<Privilege> privileges = new HashSet<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public Role(String name) {
        this.name = name;
        this.owner = "ORGANIZATION";
    }

    public Role(String name, String owner) {
        this.name = name;
        this.owner = owner;
    }

    // -----------------------------------------------------------------------
    // Domain methods
    // -----------------------------------------------------------------------

    /** Add a single privilege (idempotent via Set). */
    public void grant(Privilege privilege) {
        this.privileges.add(privilege);
    }

    /** Add a single privilege alias for consistency with UserAdminService naming. */
    public void addPrivilege(Privilege privilege) {
        this.privileges.add(privilege);
    }

    /** Remove all assigned privileges (used before full-replace). */
    public void removeAllPrivileges() {
        this.privileges.clear();
    }

    /** Full-replace: clear existing set, then add the supplied set. */
    public void replacePrivileges(Set<Privilege> newPrivileges) {
        this.privileges.clear();
        this.privileges.addAll(newPrivileges);
    }

    /** Update role name (service guards reserved names before calling this). */
    public void rename(String newName) {
        this.name = newName;
    }
}
