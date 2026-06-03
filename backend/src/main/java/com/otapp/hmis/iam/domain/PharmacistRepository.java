package com.otapp.hmis.iam.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PharmacistRepository extends JpaRepository<Pharmacist, Long> {

    Optional<Pharmacist> findByUid(String uid);

    Optional<Pharmacist> findByUser(User user);
}
