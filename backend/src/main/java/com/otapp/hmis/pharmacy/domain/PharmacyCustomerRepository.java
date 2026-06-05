package com.otapp.hmis.pharmacy.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link PharmacyCustomer} (inc-08a chunk 4).
 *
 * <p>{@link #nextPcstNo()} performs the single atomic {@code nextval(seq_pcst_no)} for the
 * sequence-backed customer number (CR-09-NUM1). The service formats it as {@code 'PCST/' + seq}.
 */
public interface PharmacyCustomerRepository extends JpaRepository<PharmacyCustomer, Long> {

    Optional<PharmacyCustomer> findByUid(String uid);

    @Query(nativeQuery = true, value = "SELECT nextval('seq_pcst_no')")
    Long nextPcstNo();
}
