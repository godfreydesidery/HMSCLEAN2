package com.otapp.hmis.iam.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Find all users that have the named role assigned, ordered by username.
     * Used by IamLookupService.findUsersByRole (build-spec §5.2 / CR-08 admin listing).
     *
     * @param roleName the role name to filter by
     * @return users with the given role, ordered by username ascending
     */
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r.name = :roleName ORDER BY u.username ASC")
    List<User> findAllByRoleName(@Param("roleName") String roleName);
}
