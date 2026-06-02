package com.otapp.hmis.shared.audit;

import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Append-only audit repository (ADR-0007). Deliberately a narrow {@link Repository} that exposes
 * only insert and read operations — no update or delete is offered to application code.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog auditLog);

    List<AuditLog> findByEntityUidOrderByOccurredAtAsc(String entityUid);

    List<AuditLog> findByEntityTypeOrderByOccurredAtAsc(String entityType);

    List<AuditLog> findByActorUsernameOrderByOccurredAtAsc(String actorUsername);

    long count();
}
