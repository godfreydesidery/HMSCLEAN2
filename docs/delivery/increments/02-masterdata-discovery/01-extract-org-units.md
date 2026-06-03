## EXTRACTION 1 — Organizational Units (Legacy `com.orbix.api`, READ-ONLY)

All citations are to the legacy source under `D:/My_Works/HMS/ZANAHMIS-2-feature/Zana-HMIS-API/api/api/src/main/java/com/orbix/api/`. Legacy uses `@GeneratedValue(strategy = IDENTITY)` `Long id` (no `uid`), JPA `double` for money, and `@Table` names are PLURAL snake_case (already matches the HMSCLEAN2 directive). Every entity carries a forensic triplet: `createdBy` (`created_by_user_id`, NOT NULL, not-updatable), `createdOn` (`created_on_day_id`, NOT NULL, not-updatable — FK-by-convention to the `Day` entity), `createdAt` (`LocalDateTime`, defaulted `now()`).

NOTE: No entity in this extraction carries `@Audited` (Hibernate Envers) and no device-fingerprint logic touches these masterdata entities — consistent with the standing finding that both are phantom features. (No audit/auth surface is introduced by these masterdata entities; full Envers/device-binding confirmation belongs to the IAM/security spec.)

### Clinic — `domain/Clinic.java`, `@Table(name = "clinics")`
- `id` Long, IDENTITY PK (Clinic.java:43-45).
- `code` String, `@NotBlank`, `@Column(unique=true)` (Clinic.java:46-48). Nullable at DB level (no `nullable=false`), but bean-validated non-blank.
- `name` String, `@NotBlank`, `@Column(unique=true)` (Clinic.java:49-51).
- `description` String, nullable, no constraint (Clinic.java:52).
- `consultationFee` `double`, `@NotNull` (Clinic.java:53-54). **This is the cash/self-pay consultation price, stored directly on the Clinic row.**
- `active` boolean, default `false` (Clinic.java:55).
- `clinicians` `Set<Clinician>`, `@ManyToMany(mappedBy = "clinics")` — INVERSE side of the clinic↔clinician M2M; `@Fetch(SUBSELECT)`, `@JsonIgnoreProperties("clinics")` (Clinic.java:57-60).
- Forensic triplet (Clinic.java:63-67).
- **No `clinicType` / `ClinicType` field, FK, or enum exists** — a Clinic's "type" is not modeled; clinics are distinguished only by `code`/`name`/free-text `description`. Grep for `ClinicType|clinicType` across `com.orbix.api` returned zero matches.

### Ward — `domain/Ward.java`, `@Table(name = "wards")`
- `id` Long IDENTITY PK (Ward.java:39-41).
- `code` String, `@Column(unique=true, nullable=false)` (Ward.java:42-43).
- `name` String, `@Column(unique=true, nullable=false)` (Ward.java:44-45).
- `noOfBeds` `int`, default `0` (Ward.java:46). Denormalized counter; not a derived/computed field.
- `active` boolean, default `false` (Ward.java:47).
- `wardCategory` `@ManyToOne(EAGER, optional=false)` → `WardCategory`, `@JoinColumn(name="ward_category_id", nullable=false, updatable=true)`, `@OnDelete(NO_ACTION)` (Ward.java:49-52).
- `wardType` `@ManyToOne(EAGER, optional=false)` → `WardType`, `@JoinColumn(name="ward_type_id", nullable=false, updatable=true)`, `@OnDelete(NO_ACTION)` (Ward.java:54-57).
- Forensic triplet (Ward.java:59-63).
- **No price field on Ward.** Ward pricing is delegated entirely to `WardType.price`.

### WardType — `domain/WardType.java`, `@Table(name = "ward_types")`
- `id` Long IDENTITY PK (WardType.java:31-33).
- `code` String, `@Column(unique=true, nullable=false)` (WardType.java:34-35).
- `name` String, `@Column(unique=true, nullable=false)` (WardType.java:36-37).
- `description` String, nullable (WardType.java:38).
- `price` `double`, `@Column(nullable=false)`, default `0` (WardType.java:39-40). **This is the cash/self-pay per-stay ward charge.**
- Forensic triplet (WardType.java:42-46).

### WardCategory — `domain/WardCategory.java`, `@Table(name = "ward_categories")`
- `id` Long IDENTITY PK (WardCategory.java:38-40).
- `code` String, `@Column(unique=true, nullable=false)` (WardCategory.java:41-42).
- `name` String, `@Column(unique=true, nullable=false)` (WardCategory.java:43-44).
- `description` String, nullable (WardCategory.java:45).
- Forensic triplet (WardCategory.java:47-51).
- **No price field** — `WardCategory` is purely a descriptive grouping; it carries no pricing or capacity data.

### WardBed — `domain/WardBed.java`, `@Table(name = "ward_beds")`
- `id` Long IDENTITY PK (WardBed.java:38-40).
- `no` String, `@Column(nullable=false)` — the bed number/label; **NOT marked unique** (uniqueness of bed number within a ward is not DB-enforced) (WardBed.java:41-42).
- `status` String, free-text status (no enum) (WardBed.java:43).
- `active` boolean, default `false` (WardBed.java:44).
- `ward` `@ManyToOne(EAGER, optional=false)` → `Ward`, `@JoinColumn(name="ward_id", nullable=false, updatable=false)`, `@OnDelete(NO_ACTION)` (WardBed.java:46-49).
- Forensic triplet (WardBed.java:51-55).
- **WardBed is the physical-bed master record** (a bed belongs to one Ward).

### AdmissionBed — `domain/AdmissionBed.java`, `@Table(name = "admission_beds")`
- `id` Long IDENTITY PK (AdmissionBed.java:41-43).
- `status` String, free-text (AdmissionBed.java:44).
- `wardBed` `@ManyToOne(EAGER, optional=false)` → `WardBed`, `@JoinColumn(name="ward_bed_id", nullable=false, updatable=false)`, `@OnDelete(NO_ACTION)` (AdmissionBed.java:46-49).
- `patientBill` `@OneToOne(EAGER, optional=false)` → `PatientBill`, `@JoinColumn(name="patient_bill_id", nullable=false, updatable=false)`, `@OnDelete(NO_ACTION)` (AdmissionBed.java:51-54). Each occupancy is tied to exactly one bed charge.
- `admission` `@ManyToOne(EAGER, optional=false)` → `Admission`, `@JoinColumn(name="admission_id", nullable=false, updatable=false)` (AdmissionBed.java:56-59).
- `patient` `@ManyToOne(EAGER, optional=false)` → `Patient`, `@JoinColumn(name="patient_id", nullable=false, updatable=false)` (AdmissionBed.java:61-64).
- `openedAt` `LocalDateTime` default `now()`; `closedAt` `LocalDateTime` nullable (AdmissionBed.java:66-67).
- **AdmissionBed is the occupancy/billing transaction record** (which patient occupied which physical `WardBed` during which `Admission`, the linked bed charge, and the open/close timestamps). It is NOT a master bed and NOT an inventory entity. NOTE: AdmissionBed has NO forensic triplet (no `createdBy`/`createdOn`/`createdAt`); it uses `openedAt`/`closedAt` instead.

### Pharmacy — `domain/Pharmacy.java`, `@Table(name = "pharmacies")`
- `id` Long IDENTITY PK (Pharmacy.java:35-37).
- `code` String, `@NotBlank`, `@Column(unique=true)` (Pharmacy.java:38-40).
- `name` String, `@NotBlank`, `@Column(unique=true)` (Pharmacy.java:41-43).
- `description` String, nullable (Pharmacy.java:44).
- `location` String, nullable (Pharmacy.java:45).
- `category` String, nullable — free-text category, NOT an enum/FK (Pharmacy.java:46).
- `active` boolean, default `false` (Pharmacy.java:47).
- Forensic triplet (Pharmacy.java:49-53).
- No relationships declared on Pharmacy itself (line 7 imports `Set`, line 14 imports `ManyToMany` but neither is used — dead imports).

### Store — `domain/Store.java`, `@Table(name = "stores")`
- `id` Long IDENTITY PK (Store.java:37-39).
- `code` String, `@NotBlank`, `@Column(unique=true)` (Store.java:40-42).
- `name` String, `@NotBlank`, `@Column(unique=true)` (Store.java:43-45).
- `description` String, nullable (Store.java:46).
- `location` String, nullable (Store.java:47).
- `category` String, nullable — free-text (Store.java:48).
- `active` boolean, default `false` (Store.java:49).
- `storePersons` `Set<StorePerson>`, `@ManyToMany(mappedBy = "stores")`, `@Fetch(SUBSELECT)`, `@JsonIgnoreProperties("stores")` (Store.java:51-54) — INVERSE side of a store↔storePerson M2M owned by `StorePerson.stores` (analogous to the Clinic/Clinician pattern).
- Forensic triplet (Store.java:56-60).

### Theatre — `domain/Theatre.java`, `@Table(name = "theatres")`
- `id` Long IDENTITY PK (Theatre.java:30-32).
- `code` String, `@Column(unique=true, nullable=false)` (Theatre.java:33-34).
- `name` String, `@Column(unique=true, nullable=false)` (Theatre.java:35-36).
- `description` String, nullable (Theatre.java:37).
- `location` String, nullable (Theatre.java:38).
- `active` boolean, default `false` (Theatre.java:39).
- Forensic triplet (Theatre.java:41-45).
- No relationships, no price field.

---

## Answers to the specific questions

**1. Is `ClinicType` an enum or an entity?**
Neither. There is no `ClinicType` enum or entity anywhere in `com.orbix.api` (grep for `ClinicType|clinicType` = 0 matches), and `Clinic` has no type field. A clinic is identified solely by `code`, `name`, and free-text `description` (Clinic.java:46-52). The increment-02 spec must not introduce a `ClinicType` unless it is a deliberate, change-requested addition (flag as a decision).

**2. How is a Clinic's consultation fee stored / priced?**
Two-tier:
- Cash / self-pay: the fee is the `Clinic.consultationFee` `double` stored directly on the clinic row (Clinic.java:53-54). At point of care, `PatientServiceImpl` creates a `PatientBill` with `amount = balance = clinic.getConsultationFee()`, `paid = 0`, status `"UNPAID"` (PatientServiceImpl.java:459-466). For a follow-up visit (`followUp == true`) the status is set to `"NONE"` (PatientServiceImpl.java:467-469).
- Insurance: when `paymentType == "INSURANCE"`, the cash fee is OVERRIDDEN by looking up `ConsultationInsurancePlan` via `consultationInsurancePlanRepository.findByClinicAndInsurancePlanAndCovered(clinic, insurancePlan, true)` (PatientServiceImpl.java:597). If absent → `InvalidOperationException("Plan not available for this clinic. Please change payment method")` (PatientServiceImpl.java:599-601). When present, the bill is set to `amount = paid = consultationPricePlan.getConsultationFee()`, `balance = 0`, status `"COVERED"` (or `"NONE"` for follow-up) (PatientServiceImpl.java:602-608). `ConsultationInsurancePlan` (`@Table consultation_insurance_plans`) is keyed by `(clinic_id, insurance_plan_id)` and carries `consultationFee` double + `covered` + `active` booleans (ConsultationInsurancePlan.java:36-54). So per-plan consultation pricing lives in `ConsultationInsurancePlan`, NOT on the clinic.

**3. How are wards priced — per `WardType`, or per-`Ward` override?**
Per `WardType` only. There is NO per-`Ward` price field or override.
- Cash: ward-bed billing reads `wb.getWard().getWardType().getPrice()` — i.e. traverses `WardBed → Ward → WardType.price` (PatientServiceImpl.java:1754, 1756; WardType.java:39-40). The `Ward` and `WardCategory` entities have no `price` field at all.
- Insurance: overridden via `WardTypeInsurancePlan` (`@Table ward_type_insurance_plans`), keyed by `(ward_type_id, insurance_plan_id)` with `price` double + `covered`/`active` booleans (WardTypeInsurancePlan.java:36-54). The admission path loads `wardTypeInsurancePlanRepository.findByInsurancePlanAndCovered(plan, true)` then matches the bed's ward type (PatientServiceImpl.java:1795-1799).
- `WardCategory` is a non-priced descriptive grouping only.

**4. Standalone `Bed` entity vs `WardBed`/`AdmissionBed`?**
There is NO standalone `Bed` entity (glob `*Bed*.java` returns exactly `WardBed.java` and `AdmissionBed.java`). The model is two distinct entities:
- `WardBed` = physical bed master (belongs to one `Ward`, has `no`, `status`, `active`).
- `AdmissionBed` = an occupancy/charging transaction linking a `WardBed` + `Admission` + `Patient` + a single `PatientBill`, with `openedAt`/`closedAt`. `Admission` itself also holds a direct `@OneToOne` to the currently-assigned `WardBed` (`ward_bed_id`, nullable, Admission.java:62-65).

**5. Clinic–clinician affiliation: is it ONLY via iam `Clinician.clinics`, and is the spec's masterdata `ClinicClinician` net-new?**
Confirmed: the affiliation is ONLY the M2M owned by `Clinician.clinics`.
- `Clinician.clinics` is `Collection<Clinic>` with a bare `@ManyToMany(fetch=EAGER)` and NO `@JoinTable` and NO `mappedBy` → this is the OWNING side; Hibernate derives the join table from defaults (Clinician.java:69-71).
- `Clinic.clinicians` is `@ManyToMany(mappedBy = "clinics")` → the INVERSE side (Clinic.java:57-60).
- There is NO masterdata-side `clinic_clinician` table and NO entity named `ClinicClinician` in the legacy code. Grep for `clinic_clinician|clinicians_clinics|clinic_clinicians` (case-insensitive) across the whole legacy repo = 0 matches, confirming the join-table name is never hard-coded; it is purely JPA-default-derived from the owning entity (`Clinician`).
- Therefore the increment-02 spec's `ClinicClinician` masterdata table is NET-NEW (no legacy equivalent). Per the IAM-reality note, the legacy owner is iam's `Clinician` entity; reproducing this in masterdata would relocate ownership across the module boundary. Flag as a decision (see decisions[]).

NOTE on the exact derived join-table name: because the owning side declares no `@JoinTable`, the physical name depends on Hibernate's default `ImplicitNamingStrategy` and the actual deployed DDL/Flyway baseline. The owner is `Clinician` (`@Table clinicians`) with field `clinics`; the legacy code never names the table explicitly. The precise physical name should be read from the deployed schema, not assumed — recorded as a decision.