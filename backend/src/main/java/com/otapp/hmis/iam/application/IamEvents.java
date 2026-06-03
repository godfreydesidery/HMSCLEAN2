package com.otapp.hmis.iam.application;

import java.util.List;

/**
 * IAM domain events (build-spec §6). All are records carrying only uid/performedBy — NO numeric
 * ids, NO password hashes, NO PHI beyond uid and username (DIRECTIVE: PHI in logs).
 *
 * <p>Published via {@code ApplicationEventPublisher} inside the service transaction;
 * consumed by {@code @TransactionalEventListener(phase=AFTER_COMMIT)} listeners in this class
 * that log at INFO with structured key=value pairs.
 */
public final class IamEvents {

    private IamEvents() {}

    public record UserCreatedEvent(String userUid, String performedBy) {}

    public record UserUpdatedEvent(String userUid, String performedBy) {}

    public record UserPasswordChangedEvent(String userUid, String performedBy) {}

    public record UserDeletedEvent(String userUid, String performedBy) {}

    public record UserRolesAssignedEvent(String userUid, List<String> roleNames, String performedBy) {}

    public record UserActivatedEvent(String userUid, String performedBy) {}

    public record UserDeactivatedEvent(String userUid, String performedBy) {}

    public record RoleCreatedEvent(String roleUid, String performedBy) {}

    public record RoleUpdatedEvent(String roleUid, String performedBy) {}

    public record RoleDeletedEvent(String roleUid, String performedBy) {}

    public record RolePrivilegesReplacedEvent(String roleUid, List<String> privilegeCodes, String performedBy) {}

    public record RefreshTokenReuseDetectedEvent(String userUid, String performedBy) {}

    public record RefreshTokenRevokedEvent(String userUid, String performedBy) {}
}
