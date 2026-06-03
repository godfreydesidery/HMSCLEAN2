package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link InsuranceProvider} (build-spec §1.4).
 */
public interface InsuranceProviderRepository extends JpaRepository<InsuranceProvider, Long> {

    Optional<InsuranceProvider> findByUid(String uid);

    List<InsuranceProvider> findAllByOrderByNameAsc();

    boolean existsByCode(String code);

    boolean existsByName(String name);
}
