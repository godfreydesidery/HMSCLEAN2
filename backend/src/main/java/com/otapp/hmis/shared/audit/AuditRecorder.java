package com.otapp.hmis.shared.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit append-only audit recorder (ADR-0007).
 *
 * <p>Writes one {@link AuditLog} row in the same ACID transaction as the operation being audited
 * ({@link Propagation#MANDATORY} forbids an orphaned row outside a transaction). The actor is read
 * from the Spring Security context, falling back to {@code SYSTEM}. Constructor injection is
 * Lombok-generated (DIRECTIVE 1); the {@code clock} defaults to UTC.
 */
@Service
@RequiredArgsConstructor
public class AuditRecorder {

    static final String SYSTEM_ACTOR = "SYSTEM";

    private final AuditLogRepository repository;
    private Clock clock = Clock.systemUTC();

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog record(String entityType, String entityUid, AuditAction action) {
        return record(entityType, entityUid, action, currentActor());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog record(String entityType, String entityUid, AuditAction action, String actorUsername) {
        Instant occurredAt = Instant.now(clock);
        String actor = actorUsername != null ? actorUsername : SYSTEM_ACTOR;
        String checksum = checksum(entityType, entityUid, action, actor, occurredAt);
        return repository.save(new AuditLog(entityType, entityUid, action, actor, occurredAt, checksum));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return SYSTEM_ACTOR;
        }
        return authentication.getName();
    }

    static String checksum(String entityType, String entityUid, AuditAction action, String actor, Instant occurredAt) {
        String canonical = String.join("|",
                entityType,
                entityUid != null ? entityUid : "",
                action.name(),
                actor,
                occurredAt.toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
