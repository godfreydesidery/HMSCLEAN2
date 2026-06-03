package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.IamEvents.RefreshTokenReuseDetectedEvent;
import com.otapp.hmis.iam.application.IamEvents.RefreshTokenRevokedEvent;
import com.otapp.hmis.iam.application.IamEvents.RoleCreatedEvent;
import com.otapp.hmis.iam.application.IamEvents.RoleDeletedEvent;
import com.otapp.hmis.iam.application.IamEvents.RolePrivilegesReplacedEvent;
import com.otapp.hmis.iam.application.IamEvents.RoleUpdatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserActivatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserCreatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserDeactivatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserDeletedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserPasswordChangedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserRolesAssignedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT listeners for IAM domain events (build-spec §6).
 *
 * <p>Logs at INFO using SLF4J structured key=value. The token value and password hash are NEVER
 * logged. Only uid and performedBy (username) appear in logs (PHI classification: low-risk
 * identifiers in INFO context per security-architect guidance).
 */
@Slf4j
@Component
class IamEventListeners {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserCreated(UserCreatedEvent event) {
        log.info("event=UserCreated userUid={} performedBy={}", event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserUpdated(UserUpdatedEvent event) {
        log.info("event=UserUpdated userUid={} performedBy={}", event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserPasswordChanged(UserPasswordChangedEvent event) {
        log.info("event=UserPasswordChanged userUid={} performedBy={}", event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        log.info("event=UserDeleted userUid={} performedBy={}", event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserRolesAssigned(UserRolesAssignedEvent event) {
        log.info("event=UserRolesAssigned userUid={} roles={} performedBy={}",
                event.userUid(), event.roleNames(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserActivated(UserActivatedEvent event) {
        log.info("event=UserActivated userUid={} performedBy={}", event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onUserDeactivated(UserDeactivatedEvent event) {
        log.info("event=UserDeactivated userUid={} performedBy={}", event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onRoleCreated(RoleCreatedEvent event) {
        log.info("event=RoleCreated roleUid={} performedBy={}", event.roleUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onRoleUpdated(RoleUpdatedEvent event) {
        log.info("event=RoleUpdated roleUid={} performedBy={}", event.roleUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onRoleDeleted(RoleDeletedEvent event) {
        log.info("event=RoleDeleted roleUid={} performedBy={}", event.roleUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onRolePrivilegesReplaced(RolePrivilegesReplacedEvent event) {
        log.info("event=RolePrivilegesReplaced roleUid={} codes={} performedBy={}",
                event.roleUid(), event.privilegeCodes(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onRefreshTokenReuseDetected(RefreshTokenReuseDetectedEvent event) {
        log.warn("event=RefreshTokenReuseDetected userUid={} performedBy={}",
                event.userUid(), event.performedBy());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @EventListener
    void onRefreshTokenRevoked(RefreshTokenRevokedEvent event) {
        log.info("event=RefreshTokenRevoked userUid={} performedBy={}", event.userUid(), event.performedBy());
    }
}
