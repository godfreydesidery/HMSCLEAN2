package com.otapp.hmis.iam.lookup;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Cross-module read API for the {@code iam} module (build-spec §5, ADR-0008).
 *
 * <p>Implementation is package-private in {@code com.otapp.hmis.iam.application}; consumers in
 * other modules depend on this interface only — never on the domain entities.
 */
public interface IamLookupService {

    /**
     * Find a user by their public uid.
     *
     * @param uid the ULID identifying the user
     * @return the summary, or empty if not found
     */
    Optional<UserSummary> findUser(String uid);

    /**
     * Find multiple users by their public uids (batch lookup for cross-module joins).
     *
     * @param uids the ULIDs to look up
     * @return summaries for found users (missing uids are silently omitted)
     */
    List<UserSummary> findUsers(Collection<String> uids);
}
