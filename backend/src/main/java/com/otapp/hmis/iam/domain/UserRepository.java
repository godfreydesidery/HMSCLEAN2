package com.otapp.hmis.iam.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByUid(String uid);

    List<User> findAllByOrderByUsernameAsc();

    /**
     * Advance {@code seq_usr_no} and return the raw BIGINT.
     * The service formats this as {@code USR-NNN-NNN} via {@link com.otapp.hmis.iam.application.UserNoFormatter}.
     */
    @Query(nativeQuery = true, value = "SELECT nextval('seq_usr_no')")
    Long nextUserNo();
}
