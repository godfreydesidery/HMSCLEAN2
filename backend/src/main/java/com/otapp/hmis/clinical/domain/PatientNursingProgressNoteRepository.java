package com.otapp.hmis.clinical.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientNursingProgressNote} (inc-07 07b).
 *
 * <p>Legacy citation: PatientNursingProgressNote.java:38; PatientResource.java:3145-3161.
 * inc-07 07b / AC-07B-NPR-01.
 */
public interface PatientNursingProgressNoteRepository
        extends JpaRepository<PatientNursingProgressNote, Long> {

    /** Find by ULID public uid. */
    Optional<PatientNursingProgressNote> findByUid(String uid);

    /** All progress notes for a given admission, oldest first. */
    List<PatientNursingProgressNote> findByAdmissionUidOrderByCreatedAtAsc(String admissionUid);
}
