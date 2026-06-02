package com.otapp.hmis.shared.domain;

import java.time.Instant;

/**
 * Per-operation audit context (ADR-0008 §3, increment-00 spec).
 *
 * <p>A plain record constructed once per logical operation and threaded explicitly into every
 * module-API command. There is deliberately <strong>no Spring bean and no ThreadLocal</strong> —
 * this prevents the legacy "179-scattered-dayService-call" anti-pattern from recurring.
 *
 * @param dayUid          the open business-day uid the operation is stamped against
 * @param timestamp       the logical operation timestamp (UTC)
 * @param actorUsername   the acting principal's username
 */
public record TxAuditContext(String dayUid, Instant timestamp, String actorUsername) {
}
