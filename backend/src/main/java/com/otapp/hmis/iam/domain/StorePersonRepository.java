package com.otapp.hmis.iam.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorePersonRepository extends JpaRepository<StorePerson, Long> {

    Optional<StorePerson> findByUid(String uid);

    Optional<StorePerson> findByUser(User user);
}
