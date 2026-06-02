package com.otapp.hmis.shared.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessDayRepository extends JpaRepository<BusinessDay, Long> {

    Optional<BusinessDay> findByUid(String uid);

    Optional<BusinessDay> findFirstByStatusOrderByOpenedAtDesc(BusinessDay.Status status);
}
