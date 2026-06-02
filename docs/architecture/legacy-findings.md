# Legacy Architecture Findings (Architecture-phase grounding)

## Confirmed

**MONEY/DECIMAL — double throughout, BigDecimal absent.**
Every monetary, quantity, stock, and balance field in every domain entity is Java primitive `double`. Verified across 52 entity files (96 matching `private double` declarations in `/domain/*.java`). A targeted grep for `BigDecimal` across the entire codebase returns **zero matches**. Affected entities include `PatientBill` (qty, amount, paid, balance — all double), `Prescription` (qty, issued, balance), `GoodsReceivedNoteDetail` (orderedQty, receivedQty, price), `StoreItem` (stock, multiple qty/price fields), `PayrollDetail` (6 double fields), `PharmacyStockCard`, `PharmacySaleOrderDetail`, `Purchase`, `PatientInvoice`, `PatientInvoiceDetail`, and every insurance-plan price table. The CLIENT MANDATE pre-approval to replace with `BigDecimal` stands; there is nothing in the code to block it.

**TRANSACTIONS — 114 files carry `@Transactional`.**
Both service impls and resource (controller) classes are transactional. The `@Transactional` in `PatientServiceImpl` covers creation of Patient + PatientBill + Registration + Visit in one transaction, all touching different conceptual domains (Registration, Billing, Clinical). `GoodsReceivedNoteServiceImpl.approve()` updates StoreItem stock, writes StoreStockCard, creates StoreItemBatch records, creates Purchase records, and updates LocalPurchaseOrder status — all in one transaction spanning Inventory, Procurement, and Purchasing domains. This is the dominant cross-domain coupling pattern.

**PERSISTENCE/DB — MySQL 5, Hibernate ddl-auto=update, no Flyway.**
Confirmed in `application.properties` (line 11: `MySQL5InnoDBDialect`; line 14: `ddl-auto = update`). DB name `zana_hmis_db_test`, port 3306. jwt.secret is the literal string `"<REDACTED>"` (hardcoded in both filter classes — a security finding, see below). No Flyway or Liquibase on the classpath.

**IDENTITY/RBAC — User → Role → Privilege, stateless JWT, 177 @PreAuthorize annotations across 45 resource files.**
Confirmed exactly. `CustomAuthorizationFilter` reads `decodedJWT.getClaim("privileges").asArray(String.class)` and constructs Spring `SimpleGrantedAuthority` objects, confirming the claim name is `privileges` (not `roles`). `CustomAuthenticationFilter` emits tokens with an 8-hour access expiry and 24-hour refresh expiry. The `Privilege` entity stores only `name` (the code string) and `id`. The `Role` entity holds a `@ManyToMany` collection of `Privilege`. `User` holds a `@ManyToMany` collection of `Role`.

**AUDIT — Hibernate Envers declared, zero `@Audited` annotations.**
Grep for `@Audited`, `@AuditTable`, `EntityAudit` returns zero matches across all Java files. Audit trail is fully net-new.

**REPORTING — Mix of entity returns and projection interfaces.**
`ReportResource.java` (1,748 lines) is a single mega-controller housing 36+ report endpoints, all under `/zana-hmis-api`. Reports return: raw domain entity lists (List<Consultation>, List<LabTest>, List<GoodsReceivedNoteDetail>), hand-mapped DTO lists (PrescriptionModel, LabTestModel, StoreStockCardModel), native-query projection interfaces (CollectionReport, ClinicianPerformanceReport), and local anonymous `@Data` inner classes (RevenueReport, IPDReport, PharmacySalesReport) defined at the bottom of the same file. These inner classes are not serializable to a stable API contract without a rewrite.

**DOCUMENT NUMBERING — confirmed pattern and race condition.**
Confirmed. All sequence-bearing document numbers use the same two-step, non-atomic pattern:
1. `repository.getLastId()` — a JPQL `SELECT MAX(entity.id)` query (not a database sequence).
2. Increment by 1 and format.

Number formats confirmed by reading each `requestXxxNo()` method:
- GRN: `GRN{yyyyMMdd}-{id}` (e.g., `GRN20260602-47`) — `Formater.formatWithCurrentDate("GRN", id)`
- LPO: `LPO{yyyyMMdd}-{id}`
- Patient Credit Note: `PCN{yyyyMMdd}-{id}`
- Payroll: `PRL{yyyyMMdd}-{id}`
- Pharmacy-to-Pharmacy RN: `PPRN{yyyyMMdd}-{id}`
- Pharmacy-to-Store RO: `PSR{yyyyMMdd}-{id}`
- Pharmacy-to-Pharmacy RO: `PPR{yyyyMMdd}-{id}`
- Pharmacy-to-Pharmacy TO: `SPT{yyyyMMdd}-{id}` (StoreToPharmacy TO uses the same prefix — confirmed collision)
- Store-to-Pharmacy RN: `PGRN{yyyyMMdd}-{id}`
- User code: `USR-{000000}` (formatSix — zero-padded, hyphen-separated, no date)
- Patient MR number: `MRNO/{year}/{rawId}` (e.g., `MRNO/2026/42`) — generated in `PatientServiceImpl` line 250 using the DB-assigned identity PK directly, not a separate counter

`Sanitizer.sanitizeString` only replaces `+` with space and collapses whitespace; it does not affect the formatted number strings above (those contain no spaces or `+`). Its real use is for patient search key construction.

**DEVICE BINDING — absent from backend.**
Confirmed. `CustomAuthorizationFilter` and `CustomAuthenticationFilter` contain no fingerprint or device-binding logic. The partial token-persistence check (`_user.setAuthorizationToken(...)`) is commented out in both filter files (lines 82–90, 95–97 respectively).

**API STYLE — almost exclusively GET + POST; no PUT/DELETE/PATCH except in UserResource.**
575 HTTP method annotations across 48 files. `@PutMapping` and `@DeleteMapping` appear only in `UserResource.java` (4 annotations: PUT /users/update, DELETE /users/delete, PUT /roles/update, DELETE /roles/delete). Every other resource uses `@GetMapping` (read) and `@PostMapping` (all mutations including creates, updates, approvals, state transitions, deletes). The pattern `/goods_received_notes/create`, `/goods_received_notes/approve`, `/goods_received_notes/verify_detail_qty` etc. is the universal convention.

**HTTP STATUS CODES — misused pervasively.**
`ResponseEntity.created(uri).body(...)` (HTTP 201) is used for **GET** responses, read queries, and search operations — not just creates. This is confirmed in `GoodsReceivedNoteResource.getVisibleGoodsReceivedNotes()` (a GET that returns 201) and throughout ReportResource (all POST report queries return via `ResponseEntity.ok()` but many resource methods use `ResponseEntity.created()`). 266 uses of `ResponseEntity.created()` across 43 files.

**CODE LAYOUT — confirmed package-by-layer.**
Packages: `domain`, `repositories`, `service`, `api`, `reports/service`, `reports/models`, `filter`, `security`, `accessories`, `models`, `exceptions`. There are also `controllers/service` (a vestigial `UserServiceController.java`) and root-level utility classes (`Formater`, `Validator`, `UpdatePatient`). No package-by-feature structure anywhere.

**PRIVILEGE COUNT — 177 annotations, 45 files confirmed precisely.**

---

## Corrected / Refined

**TRANSACTIONS count: 114, not 110.**
The grep across all Java files for `@Transactional` returns 114 distinct file-level hits (from 110 files, since a few files have it multiple times at both class and method level, e.g., `UserServiceController` has 5). The grounded fact says "110 files" — the real unique count across the package is 110 files carrying the annotation.

**REPORTING: not "29 report services" — 3 report service files, 1 mega-controller, report models are projection interfaces.**
The `reports/service` directory has only 4 files: `InventoryReportService`, `InventoryReportServiceImpl`, `LocalPurchaseOrderReportService`, and `package-info`. The `reports/models` directory holds 21 projection interfaces (Java interfaces with getter-only methods backed by Spring Data JPA projections). The "29 report services" figure likely counts report endpoints in `ReportResource`, which has 36+ distinct `@PostMapping` methods. There is no separate report service per report — all report logic is embedded in `ReportResource` directly using repositories.

**NATIVE QUERIES: 5 repositories with nativeQuery=true is accurate but the two in `ConsultationRepository` are commented out.**
Active native queries are in: `CollectionRepository` (2 active), `ClinicianPerformanceRepository` (2 active), `LabTestRepository` (1 active), `PrescriptionRepository` (2 active — fast/slow moving drugs). The two in `ConsultationRepository` (lines 130–161) are commented out. Total active: approximately 7 native queries in 4 repositories.

**REPORT RETURN TYPES: not uniform DTOs — reports return domain entities directly in many cases.**
Several report endpoints return `List<Consultation>`, `List<LabTest>`, `List<Radiology>`, `List<Procedure>` — bare domain entities with all their JPA-loaded associations, including nested lazy/eager collections, which will serialize the full object graph to the client. This is a significant serialization hazard (circular references handled by `@JsonIgnoreProperties`, but still overloading).

**JWT secret is hardcoded as `"<REDACTED>"` in the filter, not from `jwt.secret` property.**
`application.properties` line 26 sets `jwt.secret=<REDACTED>`, but `CustomAuthorizationFilter.java` line 73 uses `Algorithm.HMAC256("<REDACTED>".getBytes())` literally. `CustomAuthenticationFilter.java` line 76 also uses `Algorithm.HMAC256("<REDACTED>".getBytes())`. The property value `<REDACTED>` is **never read** by either filter — the actual operative secret is the hardcoded string `"<REDACTED>"`. This is both a security defect and a fact relevant to migration: the secret to reproduce is `"<REDACTED>"`, not `"<REDACTED>"`.

**DayService.getTimeStamp() adds 3 hours — hardcoded UTC+3 offset.**
`DayServiceImpl.getTimeStamp()` returns `LocalDateTime.now().plusHours(3)` (line 87). All `createdAt`, `approvedAt`, `verifiedAt` etc. timestamps are stored in this UTC+3-adjusted wall-clock time. Migration must account for this offset to avoid silently shifting all timestamps.

**`Day` entity is a business-date tracking mechanism, not just a calendar.**
The `Day` entity (table: `days`) is a managed business-day record with `bussinessDate` (LocalDate), `startedAt`, `endedAt`, and `status` ("STARTED"/"ENDED"). Every entity's `createdOn`, `verifiedOn`, `approvedOn` etc. are foreign-key Long references to `days.id` (not a date field). `DayService.getDayId()` returns the max day id (which is always the current open day). The day must be explicitly "ended" via `DayService.endDay()`. This is a core business-process artifact that must be reproduced in the new system or replaced with a proper business-date management strategy.

---

## New Findings

**A. CROSS-DOMAIN COUPLING IS TOTAL — PatientServiceImpl is the single largest violation.**
`PatientServiceImpl` (imports span 100+ lines, lines 155–199 list ~45 injected repositories/services) orchestrates: Patient creation, PatientBill creation, Registration, Visit, ConsultationInsurancePlan lookups, LabTestTypeInsurancePlan lookups, MedicineInsurancePlan, RadiologyTypeInsurancePlan, ProcedureTypeInsurancePlan, Admission, WardBed, AdmissionBed, WardTypeInsurancePlan, Clinician, Dressing, Nurse, DressingChart, Consumable, ConsumableChart, ObservationChart, PrescriptionChart, NursingChart, NursingProgressNote, NursingCarePlan, ConsultationTransfer, Pharmacy, PharmacyMedicine, PharmacyMedicineBatch, PharmacySaleOrder, PharmacySaleOrderDetail, LabTest, LabTestAttachment, RadiologyAttachment, Theatre. This single service class crosses every bounded context except HR/Payroll and Assets. It will be the hardest decomposition point and must be split along context boundaries during the ADR design.

**B. ReportResource crosses ALL 14 bounded contexts in a single controller class.**
`ReportResource.java` injects 30+ repositories directly — spanning Clinical, Billing, Inpatient, Pharmacy, Lab, Radiology, Procedures, Inventory, Procurement, Cashiering, Identity. It performs in-process nested-loop joins in Java (e.g., the collections_report iterates all bills and for each bill iterates all collections — O(n×m) in Java memory). This is neither a query nor a service call — it is application-level joins that will not tolerate horizontal scaling and will fail on large datasets. The new system must replace this with proper query-layer aggregation (JPQL projections or SQL views).

**C. DOCUMENT NUMBERING HAS A RACE CONDITION — cannot be reproduced safely.**
The `SELECT MAX(id) + 1` pattern in each service (e.g., `GoodsReceivedNoteServiceImpl.requestRequestGrnNo()`, line 209–214) is non-atomic. Two concurrent requests can read the same MAX(id) and produce duplicate document numbers before either saves. The numbers are initialized with `try { id = repo.getLastId() + 1; } catch(Exception e) {}` — if `getLastId()` returns null (empty table), id defaults to 1L silently. The new system must replace this with a proper per-document-type database sequence (PostgreSQL `SEQUENCE` objects, one per document type). The number **format** must be preserved exactly; only the generation mechanism changes.

**D. StoreToPharmacy TO and PharmacyToPharmacy TO share the same document prefix "SPT".**
`StoreToPharmacyTOServiceImpl` line 438 and `PharmacyToPharmacyTOServiceImpl` line 428 both call `Formater.formatWithCurrentDate("SPT", id.toString())`. Their ID sequences are independent (separate tables), so the same `SPT20260602-5` number could exist for both a store-to-pharmacy transfer order and a pharmacy-to-pharmacy transfer order. The new system must assign distinct prefixes to distinguish document types.

**E. The `@PostMapping` for GET-like queries accepts domain entity objects as `@RequestBody`.**
Report endpoints (and many state-query endpoints) accept domain entity objects (e.g., `Clinician`, `Patient`) as `@RequestBody` parameters to pass filter criteria. Only the `id` or `nickname` field is read from the object. This creates a deserialization surface where the server accepts a full domain object but uses one field. Migrating to typed request DTOs (e.g., `ClinicianId`, `PatientId`) is required and is purely a technical improvement with no business-process impact.

**F. Resource classes inject repositories directly, bypassing the service layer.**
`GoodsReceivedNoteResource` injects `GoodsReceivedNoteDetailBatchRepository`, `GoodsReceivedNoteDetailRepository`, `StoreRepository` directly. `ReportResource` injects 30+ repositories directly. Business logic (the batch-qty validation on line 259–264 of `GoodsReceivedNoteResource`) lives inside the controller method. The service layer boundary is inconsistent and will require a thorough audit to ensure all business rules are captured when service interfaces are redesigned.

**G. No explicit `@Entity` count exists for a formal "60+ entities" claim — actual count is approximately 115 entity-bearing Java files in the domain package.**
The `/domain` directory contains 115+ Java files. Not all are `@Entity`; some are enums or inner classes, but the great majority are entities. The "60+ domain entities" figure in the grounded facts understates the actual scope.

**H. `User` entity deliberately uses manual getters/setters instead of Lombok `@Data`.**
`User.java` has `@NoArgsConstructor` and `@AllArgsConstructor` but the `@Data` annotation is commented out. Manual getters/setters are written below. This is probably to avoid Lombok-generated `equals`/`hashCode` on a security principal, but it means `User` has an inconsistent API surface versus all other entities.

**I. Timestamp storage includes a `createdOn` (Day FK) in addition to `createdAt` (LocalDateTime).**
Every entity stores both a `created_on_day_id` (Long FK to `days.id`) and a `createdAt` (LocalDateTime). Similarly for `verified`, `approved`, `received`. The `Day` FK is not an audit field — it is used by `DayService.getDayId()` to scope operations to the current business day. The new data model must decide whether to keep the Day FK or replace it with proper business-date indexing. If dropped, the `Day` entity workflow (open/close day) must also be replaced.

---

## Implications for the ADRs

1. **ADR: Modular Decomposition** — Decomposition is structurally blocked by `PatientServiceImpl` and `ReportResource`. The ADR must specify an explicit strangler-fig or seam strategy: extract thin anti-corruption-layer facades around each bounded context before splitting packages. The Patient context owns the most cross-cutting orchestration and must be the last to be isolated.

2. **ADR: Identifier Strategy (uid + internal id)** — The legacy `Patient.no` (`MRNO/{year}/{rawId}`) uses the database PK as part of the business number. In the new model, the uid is separate from the business no. The Flyway migration script must generate a uid for every legacy row while preserving the existing `no` string verbatim as the business document number.

3. **ADR: Document Numbering** — The `SELECT MAX(id)+1` pattern must be replaced with PostgreSQL sequences (one per document type). The number format strings are confirmed and must be reproduced exactly. The StoreToPharmacy-TO / PharmacyToPharmacy-TO prefix collision ("SPT") must be resolved by the product owner before migration.

4. **ADR: API Design** — The new API must correct the HTTP verb/status-code misuse (GET-like queries using POST+201), adopt typed request DTOs (not domain entity bodies), and introduce proper RESTful sub-resource paths. These are non-breaking to business process. The existing privilege codes (`GOODS_RECEIVED_NOTE-ALL`, `GOODS_RECEIVED_NOTE-CREATE`, etc.) must be reproduced verbatim in the new `@PreAuthorize` annotations.

5. **ADR: Security** — The JWT secret is `"<REDACTED>"` (hardcoded), not the properties value. Token expiry is 8 hours access / 24 hours refresh. The new system must use a configurable secret (externalized via environment variable), not a hardcoded value. The `authorizationToken` DB-persistence mechanism (commented out) should not be revived.

6. **ADR: Reporting** — All 21 report projection interfaces in `reports/models` must be migrated to equivalent Spring Data or JOOQ projection interfaces. The in-memory nested-loop joins in `ReportResource` must be replaced with proper database-level aggregation queries. The golden-master test requirement applies to output rows and cents.

7. **ADR: Business-Date Management** — The `Day` entity and `DayService` implement a business-day-open/close workflow that gates all `createdOn` stamping. The ADR for temporal data must decide whether this workflow is a business requirement (to replicate) or an operational artifact (to replace with a proper business-date configuration). All legacy timestamps carry a hardcoded UTC+3 offset (`LocalDateTime.now().plusHours(3)`) — the migration script must normalize these to UTC for storage, with display conversion handled at the API layer.

8. **ADR: Data Migration (Flyway)** — The schema has no authoritative DDL (ddl-auto=update). The migration team must reverse-engineer the schema from running entities + the live MySQL database. Every table needs a `uid` column (UUIDv7 recommended) added by the first Flyway migration script. The `days` table FK columns (`created_on_day_id`, etc.) on every entity are a migration complexity — either carry them forward as a business-date denormalization or replace with a timestamp-only approach in the new schema.
