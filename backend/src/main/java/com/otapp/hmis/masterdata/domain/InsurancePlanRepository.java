package com.otapp.hmis.masterdata.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link InsurancePlan} (build-spec §1.4).
 */
public interface InsurancePlanRepository extends JpaRepository<InsurancePlan, Long> {

    Optional<InsurancePlan> findByUid(String uid);

    List<InsurancePlan> findAllByOrderByNameAsc();

    List<InsurancePlan> findAllByInsuranceProviderOrderByNameAsc(InsuranceProvider provider);

    boolean existsByCode(String code);

    boolean existsByName(String name);
}
