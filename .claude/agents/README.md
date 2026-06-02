# Zana HMIS Modernization Firm — Agent Directory

This folder holds the **invocable Claude Code subagents** that make up the Zana HMIS modernization "firm." Each `*.md` file defines one specialist agent — its mandate, operating boundaries, and the model it runs on. Agents are engaged either explicitly (via the Agent tool) or by **auto-delegation** based on their description. For the firm-wide operating model, escalation paths, and shared conventions, see [`../../FIRM_CHARTER.md`](../../FIRM_CHARTER.md).

## Org Chart

```
                        ┌─────────────────────────────┐
                        │   DELIVERY & PRODUCT         │
                        │  engagement-lead (orchestr.) │
                        │  business-analyst (backlog)  │
                        └──────────────┬───────────────┘
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        │                              │                              │
┌───────┴────────┐         ┌──────────┴───────────┐        ┌─────────┴──────────┐
│  DISCOVERY     │         │ ARCHITECTURE & DESIGN│        │   ENGINEERING       │
│ legacy-analyst │         │ solution-architect   │        │ backend-engineer    │
│ healthcare-    │         │ data-architect       │        │ frontend-engineer   │
│   domain-expert│         │ security-architect   │        │ integration-engineer│
└────────────────┘         │ ux-ui-designer       │        │ data-migration-eng. │
                           └──────────────────────┘        │ devops-engineer     │
                                                           └─────────┬───────────┘
                                                                     │
                                                        ┌────────────┴───────────┐
                                                        │      QUALITY            │
                                                        │ qa-test-engineer        │
                                                        │ code-reviewer (tech lead)│
                                                        └─────────────────────────┘
```

## Roster

| Agent (slug) | Role | Engage when... | Model |
|---|---|---|---|
| `engagement-lead` | Engagement Lead / Delivery Manager | Coordinating work, sequencing agents, or you need a single point of accountability. | Opus |
| `business-analyst` | Business Analyst / Product Owner | Capturing requirements, writing user stories, or grooming the product backlog. | Opus |
| `legacy-analyst` | Legacy Systems Analyst (Process Archaeologist) | You need the EXACT legacy behaviour: state machines, validations, numbering, pricing/insurance, billing math, stock/batch rules, report SQL — with file:line citations. | Opus |
| `healthcare-domain-expert` | Healthcare / HMIS Domain Expert | Clinical, hospital-ops, or TZ/East-African regulatory & insurance (e.g. NHIF) questions arise. | Opus |
| `solution-architect` | Solution Architect | Defining overall architecture or ratifying the technology stack. | Opus |
| `data-architect` | Data & Database Architect | Designing the PostgreSQL schema, Flyway baseline, audit model, or the 116-entity legacy mapping. | Opus |
| `security-architect` | Security & Compliance Architect | AuthN/Z, PHI/PII protection, tamper-evident audit, secrets, or threat modeling is in scope. | Opus |
| `ux-ui-designer` | UX/UI Designer (Design System) | Building the design system, navigation, accessibility, or high-density clinical/billing screen IA. | Sonnet |
| `backend-engineer` | Backend Engineer | Implementing Spring Boot 3 / Java 21 modules, JPA, services, DTOs, and REST per spec. | Sonnet |
| `frontend-engineer` | Frontend Engineer | Implementing Angular 18 standalone components, signals, typed forms, guards, and RBAC-aware UI. | Sonnet |
| `integration-engineer` | API & Integrations Engineer | Owning the OpenAPI contract surface or wiring insurance/claims and external integrations. | Sonnet |
| `data-migration-engineer` | Data Migration Engineer (ETL) | Moving 3 years of MySQL production data into the new PostgreSQL schema. | Sonnet |
| `devops-engineer` | DevOps / Platform Engineer | Build/test/deploy pipelines, Docker, GitHub Actions, Terraform, environments, and observability. | Sonnet |
| `qa-test-engineer` | QA / Test Engineer | Defining test strategy, quality gates, or authoring/automating tests. | Sonnet |
| `code-reviewer` | Tech Lead / Code Reviewer | Any change needs review for correctness, exact-process fidelity, security, performance, or maintainability. | Opus |

## Typical Flow

A feature moves through the firm like this:

1. **`legacy-analyst`** extracts the exact legacy rules (with file:line citations); **`healthcare-domain-expert`** confirms clinical/regulatory correctness.
2. **`business-analyst`** turns those rules into requirements and backlog items.
3. **Architects** (`solution-architect`, `data-architect`, `security-architect`) and **`ux-ui-designer`** produce the design and contracts.
4. **Implementers** (`backend-engineer`, `frontend-engineer`, `integration-engineer`, `data-migration-engineer`, `devops-engineer`) build it.
5. **`qa-test-engineer`** validates behaviour and **`code-reviewer`** signs off on every change.

The **`engagement-lead`** orchestrates this end to end, resolving dependencies and owning accountability.

## How to Invoke

Call an agent explicitly with the **Agent tool** (e.g. `engagement-lead`), or let Claude Code **auto-delegate** by matching your task to an agent's description.
