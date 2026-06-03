package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link LabTestTypeRange}.
 *
 * <p>{@code findAllByLabTestType} reproduces the legacy
 * {@code LabTestTypeRangeRepository.findAllByLabTestType} used by {@code LabTestTypeRangeResource}
 * to list ranges for a given type.
 */
public interface LabTestTypeRangeRepository extends JpaRepository<LabTestTypeRange, Long> {

    Optional<LabTestTypeRange> findByUid(String uid);

    List<LabTestTypeRange> findAllByLabTestType(LabTestType labTestType);
}
