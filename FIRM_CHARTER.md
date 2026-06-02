# Zana HMIS Modernization — Firm Charter

## Mission

We are rebuilding **Zana HMIS** — a ~3-year-old production hospital management information system — from the ground up with a **modern architecture and UX, while preserving the legacy system's business behaviour exactly.** Our governing principle is **"Modern Design, Exact Process."**

> **Client directive (authoritative):** *only the **process** must remain exactly the same; **data types and the data model may change**.* Type/model modernization is pre-approved.

**What is allowed to change:** the architecture (modular monolith, modern frameworks), the data model and its **data types** (e.g. `double` → `BigDecimal` for money), the persistence engine (MySQL → PostgreSQL via Flyway), the security stack, developer experience, performance characteristics, and the user interface. These are not just permitted but expected to improve.

**What is *not* allowed to change without an approved change request:** the **process** — business rules, workflow states and transitions, validations, numbering schemes (formats *and* sequences), pricing and insurance logic, role/privilege access semantics, and the content and structure of the 29 report outputs. These are **observable business behaviours**; they must be **extracted from the legacy code**, never invented, guessed, or "improved" on an engineer's initiative. When behaviour is ambiguous, **the legacy code is the single source of truth.**

**On numeric fidelity:** because data types may change, "exact" means an **identical process producing identical business results** — not bit-for-bit reproduction of legacy floating-point rounding. Adopting `BigDecimal` is expected to *refine* precision; where it differs from legacy `double` only in trailing digits, that is an accepted, documented improvement, not a defect. Parity / golden-master tests assert process and business-result equality on that basis.

Clinical and financial correctness are treated as safety-critical; PHI/PII protection and a tamper-evident audit trail are non-negotiable; and three years of production data must migrate **losslessly**, with mandatory reconciliation.

## How this firm operates

The firm's roster of specialists are **Claude Code subagents** defined under `.claude/agents/`. Each agent has a kebab-slug identity (e.g. `legacy-analyst`, `solution-architect`) and a focused mandate. A human stakeholder, or the **engagement-lead** acting as orchestrator, engages an agent by delegating a scoped task to it; the agent works within its specialty and returns artifacts.

Work flows **phase by phase**. Each phase has a **lead agent** who owns its deliverables and supporting agents who contribute. Artifacts produced in one phase (process specifications, architecture decision records, data-mapping documents, golden-master fixtures) become the **inputs** to the next. The **engagement-lead** sequences the phases, resolves cross-agent dependencies, and enforces exit criteria; the **business-analyst** and **healthcare-domain-expert** keep every increment anchored to real-world clinical and financial meaning; the **code-reviewer** gates merges. Nothing advances to the next phase until the prior phase's exit criteria are met and signed off.

## Delivery phases

**1. Discovery (Process Archaeology)**
- *Goal:* Extract the exact business behaviour from the legacy codebase.
- *Lead:* `legacy-analyst`, with `business-analyst` and `healthcare-domain-expert`.
- *Deliverables:* per-context process specifications, documented workflow states, validations, numbering schemes, pricing/insurance rules, and the catalogue of 29 report outputs.
- *Exit:* every bounded context has a ratified behaviour spec traceable to legacy source.

**2. Architecture & Design**
- *Goal:* Ratify the target architecture and design system.
- *Lead:* `solution-architect`, with `data-architect`, `security-architect`, `ux-ui-designer`.
- *Deliverables:* ratified ADRs, module boundaries for the 14 contexts, OpenAPI contracts (contract-first), target schema, RBAC model, design system.
- *Exit:* architecture and contracts approved; default stack ratified or formally amended.

**3. Data-Migration Design**
- *Goal:* Plan lossless migration of the 116 legacy entities.
- *Lead:* `data-migration-engineer`, with `data-architect`.
- *Deliverables:* Flyway baseline, ETL mapping (legacy → new schema), row-count and financial reconciliation report designs.
- *Exit:* mapping covers 100% of legacy entities; reconciliation strategy approved.

**4. Iterative Build by Bounded Context**
- *Goal:* Implement contexts one at a time, behaviour-faithful.
- *Lead:* `backend-engineer` and `frontend-engineer`, with `integration-engineer`.
- *Deliverables:* working modules, typed APIs, UI, unit/integration tests per increment.
- *Exit:* each context passes parity tests and code review against its Discovery spec.

**5. Hardening & Parity Testing**
- *Goal:* Prove behavioural equivalence and production readiness.
- *Lead:* `qa-test-engineer`, with `security-architect`, `devops-engineer`.
- *Deliverables:* golden-master/parity test suite, security review, performance and observability validation.
- *Exit:* all parity tests green; reconciliation passes; security sign-off.

**6. Cutover**
- *Goal:* Migrate production data and go live safely.
- *Lead:* `engagement-lead`, with `data-migration-engineer`, `devops-engineer`.
- *Deliverables:* executed migration, reconciliation reports, staged rollout with feature flags, rollback plan.
- *Exit:* reconciled production data; stakeholder acceptance; rollback rehearsed.

## Recommended target architecture

**This is the default working target, to be ratified by `solution-architect` in the Architecture phase** — an evolutionary path that retains the team's Spring + Angular skills.

- **Backend:** Java 21, Spring Boot 3.3, **Spring Modulith** (modular monolith), Spring Data JPA + Hibernate 6, Flyway, **PostgreSQL 16** (migrated from MySQL), Spring Security 6 (OAuth2 Resource Server + JWT), springdoc-openapi (OpenAPI 3, contract-first), MapStruct, Bean Validation, explicit append-only audit (a new capability — legacy Envers is declared but annotates zero entities), JUnit 5 + Testcontainers.
- **Frontend:** Angular 18+ (standalone components, signals, `@if`/`@for`, typed reactive forms), Angular Material 3 + CDK, TypeScript strict, ESLint + Prettier, Jest/Vitest + Playwright, documented design system.
- **Cross-cutting:** legacy-preserving RBAC, structured logging + Actuator/Micrometer/OpenTelemetry, Docker Compose (dev), GitHub Actions CI/CD, Terraform IaC, staged environments, feature flags.

**The 14 bounded contexts:** (1) Registration & Patient, (2) Clinical/OPD, (3) Inpatient/Nursing, (4) Pharmacy, (5) Inventory/Procurement, (6) Laboratory, (7) Radiology, (8) Procedures/Theatre, (9) Billing & Cashiering, (10) Insurance/Claims, (11) HR/Payroll, (12) Assets, (13) Identity & Access, (14) Reporting.

## Exact-process fidelity rules

- **Source-of-truth rule:** when behaviour is unclear, the legacy code decides. No business rule may be authored from assumption.
- **Change-request control:** any deviation from observed legacy behaviour requires an explicit, written, approved change request before implementation. Unapproved "improvements" to process are defects.
- **Golden-master / parity testing:** for each context we capture legacy inputs and outputs (especially numbering, pricing/insurance calculations, and the 29 reports) as **golden masters**, and assert the new system reproduces the **process and business results**. Document numbering (formats and sequences) and report content/structure must match exactly; numeric values must match at the business level, with trailing-digit differences from the pre-approved `double` → `BigDecimal` change accepted and documented (see *On numeric fidelity* above). Parity is a merge gate.
- **Verify-before-reproduce (no phantom features):** a feature counts as "legacy behaviour to preserve" only once `legacy-analyst` confirms it actually runs in the legacy code — a bundled dependency or import is *not* proof. Two confirmed traps in this codebase: the **Hibernate Envers** dependency is declared but annotates **zero** entities (no audit history exists to migrate or reproduce), and the **browser-fingerprint** libraries are present in the Angular `package.json` but no device-binding logic is wired up. Treat both as *absent* until proven present; building them anyway would itself violate exact-process by inventing a rule that never existed.

## Engineering standards

- **Security & PHI:** least-privilege RBAC mirroring legacy roles/privileges, encrypted PHI/PII in transit and at rest, secrets management. (Device-binding is **not** a confirmed legacy feature — see the verify-before-reproduce rule — so it is out of scope unless `legacy-analyst` confirms it.) Security review precedes cutover.
- **Audit:** explicit append-only, tamper-evident audit trail covering every clinical and financial mutation — a **new** capability, since the legacy Envers dependency annotates zero entities and leaves no audit history to port.
- **Testing:** unit + integration (Testcontainers) + parity + Playwright e2e; no increment ships without coverage of its business rules.
- **Code review:** `code-reviewer` gates every merge for correctness, fidelity-to-spec, and standards.
- **Observability:** structured logs, metrics, and traces (Actuator/Micrometer/OpenTelemetry) wired from day one.

## Definition of done

**Per increment:** behaviour matches its Discovery spec; parity/golden-master tests pass; unit and integration tests green; PHI and RBAC enforced; audit events emitted; reviewed and merged; OpenAPI contract updated.

**For the engagement:** all 14 contexts delivered at behavioural parity; three years of data migrated and reconciled (row-count and financial); security sign-off obtained; observability live; staged cutover completed with a rehearsed rollback; and stakeholder acceptance recorded.
