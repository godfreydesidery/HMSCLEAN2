package com.otapp.hmis.masterdata.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the {@link Dressing} masterdata entity (inc-07 07b).
 *
 * <p>Legacy citation: Dressing.java:35-49; PatientServiceImpl.java:2094.
 * inc-07 07b / AC-07B-DRS-02.
 */
public interface DressingRepository extends JpaRepository<Dressing, Long> {

    /**
     * Check whether a ProcedureType is registered as a dressing.
     *
     * @param procedureTypeUid loose uid of the procedure type
     * @return the Dressing entry if the procedure type is listed as a dressing, else empty
     */
    Optional<Dressing> findByProcedureTypeUid(String procedureTypeUid);
}
