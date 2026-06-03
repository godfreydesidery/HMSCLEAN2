package com.otapp.hmis.iam.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);

    Optional<Role> findByUid(String uid);

    List<Role> findAllByOrderByNameAsc();
}
