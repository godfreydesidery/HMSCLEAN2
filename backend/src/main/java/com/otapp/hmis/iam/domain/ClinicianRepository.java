package com.otapp.hmis.iam.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicianRepository extends JpaRepository<Clinician, Long> {

    Optional<Clinician> findByUid(String uid);

    Optional<Clinician> findByUser(User user);
}
