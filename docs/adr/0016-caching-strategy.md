# ADR-0016: Caching Strategy

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

## Context

The legacy `ReportResource.java` (1,748 lines, 36+ endpoints, 30+ injected repositories) performs all report aggregation as in-process nested-loop Java joins — O(n×m) over full entity graphs. The five heaviest paths (collections report, clinician performance, fast/slow-moving stock, IPD summary, pharmacy sales) scan all bills, all consultations, or all stock cards for a date range with no query-layer aggregation. These are the primary read-pressure points. A secondary pressure point exists on every transactional path that calls `PriceLookup.resolve()`: the prior build's `ServicePriceRepository.findCell()` fires up to three separate point-lookup queries per order line (target currency, default currency, any currency fallback) and `CurrencyService.defaultCode()` is called on every invocation — all to read data that changes at most a few times per year. The legacy has no caching whatsoever.

The system is a single-facility modular monolith (ADR-0001). Horizontal scaling by running multiple stateless instances behind a load balancer is the stated deploy model (ADR-0013). The initial deployment is a single instance. Multi-instance becomes relevant only when the hospital operates at scale.

Two invariants must hold regardless of caching:

1. **Exact-process fidelity:** cached data must never cause the system to apply a stale price, stale coverage flag, or stale privilege check in a financial or access-control decision. Caching is a read-path optimisation only.
2. **Financial correctness:** live balances, stock quantities, and settlement flags are never served from cache. Those values are the source of truth for payment gates (confirmed open gaps DIAG-2 / PHARM-2 in the prior build) and must always reflect the current database state.

## Decision

**Spring Cache abstraction backed by Caffeine (in-process) as the sole caching layer. Redis is deferred, not pre-provisioned.**

### 1. Application-level cache: Spring Cache + Caffeine

Enable `@EnableCaching` on the application. Declare Caffeine as the `CacheManager` provider. Define named caches with explicit TTL and maximum-size bounds in `application.yml` under `spring.cache.caffeine.spec` (per-cache spec via `CaffeineCacheManager.setCacheSpecification`).

Caffeine is chosen over the Spring default (ConcurrentHashMap) because it provides bounded heap consumption (maximum entry count), time-based expiry (`expireAfterWrite`), and eviction statistics exposed via Actuator — all required for a production service. No Redis dependency is introduced at this stage: the modular monolith runs as one JVM, so an in-process L1 cache eliminates every network round-trip that Redis would still incur.

### 2. What is safe to cache

The following data has low mutation rate, high read frequency, and no financial-correctness risk when slightly stale:

| Cache name | Content | Max entries | TTL |
|---|---|---|---|
| `reference-data` | Clinics, wards, pharmacies, stores, lab test types, radiology types, procedure types, medicine list, dosages, routes, frequencies | 2,000 | 30 min |
| `insurance-plans` | Insurance plans and providers | 500 | 30 min |
| `service-prices` | `ServicePrice` rows by `(planUid, kind, serviceUid, currency)` — the exact `PriceLookup.findCell` key | 10,000 | 30 min |
| `currency-default` | The single default currency code from `CurrencyService.defaultCode()` | 1 | 60 min |
| `privilege-lookup` | The resolved privilege set for a given `Role` set (keyed by sorted role-uid list) — used when assembling the JWT `privileges` claim at login, not on every request | 200 | 15 min |
| `company-profile` | Single `CompanyProfile` row (header on all documents) | 1 | 60 min |
| `analyte-ranges` | Lab reference ranges per `(analyteUid, sex, ageBand)` | 5,000 | 60 min |

The `PriceLookup` component is the highest-frequency cache consumer. Apply `@Cacheable("service-prices")` on the `resolve()` method; `@CacheEvict(value = "service-prices", allEntries = true)` on `ServicePriceService.create()`, `update()`, and `delete()`. The same evict-on-mutation pattern applies to all caches above.

### 3. What must never be cached

The following are explicitly excluded from all caches:

- **Financial balances** (`PatientBill.balance`, `Invoice` outstanding, `StockBalance.quantity`, `StockBatch.remainingQuantity`): these are the source of truth for payment gates and stock dispensing. Serving a stale balance would silently allow an unpaid CASH patient to advance through a service gate — the exact defect pattern confirmed in the prior build (DIAG-2 / PHARM-2).
- **Settlement / settled flags** (`ClinicalOrder.settled`, `Admission.billsCleared`, `Consultation.feeSettled`): these are written by the billing-side `SettlementDispatcher` and read as hard gates on state transitions. Always read from the database.
- **Worklist queries** (reception queue, nurse queue, dispense worklist, closure worklist): these change on every state transition. Caching them would produce stale queues for concurrent users. Polling these endpoints is cheap — they are indexed queries on status columns.
- **Prescription and order aggregates** in IN_PROGRESS or PENDING state: any mutable aggregate being actively edited must never be served from cache.
- **Audit trail reads**: by definition always fresh.
- **Report data**: the reporting module (ADR-0010) serves data assembled by JOOQ queries against the live schema. Report correctness requires current data; caching report output would silently return stale totals. Report query performance is addressed by database-level indexes and query design, not by application-level caching.

### 4. Explicit invalidation on mutation

All `@CacheEvict` annotations are placed on service-layer methods (never on controllers or repositories), consistent with the Spring Modulith boundary rule that caches are module-internal concerns. The eviction strategy is `allEntries = true` for all reference-data caches because the entry count is small and partial eviction by key is error-prone when keys involve multi-part compound lookups.

An `@TransactionalEventListener(phase = AFTER_COMMIT)` is the correct hook for cache eviction triggered by cross-module mutations: the cache is only cleared after the transaction commits, preventing a race where a concurrent read repopulates the cache with pre-commit state.

### 5. HTTP caching and ETags for GET endpoints

Reference-data GET endpoints (`/masterdata/**`, `/iam/roles`, `/iam/privileges`) must include `ETag` and `Cache-Control: max-age=300, must-revalidate` response headers. Spring's `ShallowEtagHeaderFilter` generates ETags from response body hashes at zero application-code cost; declare it as a `FilterRegistrationBean` scoped to `/masterdata/**`.

Transactional resource GET endpoints (`/encounters/**`, `/billing/**`, `/pharmacy/**`) must respond with `Cache-Control: no-store`. Financial and clinical data must never be cached by a browser or intermediate proxy.

### 6. Redis deferral condition

Redis (Spring Cache with `spring-boot-starter-data-redis`) is introduced only when the deployment runs more than one JVM instance. At that point the in-process Caffeine caches become stale-per-node (each instance may see a different view of `service-prices` after a mutation lands on instance A). The switch requires: adding the Redis starter, declaring a `RedisCacheManager` bean with the same named-cache TTLs, and ensuring all `@CacheEvict` paths broadcast to Redis. Because all cache interaction is behind the Spring Cache abstraction (`@Cacheable` / `@CacheEvict`), the swap is a configuration change in `CacheConfig.java` — no service-layer changes. Flag in `application.yml`: `hmis.cache.backend=caffeine` (default) or `redis`.

## Considered Alternatives

| # | Alternative | Decision | Reason |
|---|---|---|---|
| A | No application cache; rely solely on PostgreSQL's shared buffer | Rejected | `PriceLookup.resolve()` fires 1–3 queries per order line; at 50 concurrent cashiers / prescribers the cumulative DB load on a point-lookup table with 10,000 rows is unnecessary |
| B | Redis from day one | Rejected | Single-instance initial deploy makes Redis a pure ops overhead with no correctness benefit; adds a required infrastructure component to every developer workstation |
| C | Hibernate 2nd-level cache (Ehcache / Infinispan) | Rejected | 2nd-level cache operates below the service boundary and makes cache eviction from mutation events ambiguous; harder to audit and test; Spring Cache at the service method level is explicit and testable |
| D | Materialized views for report queries | Deferred | Correct long-term approach for report performance; requires a refresh strategy (scheduled or event-driven) and adds operational complexity; JOOQ query + proper indexes is the first step |
| E | Cache report output as a named Spring Cache entry | Rejected | Violates the exact-process rule: a cached report served after a cashier posts a payment would show stale totals, breaking the collections-report parity test |

## Consequences

**Positive:**
- `PriceLookup.resolve()` reduces from 1–3 database round-trips per call to a single heap lookup after warm-up; this directly reduces latency on registration, consultation-fee invoicing, and order-charge paths.
- `CurrencyService.defaultCode()` (called on every `PriceLookup` invocation) drops to a heap read.
- Reference-data picklist endpoints (clinic list, ward list, medicine search) are served from heap without touching PostgreSQL — important for the Angular autocomplete components that fire on every keystroke.
- The `no-store` / ETag contract on transactional endpoints eliminates any risk of a browser serving a stale balance or stale queue to a concurrent user.
- All caches are bounded (Caffeine max-size); heap growth is predictable and configurable without a restart.
- Actuator's `/actuator/caches` endpoint (auto-exposed when Spring Cache is on the classpath) gives ops visibility into hit/miss rates and entry counts.

**Negative / Risks:**

| Risk | Mitigation |
|---|---|
| Stale service-price served after admin updates pricing mid-day | TTL is 30 min maximum; `@CacheEvict(allEntries=true)` on every `ServicePriceService` mutation fires immediately after commit. Worst-case stale window is the time between the AFTER_COMMIT eviction and the next cache population — sub-second in a single JVM |
| Developer writes a new service method that calls `ServicePriceRepository` directly, bypassing `PriceLookup` | Module boundary rule: `ServicePriceRepository` is package-private within `masterdata.pricing`; external callers must go through `PriceLookup` or `ServicePriceService`. Spring Modulith structure test enforces this |
| Multi-instance deployment silently misses Redis migration | `hmis.cache.backend` property is a required config key with no default in production profiles; startup fails with a `BeanCreationException` if neither `caffeine` nor `redis` is specified |

## Exact-Process Impact

No business process is affected by this decision. Caching is applied exclusively to read paths that have no state-transition side effects. The following confirmed process gates remain database-reads:

- `ClinicalOrder.settled` checked before `accept()`, `complete()` (lab / radiology / procedure) — never cached (prior build gap DIAG-2 / PHARM-2).
- `Consultation.feeSettled` checked before the reception queue serves a consultation — never cached (prior build gap M2).
- `DischargePlan.status` checked before `AdmissionService.discharge()` — never cached.
- `PatientBill.balance` checked by cashiering endpoints — never cached.

## Implementation Notes

- **Caffeine dependency:** `com.github.ben-manes.caffeine:caffeine` (pulled transitively by `spring-boot-starter-cache`). No explicit version pin needed; Spring Boot 3.3 manages it.
- **Config bean:** `CacheConfig.java` in the `platform` module (the cross-cutting platform layer defined in ADR-0008). Declares a `CaffeineCacheManager` with per-cache specs loaded from `application.yml`. Do not use `@EnableCaching` on individual module configs — one centrally declared `CacheManager` bean is sufficient and avoids duplicate registrations.
- **Cache names as constants:** define a `CacheNames` class in the shared kernel with `public static final String SERVICE_PRICES = "service-prices"` etc. Reference only the constant in `@Cacheable` / `@CacheEvict` annotations — prevents typo-driven cache misses.
- **`ShallowEtagHeaderFilter`:** declare as a `FilterRegistrationBean<ShallowEtagHeaderFilter>` with URL pattern `/masterdata/*`. Do not apply to `/reports/*` (report output must not be ETagged — the same URL with the same filter body but a different date range would collide).
- **Test requirement:** each cached service method must have a unit test asserting (a) the cached result matches the database result, and (b) after `@CacheEvict`, the next call returns a freshly loaded value. Use a `SpyCacheManager` or `CacheManager.getCache(name).invalidate()` in the test harness; do not rely on TTL expiry in unit tests.
- **Monitoring:** expose `management.endpoints.web.exposure.include=caches,health,metrics` in all non-production profiles. Production exposure is governed by ADR-0012 (Actuator security).
- **Redis migration checklist (when triggered):** add `spring-boot-starter-data-redis`, replace `CaffeineCacheManager` with `RedisCacheManager` in `CacheConfig.java`, set `hmis.cache.backend=redis`, configure `spring.data.redis.host/port/password` via environment variable, add Redis readiness probe to the container health check (ADR-0013).
