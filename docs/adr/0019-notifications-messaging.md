# ADR-0019: Notifications and Outbound Messaging

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

## Context

The engagement mandate requires scoping outbound messaging (email/SMS) based on whether the legacy system exhibits any process-observable notification behaviour.

**Legacy investigation findings (confirmed, not assumed):**

The legacy backend `pom.xml` (Spring Boot 2.2.5, Java 11) contains no `spring-boot-starter-mail`, no Twilio, no SendGrid, and no SMS gateway dependency. `application.properties` contains no SMTP configuration. A full-text search across all 400+ Java files in `com.orbix.api` returns zero matches for `mail`, `smtp`, `email` (as a functional call), `sms`, `sendEmail`, `notification`, or `JavaMailSender`. The only `email` field present is a data-capture string on `User`, `Employee`, `CompanyProfile`, and `ExternalMedicalProvider` — stored as metadata, never sent anywhere.

The prior-attempt build (`D:\My_Works\HMS\HMSCLEAN`) confirms the same: 76 Flyway migrations contain no `outbox`, `notification`, or `message_log` table. The prior build uses Spring `ApplicationEvent` exclusively for intra-JVM coordination (`PatientRegisteredEvent`, `ConsultationBookedEvent`, `ClinicalOrderRaisedEvent`, `PrescriptionRaisedEvent`, `AdmissionAdmittedEvent`). No WebSocket, no SSE, no email/SMS channel exists anywhere. The Angular 18 frontend has no WebSocket library, no SSE consumer, and no push-notification dependency.

**Conclusion for legacy-analyst flag:** The legacy system has zero process-observable outbound notification behaviour. Email addresses are captured but never used for messaging. SMTP credentials referenced in earlier planning documents do not correspond to any wired code path in the running system. This is confirmed across both the legacy source and the prior modernization attempt.

Because notifications are entirely absent from the legacy process, the engagement rules require scoping them as a net-new, opt-in capability behind a feature flag. No fabricated process may be introduced.

There are, however, two real operational needs that motivate a notification foundation:

1. **Real-time intra-hospital queue updates.** The legacy relies on manual page refresh. The prior build's gap audit (ADR-NEW-B) identified polling-based queues as a known problem. Nurse queues, reception queues, and dispense worklists need a "something changed" signal.
2. **Transactional outbound messaging as a future opt-in.** Appointment reminders, discharge summaries to patients, and insurance claim acknowledgements are credible near-term requirements even if absent today.

## Decision

**D1 — Legacy confirmation flag.** Legacy-analyst must formally confirm before V1 implementation begins that no SMTP relay, no SMS gateway, and no notification broker is wired in the production legacy environment. This ADR records the code-level finding; the operational environment must be independently verified. If the analyst confirms a wired but undiscovered channel (e.g., a deploy-time config not in source), this ADR is escalated to Amended and the transactional outbox path below is activated immediately.

**D2 — Real-time intra-hospital queue signals via Server-Sent Events (SSE).** The fresh build will implement SSE (`text/event-stream`) for queue-affecting domain events. SSE is chosen over WebSocket because: (a) queue updates are server-to-client only — no client-to-server data needed; (b) SSE works over standard HTTP/2 with no upgrade handshake, compatible with the existing Spring Security 6 filter chain and CORS config from ADR-0005; (c) Spring 6 `SseEmitter` / `Flux<ServerSentEvent>` is available without additional dependencies; (d) Angular 18 `EventSource` requires no third-party library.

Each Spring Modulith module publishes a typed `ApplicationEvent` (already the pattern in the prior build). A new `messaging` module subscribes to queue-relevant events and broadcasts to registered `SseEmitter` instances scoped by queue identity (e.g., `reception-queue`, `nurse-queue:{wardUid}`, `dispense-queue:{pharmacyUid}`). The emitter registry is an in-memory `ConcurrentHashMap`; in a single-JVM deployment this is sufficient. Horizontal scaling is not in scope (ADR-0013 establishes single-instance deployment).

**D3 — Transactional outbox for email/SMS, feature-flagged, not wired in V1.** The architecture will include a `notification_outbox` table and a `NotificationPort` interface from V1 so the plumbing exists before any channel is activated. The feature flag `hmis.notifications.enabled` defaults to `false`. When `false`, the `NotificationService` implementation is a no-op. No SMTP relay, no SMS gateway, and no third-party notification provider is wired in V1.

The outbox table schema (one Flyway migration, applied unconditionally, but populated only when the flag is enabled):

```
notification_outbox (
  id          BIGSERIAL PRIMARY KEY,
  uid         UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  channel     VARCHAR(10) NOT NULL,        -- EMAIL | SMS
  recipient   VARCHAR(255) NOT NULL,       -- opaque address, never PHI
  template_id VARCHAR(80) NOT NULL,
  payload     JSONB NOT NULL,              -- template variables, no PHI in body
  status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts    SMALLINT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  sent_at     TIMESTAMPTZ,
  error_detail TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
```

A `@Scheduled` poller (60-second interval) picks `PENDING` rows where `next_attempt_at <= NOW()` and `attempts < 5`, dispatches via the active channel adapter, and updates `status` to `SENT` or increments `attempts` with exponential backoff (`next_attempt_at = NOW() + INTERVAL '2^attempts minutes'`). On permanent failure (attempts = 5), status becomes `FAILED` and an `ApplicationEvent` is published for audit.

**PHI constraint (non-negotiable):** message bodies and template variables stored in `payload` JSONB must never contain patient names, MR numbers, diagnosis text, medication names, or any other PHI. Templates receive only opaque identifiers (appointment reference codes, invoice numbers) and facility-generic text. The security-architect must sign off on every template before activation.

**D4 — No SMS gateway dependency in V1.** SMS requires a regional carrier integration (Tanzania-specific shortcode/aggregator), a data-processing agreement, and PHI-handling controls that are not yet in scope. SMS is acknowledged as a future extension point via `NotificationPort` but no implementation class is written in V1.

## Considered Alternatives

**WebSocket instead of SSE for real-time queues.** Rejected. WebSocket requires a handshake upgrade (`Upgrade: websocket`) that adds complexity to the Spring Security filter chain, requires STOMP broker config or a raw handler, and necessitates a third-party Angular library (`@stomp/ng2-stompjs` or `ngx-socket-io`). For one-directional queue signals, this overhead is unjustified. SSE is simpler and sufficient.

**Polling from the Angular frontend.** Rejected as the primary mechanism. Prior-attempt gap (ADR-NEW-B) confirmed this produces stale queues and unnecessary server load. Polling remains an acceptable fallback for clients that cannot maintain a persistent connection (e.g., a mobile device behind an aggressive proxy), but SSE is the designed path.

**Full message broker (RabbitMQ/Kafka) for outbound notifications.** Rejected for V1. The single-facility, single-JVM deployment profile (ADR-0013) does not justify broker infrastructure. The outbox-pattern with a scheduled poller achieves equivalent reliability without an external dependency. If the deployment profile ever changes to multi-instance, the poller becomes a distributed lock problem — at that point a broker is the right answer and the `NotificationPort` interface isolates the change.

**Activating email in V1 behind the feature flag.** Rejected. With no confirmed SMTP relay, no PHI-reviewed templates, and no process requirement, activating the channel in V1 creates a misconfiguration surface without business value. The flag defaults to `false` and no Spring Mail autoconfiguration is triggered.

## Consequences

**Positive:**
- Real-time queue updates eliminate manual-refresh friction for nurses, reception, and dispensary staff without introducing a broker dependency.
- The outbox table and `NotificationPort` interface provide a wired extension point; activating email in a future sprint requires only an adapter implementation and `hmis.notifications.enabled=true` — no schema change.
- PHI constraint is enforced at the template layer, not as a code review convention, making it auditable.
- Feature flag means the notification infrastructure is testable in CI without a live SMTP or SMS endpoint.

**Negative / risks:**
- In-memory SSE emitter registry is lost on restart; connected Angular clients must reconnect. The Angular `EventSource` API reconnects automatically (default retry), so this is transparent to users.
- The outbox poller adds a background thread. Under high load with the flag disabled this is a no-op; under load with the flag enabled, the poller must be configured with a `ThreadPoolTaskScheduler` that does not starve request threads. Document the thread-pool sizing in `application.yml`.
- Template governance (PHI review, approval chain) must be defined before any template is activated. This is an operational process gap, not a code gap.

## Exact-Process Impact

None. The legacy process contains zero notification steps. No workflow state, no document-numbering rule, no payment gate, and no RBAC permission is affected by this ADR. The SSE queue signals are additive UX improvements; they do not alter when a transition is permitted or who may perform it. The outbox is dormant in V1.

## Implementation Notes

1. **Flyway migration:** Add `VXX__notification_outbox.sql` containing the `notification_outbox` table DDL above. Apply unconditionally. No seed data required.

2. **Spring Modulith placement:** Create a `messaging` module (`com.otapp.hmis.messaging`). Allowed inbound dependencies: any module may publish events consumed by `messaging`. The `messaging` module must not depend on `encounter`, `billing`, or `pharmacy` directly — it listens only to typed `ApplicationEvent` records published via Spring's event bus. This preserves the module boundary graph from ADR-0008.

3. **SSE endpoint:** `GET /messaging/queues/{queueId}/events` (requires authentication; queue subscription is scoped to the authenticated user's roles via a `QueueAccessPolicy` check). Returns `text/event-stream`. Spring `SseEmitter` with a 5-minute timeout; clients reconnect automatically.

4. **`NotificationPort` interface:** Three methods: `send(channel, recipient, templateId, payload)`, `isEnabled()`, `supportedChannels()`. V1 ships one implementation: `NoOpNotificationAdapter`. A future `SmtpNotificationAdapter` implements the same interface and is activated by the feature flag.

5. **Feature flag:** `hmis.notifications.enabled: ${HMIS_NOTIFICATIONS_ENABLED:false}` in `application.yml`. When `false`, the `@Scheduled` poller bean is not registered (conditional on `@ConditionalOnProperty`). The outbox table exists and is writable regardless.

6. **PHI governance checklist** (must be completed before any template is activated in any environment): (a) list all template variables; (b) confirm each variable is a non-PHI reference code; (c) security-architect sign-off; (d) DPO acknowledgement if patient contact details are involved.
