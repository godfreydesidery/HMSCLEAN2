package com.otapp.hmis.iam.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClinicianRepository extends JpaRepository<Clinician, Long> {

    Optional<Clinician> findByUid(String uid);

    Optional<Clinician> findByUser(User user);

    /**
     * Finds all Clinician entities affiliated with the given clinic uid
     * (queries the clinician_clinic_uids @ElementCollection join table).
     *
     * <p>Used by {@link com.otapp.hmis.iam.application.ClinicianAffiliationServiceImpl}
     * to implement the reverse lookup: "which clinicians are at clinic X?".
     *
     * @param clinicUid the opaque clinic uid string
     * @return clinicians whose clinicUids set contains the given uid
     */
    @Query("SELECT c FROM Clinician c JOIN c.clinicUids cu WHERE cu = :clinicUid")
    List<Clinician> findAllByClinicUid(@Param("clinicUid") String clinicUid);
}
