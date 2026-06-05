package com.otapp.hmis.masterdata.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Medication administration-route master (inc-07 07d, CR-07-MAR prerequisite).
 *
 * <p>Admin-managed controlled vocabulary for the route a medication was administered by
 * (IV, PO, IM, SC, PR, SL, INH, TOP…). Net-new — there is NO legacy equivalent; the legacy
 * dosing-note path ({@code PatientPrescriptionChart}) carried free text only. The owner ruled a
 * first-class masterdata table (not an enum, not free text) so routes are addable without a
 * redeploy. Referenced by the {@code MedicationAdministration} aggregate via a loose
 * {@code routeUid}, validated through {@link com.otapp.hmis.masterdata.lookup.RouteLookup}.
 *
 * <p>Mirrors {@link WardCategory} (code / name / description / active + the AuditableEntity
 * forensic triplet).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "administration_routes")
public class AdministrationRoute extends AuditableEntity {

    @NotBlank
    @Column(name = "code", length = 40, nullable = false, unique = true)
    private String code;

    @NotBlank
    @Column(name = "name", length = 200, nullable = false, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    public AdministrationRoute(String code, String name, String description, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
    }

    public void update(String code, String name, String description, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
    }
}
