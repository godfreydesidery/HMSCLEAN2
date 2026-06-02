# HMSCLEAN2 — Zana HMIS Modernization

A ground-up **modern rebuild** of the Zana HMIS (hospital management information system) that preserves the **exact legacy business process** while modernizing the architecture, stack, and UX.

> **Principle — "Modern design, exact process":** only the business *process* stays identical (workflow states, validations, document-numbering formats & sequences, pricing/insurance logic, RBAC semantics, report content). Data types and the data model may change (`double → BigDecimal`, etc.). See [`FIRM_CHARTER.md`](FIRM_CHARTER.md).

## Repository layout

| Path | What it is |
|---|---|
| [`FIRM_CHARTER.md`](FIRM_CHARTER.md) | Engagement charter — mission, delivery phases, fidelity rules, standards |
| [`.claude/agents/`](.claude/agents/) | The software-development "firm" — 15 specialized agents (one per SDLC role) |
| [`docs/architecture/overview.md`](docs/architecture/overview.md) | Target architecture overview |
| [`docs/architecture/`](docs/architecture/) | Legacy + prior-attempt discovery findings |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records (ADR-0001 … ADR-0021) |

## Target stack

Java 21 · Spring Boot 3.3 · Spring Modulith (modular monolith) · PostgreSQL 16 · Flyway · Spring Security 6 (OAuth2 + JWT) · springdoc OpenAPI 3 · MapStruct · Testcontainers · Angular 18+ · Docker · GitHub Actions · Terraform.

**Identifiers:** every entity has a hidden internal `id` (`BIGINT GENERATED ALWAYS`) and a public **ULID** `uid` (`CHAR(26)`) exposed in RESTful URLs `/{resources}/uid/{ulid}`.

## Status

**Architecture phase complete** (ADRs proposed, pending ratification). No application code yet — build phase is next.
