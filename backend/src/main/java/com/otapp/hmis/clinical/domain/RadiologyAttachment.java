package com.otapp.hmis.clinical.domain;

import com.otapp.hmis.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A named result file attachment on a radiology order (RadiologyAttachment.java:28-50, V25).
 *
 * <p>Stores a file name reference — NOT a bytea blob. The actual file storage is out of scope;
 * only {@code name} and {@code fileName} are persisted here.
 *
 * <p><strong>Distinction from the inline blob:</strong>
 * {@link Radiology} also carries an inline {@code attachment BYTEA} column (set at verify time
 * from Radiology.java:50). This child table holds SEPARATE named file references uploaded
 * independently. Both mechanisms co-exist on the same radiology order.
 *
 * <p><strong>Uniqueness:</strong>
 * {@code fileName} carries a DB-level UNIQUE constraint
 * ({@code uq_radiology_attachments_file_name} from V25). Globally unique across all radiology
 * attachments.
 *
 * <p><strong>Lifecycle rules (application-enforced, NOT DB constraints):</strong>
 * <ul>
 *   <li>Max 5 attachments per order (PatientServiceImpl.java:2928-2930).</li>
 *   <li>Attach only when parent status == ACCEPTED (PatientServiceImpl.java:2931-2933).
 *       NOTE: ACCEPTED gate — different from lab attachments (COLLECTED gate).</li>
 *   <li>Download/view gated on parent status == VERIFIED (PatientResource.java:6154).</li>
 *   <li>Delete blocked when parent status == VERIFIED (order is finalized).</li>
 * </ul>
 *
 * <p><strong>Cascade / orphanRemoval:</strong>
 * The FK {@code radiology_id} carries {@code ON DELETE CASCADE} (V25). The JPA side uses
 * {@code orphanRemoval=true} on the {@link Radiology#getRadiologyAttachments()} collection.
 * Deleting a Radiology hard-deletes all its attachments.
 *
 * <p>Intra-module {@code @ManyToOne} — both {@code Radiology} and {@code RadiologyAttachment}
 * live in the {@code clinical} module; the FK is safe and correct per ADR-0008.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: RadiologyAttachment.java:28-50</li>
 *   <li>Max 5 rule: PatientServiceImpl.java:2928-2930</li>
 *   <li>ACCEPTED gate: PatientServiceImpl.java:2931-2933</li>
 *   <li>VERIFIED download gate: PatientResource.java:6154</li>
 *   <li>File name unique: V25 uq_radiology_attachments_file_name</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "radiology_attachments")
public class RadiologyAttachment extends AuditableEntity {

    /**
     * Human-readable display name for the attachment (e.g., "Chest X-Ray").
     * Nullable — legacy entity has no @NotBlank on name.
     */
    @Column(name = "name", length = 200)
    private String name;

    /**
     * File name / storage reference (globally unique per V25 constraint).
     * NOT NULL. Uniqueness enforced at DB level ({@code uq_radiology_attachments_file_name}).
     */
    @NotBlank
    @Column(name = "file_name", length = 400, nullable = false, unique = true)
    private String fileName;

    /**
     * Intra-module real FK to the owning radiology order (@ManyToOne, updatable=false).
     * Both entities are in the clinical module — the FK is correct and safe (ADR-0008).
     * NOT NULL: every attachment belongs to exactly one radiology order.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "radiology_id", nullable = false, updatable = false)
    private Radiology radiology;

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Create a new RadiologyAttachment bound to the given Radiology order.
     *
     * <p>The caller must have verified the attachment rules before calling this:
     * <ul>
     *   <li>Parent status == ACCEPTED.</li>
     *   <li>Current attachment count &lt; 5.</li>
     * </ul>
     *
     * @param radiology the owning radiology order (intra-module real @ManyToOne)
     * @param name      display name (nullable)
     * @param fileName  file name / storage reference (globally unique, NOT NULL)
     * @return a new RadiologyAttachment ready to persist
     */
    public static RadiologyAttachment create(Radiology radiology, String name, String fileName) {
        RadiologyAttachment a = new RadiologyAttachment();
        a.radiology = radiology;
        a.name = name;
        a.fileName = fileName;
        return a;
    }
}
