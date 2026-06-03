package com.otapp.hmis.iam.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NurseRepository extends JpaRepository<Nurse, Long> {

    Optional<Nurse> findByUid(String uid);

    Optional<Nurse> findByUser(User user);
}
