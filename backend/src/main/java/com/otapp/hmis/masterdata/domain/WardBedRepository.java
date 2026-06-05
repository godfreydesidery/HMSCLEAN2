package com.otapp.hmis.masterdata.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WardBedRepository extends JpaRepository<WardBed, Long> {

    Optional<WardBed> findByUid(String uid);

    /**
     * Acquire a PESSIMISTIC_WRITE (SELECT … FOR UPDATE) lock on the WardBed row before reading.
     *
     * <p>Used by {@code WardBedClaimImpl.claimBed} to serialise concurrent bed-claim attempts
     * (inc-07 CR-07-Q3, ADR-0017 ratified). The lock is held until the surrounding
     * {@code @Transactional} method commits or rolls back — the window during which the bed
     * status is inspected and mutated. This prevents the legacy silent-oversell race where two
     * admissions could both read EMPTY and both set WAITING (PatientServiceImpl.java:1703-1711).
     *
     * @param uid the bed's public ULID
     * @return the locked bed row, or {@link Optional#empty()} if absent
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from WardBed b where b.uid = :uid")
    Optional<WardBed> findByUidForUpdate(@Param("uid") String uid);

    List<WardBed> findAllByWardOrderByNoAsc(Ward ward);

    List<WardBed> findAllByOrderByNoAsc();
}
