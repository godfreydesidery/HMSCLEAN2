package com.otapp.hmis.iam.lookup;

import java.util.List;

/**
 * Cross-module read projection for a user (build-spec §5, ADR-0008).
 *
 * <p>This is the ONLY user representation visible outside the {@code iam} module. No internal
 * id, no password hash, no domain {@code @Entity} reference.
 *
 * @param uid         the user's public ULID
 * @param username    login username
 * @param displayName {@code firstName + " " + lastName}, or nickname fallback
 * @param enabled     whether the account is active
 * @param roleNames   names of assigned roles
 */
public record UserSummary(
        String uid,
        String username,
        String displayName,
        boolean enabled,
        List<String> roleNames
) {
}
