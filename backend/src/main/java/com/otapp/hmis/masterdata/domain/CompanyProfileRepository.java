package com.otapp.hmis.masterdata.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Long> {

    Optional<CompanyProfile> findByUid(String uid);

    Optional<CompanyProfile> findFirstByOrderByCreatedAtAsc();
}
