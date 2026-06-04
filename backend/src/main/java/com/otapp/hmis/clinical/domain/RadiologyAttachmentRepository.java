package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link RadiologyAttachment}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Attachment count gate: PatientServiceImpl.java:2928-2930 (max 5 per radiology order)</li>
 *   <li>List attachments: PatientResource.java:6154 (download gated on VERIFIED)</li>
 * </ul>
 */
public interface RadiologyAttachmentRepository extends JpaRepository<RadiologyAttachment, Long> {

    /**
     * Locate an attachment by ULID public identifier.
     */
    Optional<RadiologyAttachment> findByUid(String uid);

    /**
     * Count attachments currently associated with a radiology order.
     *
     * <p>Used by the max-5 gate (PatientServiceImpl.java:2928-2930):
     * if the count is already 5, the attachment is rejected with a 422.
     *
     * @param radiology the owning radiology order entity
     * @return count of attachments for this radiology order
     */
    long countByRadiology(Radiology radiology);

    /**
     * List all attachments for a radiology order, ordered by creation time ascending.
     *
     * @param radiology the owning radiology order entity
     * @return attachments for this radiology order, oldest first
     */
    List<RadiologyAttachment> findByRadiologyOrderByCreatedAtAsc(Radiology radiology);
}
