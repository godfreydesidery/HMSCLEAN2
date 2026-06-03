package com.otapp.hmis.billing.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PatientPayment}.
 *
 * <p>Legacy PatientPaymentRepository was empty (no custom finders — PatientPayment.java:39).
 * We add uid-keyed lookup for the receipt endpoint.
 */
public interface PatientPaymentRepository extends JpaRepository<PatientPayment, Long> {

    Optional<PatientPayment> findByUid(String uid);
}
