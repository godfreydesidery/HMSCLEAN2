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
 * A result file attachment on a laboratory test order (LabTestAttachment.java:35-57, V24).
 *
 * <p>Stores a file name reference — NOT a bytea blob. The legacy lab attachment stores
 * filename refs (unlike radiology which may store bytes elsewhere). The actual file storage
 * is out of scope; only {@code name} and {@code fileName} are persisted here.
 *
 * <p><strong>Uniqueness:</strong>
 * {@code fileName} carries a DB-level UNIQUE constraint ({@code uq_lab_test_attachments_file_name}
 * from V24). This is globally unique — no two attachments across all lab tests may share
 * the same file name reference.
 *
 * <p><strong>Lifecycle rules (application-enforced, NOT DB constraints):</strong>
 * <ul>
 *   <li>Max 5 attachments per lab test (PatientServiceImpl.java:2828-2830).</li>
 *   <li>Attach only when parent status == COLLECTED (PatientServiceImpl.java:2832-2834).</li>
 *   <li>Download/view gated on parent status == VERIFIED (PatientResource.java:6021).</li>
 *   <li>Delete blocked when parent status == VERIFIED (order is finalized).</li>
 * </ul>
 *
 * <p><strong>Cascade / orphanRemoval:</strong>
 * The FK {@code lab_test_id} carries {@code ON DELETE CASCADE} (V24). The JPA side uses
 * {@code orphanRemoval=true} on the {@link LabTest#getLabTestAttachments()} collection.
 * Deleting a LabTest hard-deletes all its attachments. Removing an attachment from the
 * collection also hard-deletes it.
 *
 * <p>Intra-module {@code @ManyToOne} — both {@code LabTest} and {@code LabTestAttachment}
 * live in the {@code clinical} module; the FK is safe and correct per ADR-0008.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Entity shape: LabTestAttachment.java:35-57</li>
 *   <li>Max 5 rule: PatientServiceImpl.java:2828-2830</li>
 *   <li>COLLECTED gate: PatientServiceImpl.java:2832-2834</li>
 *   <li>VERIFIED download gate: PatientResource.java:6021</li>
 *   <li>File name unique: V24 uq_lab_test_attachments_file_name</li>
 * </ul>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "lab_test_attachments")
public class LabTestAttachment extends AuditableEntity {

    /**
     * Human-readable display name for the attachment (e.g., "CBC Report").
     * Nullable — legacy entity has no @NotBlank on name.
     */
    @Column(name = "name", length = 200)
    private String name;

    /**
     * File name / storage reference (globally unique per V24 constraint).
     * NOT NULL. Uniqueness enforced at DB level ({@code uq_lab_test_attachments_file_name}).
     */
    @NotBlank
    @Column(name = "file_name", length = 400, nullable = false, unique = true)
    private String fileName;

    /**
     * Intra-module real FK to the owning lab test (@ManyToOne, updatable=false).
     * Both entities are in the clinical module — the FK is correct and safe (ADR-0008).
     * NOT NULL: every attachment belongs to exactly one lab test.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lab_test_id", nullable = false, updatable = false)
    private LabTest labTest;

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Create a new LabTestAttachment bound to the given LabTest.
     *
     * <p>The caller must have verified the attachment rules before calling this:
     * <ul>
     *   <li>Parent status == COLLECTED.</li>
     *   <li>Current attachment count < 5.</li>
     * </ul>
     *
     * @param labTest  the owning lab test (intra-module real @ManyToOne)
     * @param name     display name (nullable)
     * @param fileName file name / storage reference (globally unique, NOT NULL)
     * @return a new LabTestAttachment ready to persist
     */
    public static LabTestAttachment create(LabTest labTest, String name, String fileName) {
        LabTestAttachment a = new LabTestAttachment();
        a.labTest = labTest;
        a.name = name;
        a.fileName = fileName;
        return a;
    }
}
