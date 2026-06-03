package com.otapp.hmis.iam.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashierRepository extends JpaRepository<Cashier, Long> {

    Optional<Cashier> findByUid(String uid);

    Optional<Cashier> findByUser(User user);
}
