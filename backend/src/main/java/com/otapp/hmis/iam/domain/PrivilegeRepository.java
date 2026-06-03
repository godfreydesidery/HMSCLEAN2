package com.otapp.hmis.iam.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivilegeRepository extends JpaRepository<Privilege, Long> {

    Optional<Privilege> findByCode(String code);

    Optional<Privilege> findByUid(String uid);

    List<Privilege> findAllByOrderByCodeAsc();

    List<Privilege> findByCategory(String category);
}
