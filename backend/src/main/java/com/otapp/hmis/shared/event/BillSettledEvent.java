package com.otapp.hmis.shared.event;

import java.time.Instant;

/**
 * Cross-module event published by the {@code billing} module when a CASH bill transitions to
 * PAID and the billing-side {@code settled} flag is set (CR-05, inc-05 §5, ADR-0022 D5).
 *
 * <p>The {@code clinical} module's {@link
 * com.otapp.hmis.clinical.application.ConsultationSettlementListener} handles this event and
 * flips the LOCAL {@code settled} flag on whichever clinical row (Consultation, LabTest,
 * Radiology, Procedure, or Prescription) references this bill uid.
 *
 * <p><strong>Event seam design (inc-05 ADR-0022 D5 — no cycle):</strong>
 * <ul>
 *   <li>This record lives in {@code shared.event} — the {@code shared} module is OPEN
 *       (ADR-0014 §1), so every module can use it without an explicit allowed-dependency edge.</li>
 *   <li>{@code billing} imports only {@code shared} when publishing. It imports NOTHING from
 *       {@code clinical}, so no billing→clinical compile edge is created.</li>
 *   <li>{@code clinical} imports only {@code shared} when consuming. It already depends on
 *       {@code billing::api} (for {@code PaymentMode}, {@code SettlementPolicy}, etc.); this
 *       event adds NO new billing dependency because the event type is in {@code shared.event}.</li>
 *   <li>Result: no cycle. {@code ApplicationModules.verify()} stays green.</li>
 *   <li>The event is published synchronously via {@code ApplicationEventPublisher.publishEvent()}
 *       inside the same DB transaction as the PAID transition. The
 *       {@code @TransactionalEventListener(phase = BEFORE_COMMIT)} listener executes before the
 *       outer commit, so the clinical {@code settled} flip and the billing PAID transition are
 *       fully atomic: both commit or both roll back.</li>
 * </ul>
 *
 * <p><strong>PHI note:</strong>
 * The bill uid is a ULID (not a patient name, diagnosis, or financial identifier). It is safe
 * to include in structured log messages at DEBUG level.
 *
 * @param billUid   the ULID of the PatientBill that was just marked PAID and settled
 * @param settledAt the instant at which the bill was settled (from {@code TxAuditContext.timestamp()})
 */
public record BillSettledEvent(String billUid, Instant settledAt) {
}
