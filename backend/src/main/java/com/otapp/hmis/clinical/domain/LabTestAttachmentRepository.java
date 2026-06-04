package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link LabTestAttachment}.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Attachment count gate: PatientServiceImpl.java:2828-2830 (max 5 per lab test)</li>
 *   <li>List attachments: PatientResource.java:6021 (download gated on VERIFIED)</li>
 * </ul>
 */
public interface LabTestAttachmentRepository extends JpaRepository<LabTestAttachment, Long> {

    /**
     * Locate an attachment by ULID public identifier.
     */
    Optional<LabTestAttachment> findByUid(String uid);

    /**
     * Count attachments currently associated with a lab test.
     *
     * <p>Used by the max-5 gate (PatientServiceImpl.java:2828-2830):
     * if the count is already 5, the attachment is rejected with a 422.
     *
     * @param labTest the owning lab test entity
     * @return count of attachments for this lab test
     */
    long countByLabTest(LabTest labTest);

    /**
     * List all attachments for a lab test, ordered by creation time ascending.
     *
     * @param labTest the owning lab test entity
     * @return attachments for this lab test, oldest first
     */
    List<LabTestAttachment> findByLabTestOrderByCreatedAtAsc(LabTest labTest);
}
