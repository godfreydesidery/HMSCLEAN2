package com.otapp.hmis.iam.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagementRepository extends JpaRepository<Management, Long> {

    Optional<Management> findByUid(String uid);

    Optional<Management> findByUser(User user);
}
