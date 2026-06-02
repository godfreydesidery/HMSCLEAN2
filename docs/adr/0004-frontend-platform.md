# ADR-0004: Frontend platform — Angular 18+

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

## Context

The legacy system has a parallel Angular 16 frontend (`hmis-engine-web`) that drives ~12 role-specific worklists, high-density clinical/billing/pharmacy task flows, registration, inpatient management, reporting, and payroll. The prior modernization attempt exposed a coherent set of frontend gaps: missing payment-gate enforcement in the UI (DIAG-2/PHARM-2), absent POS receipt and closure-document printing (BILL-1, DISCH-2), no closure-plan worklist (DISCH-1), no select-working-pharmacy session scope (PHARM-1), and RBAC guards that relied on brittle error-message string matching instead of structured error codes. The team has existing Angular skills and the legacy frontend, while not binding us to its technical choices, provides an accurate model of the task-flow complexity to reproduce. The API contract will be generated from the Spring Boot / springdoc OpenAPI 3 contract and will surface only `uid` (ULID) in URLs and payloads, never internal `id`.

## Decision

**Angular 18+ with standalone components, signals, typed reactive forms, and the new `@if`/`@for` control flow is the chosen frontend platform.**

Key decisions within this choice:

1. **Angular Material 3 as the component library.** The ux-ui-designer owns the full design-system detail (tokens, typography, density overrides for clinical tables), but the engineering baseline is Angular Material 3. High-density clinical views (worklists, lab result tables, inpatient MAR) require the `density: -2` compact mode and must be validated against real data volumes before release.

2. **Signals + component store for state.** Angular 18 signals replace NgRx for per-feature state (consultation queue, pharmacy session, cashier session). A lightweight `SignalStore` (NgRx SignalStore or a thin hand-rolled equivalent) replaces `BehaviorSubject` chains. Global authentication state (JWT claims, decoded privileges array) lives in a singleton `AuthStore` signal. No full Redux/NgRx is introduced unless a specific cross-cutting synchronisation need is identified.

3. **Generated typed API client from the OpenAPI contract.** The backend publishes an OpenAPI 3 spec. The frontend uses `openapi-generator-cli` (Angular/TypeScript template) at build time to produce a strictly-typed client. All URLs are `/{resources}/uid/{resourceUid}` — the generator is configured to enforce this. Internal `id` never appears in any generated model. The generator runs as a pre-build step and its output is committed to `src/app/api/generated/` (not `.gitignore`d) so the build is reproducible without a running backend.

4. **RBAC-aware UI driven by the `privileges` claim.** The JWT carries a `privileges` string array (177 codes, confirmed from legacy). The `AuthStore` exposes a `hasPrivilege(code: string): Signal<boolean>` helper. Structural directives (`*appCan='CODE'`) and route guards (`PrivilegeGuard`) are driven solely by this signal — no role-name checks, no string-matching hacks. This directly fixes the prior-build failure (FRONTEND_GAPS B5) where the UI pattern-matched on error message text to detect fee-gate rejections. The backend will return structured `ProblemDetail` with a typed `ErrorCode` in the `type` URI; the frontend catches `HttpErrorResponse` and dispatches on `error.extensions.errorCode`, never on `error.message`.

5. **Pharmacy session scope.** A `PharmacySessionStore` (singleton signal) holds the currently selected pharmacy `uid`. All dispensing calls, stock-balance queries, and worklist fetches inject this `uid` as a required query parameter. The session is persisted to `sessionStorage` so a page refresh does not lose the selection. This closes PHARM-1 from the prior build.

6. **Real-time queue updates via Server-Sent Events.** Each role-queue component (reception, nurse, lab, pharmacy dispense, cashier) subscribes to an SSE endpoint (`/events/queue/{queueKind}`) on mount and unsubscribes on destroy. Angular's `EventSource` wrapper is encapsulated in a `QueueEventService`. This replaces the timed-polling pattern from the prior build. If the SSE connection drops, the service falls back to a 30-second poll with exponential back-off. (Depends on ADR-NEW-B decision; this ADR commits the frontend contract; the backend SSE vs. WebSocket choice is owned by ADR-NEW-B.)

7. **Client-side PDF/Excel exports using browser-print CSS and `@angular/cdk` for receipts; server-generated PDF for archivable documents.** POS receipts and patient invoices (BILL-1, BILL-4) are rendered as hidden `@media print` components and triggered via `window.print()` — fast, no server round-trip. Discharge summaries, referral letters, and death records (DISCH-2) are fetched as binary blobs from a server-side PDF endpoint and opened in a new tab. Excel exports (collections report, revenue breakdown) use `SheetJS` (community edition) client-side. This hybrid approach closes the HIGH-severity print gaps without imposing a JasperReports dependency on the backend critical path.

8. **Typed reactive forms for all clinical/billing task flows.** `FormBuilder` with `NonNullableFormBuilder` everywhere. No template-driven forms. Form models mirror the DTO shapes produced by the OpenAPI generator. Consultation booking, prescription entry, and invoice creation forms carry inline validation derived from the API's Bean Validation constraints (min/max, pattern) embedded in the OpenAPI schema and surfaced as `Validators` by the generator.

## Considered alternatives

**React/Next.js rewrite.** Would require rebuilding the ~12 role-queue flows, high-density clinical grids, and RBAC guard infrastructure from scratch with no transferable code from the Angular 16 legacy. Team Angular skills reduce ramp-up risk. The exact task-flow preservation requirement (M1–M25) favours the lower-risk Angular continuity. React was not ruled out on technical merit but on project-risk and skills grounds. If the ux-ui-designer team is React-native, this can be re-evaluated at sprint zero — but the decision must be made before any scaffolding begins.

**Vue 3 / Nuxt.** No existing team skills. Rejected.

**Continuing with Angular 16 (no upgrade).** Angular 16 lacks signals and the new control flow. Standalone components are opt-in only. The typed-form and signal patterns the fresh build requires would require polyfills or workarounds. Rejected.

## Consequences

**Positive:**
- Signals eliminate `ChangeDetectionStrategy.OnPush` boilerplate while retaining fine-grained reactivity for live queues.
- Generated API client eliminates hand-written HTTP service duplication (the prior build had ~40 hand-coded service files, each with subtle URL inconsistencies).
- Structured `ErrorCode` dispatch replaces brittle error-message matching.
- `PharmacySessionStore` closes PHARM-1 from day one.
- SSE subscription closes the real-time gap identified in ADR-NEW-B.

**Negative / risks:**
- Angular Material 3 theming API changed significantly from M2; existing component customizations from the Angular 16 legacy cannot be ported directly — the ux-ui-designer must re-author all density/color tokens.
- `openapi-generator-cli` output quality depends on the OpenAPI spec quality; under-specified discriminators or missing `required` fields produce `any`-typed models. The backend team must annotate all DTO schemas with `@Schema(required = true)` and explicit `nullable = false`.
- SSE with a Spring Boot backend requires `SseEmitter` management; the backend must handle client-disconnect cleanup to avoid emitter leaks. This is an implementation risk to flag to the backend architect.

## Exact-process impact

The following confirmed process behaviours must be faithfully reproduced in the frontend; they are not design-time options:

- **SendToDoctor is one atomic action, not two screens.** The receptionist UI must POST a single endpoint that creates the consultation and invoice together. No intermediate "create consultation" and "create fee" steps visible to the user.
- **Payment gates are UI hard-stops, not hidden items.** For CASH outpatient and outsider patients, the lab/radiology/procedure worklist must disable the "Accept" and "Complete" actions (not hide the row) until the `settled` flag is `true`. The frontend reads the `settled` boolean returned in the order DTO and locks the action button with a tooltip explaining the gate. The same gate applies to pharmacy dispense (`markSold`) and discharge plan approval.
- **Consultation transfer is two-phase.** The doctor's UI has a "Transfer Patient" action that submits a pending-transfer request (no clinician chosen). A distinct "Pending Transfers" queue is visible to reception, where the receiving clinician is chosen and the new consultation is booked. The doctor's queue shows a "Cancel Transfer" button on the original consultation while its status is TRANSFERRED.
- **Discharge requires a second approver.** The closure-plan approval UI must check `plan.authorUsername !== currentUser.username` client-side (and the backend enforces it server-side) before enabling the "Approve Plan" button. A solo-clinician config override is a backend flag surfaced in the system-settings API; the UI reads it and relaxes the guard accordingly.
- **Ward-day charges are backend-scheduled.** The frontend does not compute or display accruing ward-day charges speculatively; it reads the billed line items from the invoice endpoint. The UI must not show a "days × rate" preview unless the backend explicitly provides a computed estimate endpoint.
- **Prescribing alerts are advisory, not blocking.** The prescription entry form calls a `/encounters/prescriptions/alerts` endpoint after the medicine is chosen. If `duplicateMedicine` or `unfinishedCourse` flags are true, a non-modal inline warning banner is shown. The form remains submittable.

## Implementation notes

- Scaffold: `ng new hmis-web --standalone --routing --style=scss`. No NgModules for feature code; `AppModule` only for bootstrapping.
- Lazy-loaded feature routes per bounded context: `registration`, `outpatient`, `inpatient`, `pharmacy`, `laboratory`, `radiology`, `procedures`, `billing`, `inventory`, `payroll`, `masterdata`, `reports`, `iam`.
- The OpenAPI generator runs via `npm run generate:api` (wraps `openapi-generator-cli generate -i http://localhost:8080/v3/api-docs -g typescript-angular -o src/app/api/generated`). Pin the generator version in `package.json`.
- `AuthInterceptor` attaches `Authorization: Bearer <token>` from `AuthStore`. A `RefreshInterceptor` catches 401, calls the refresh-token endpoint once, retries, then routes to `/login` on second failure.
- `PrivilegeGuard` implements `CanActivate` and `CanActivateChild`; routes declare `data: { privilege: 'CODE' }`.
- The `PharmacySessionStore` must be initialized (pharmacy selected) before any dispensing route activates; a route guard redirects to a pharmacy-selector screen if `pharmacyUid` is null.
- Client-side PDF print components live in `src/app/print/`; they import zero Angular Material (raw HTML + print CSS only) to avoid CDK style bleed under `@media print`.
- `SheetJS` is a dev/runtime dependency only for the reports module; it must be lazy-loaded via dynamic `import()` to keep the main bundle below 500 kB initial load.
- All `Signal<T>` state is typed against the generated DTO types, never `any`. ESLint rule `@typescript-eslint/no-explicit-any` is enabled as an error.
