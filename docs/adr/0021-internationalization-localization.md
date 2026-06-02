# ADR-0021: Internationalization & Localization

- **Status:** Proposed (Architecture phase)
- **Date:** 2026-06-02
- **Deciders:** solution-architect (reviewed by security-architect, data-architect)
- **Engagement:** Zana HMIS modernization, fresh build, no data migration

---

## Context

The legacy system (`com.orbix.api`) has zero i18n infrastructure. Confirmed findings from `legacy-findings.md` and direct code inspection:

- No `MessageSource`, no `messages.properties`, no `Locale` import anywhere in the 48 resource classes or 115+ domain files.
- All user-facing strings are Java string literals embedded inline (e.g., `throw new NotFoundException("Clinician not found")` in `ReportResource.java` line 171).
- Date formatting is locale-agnostic: `DayServiceImpl.getBussinessDate()` uses `DateTimeFormatter.ofPattern("yyyy-MM-dd")` with no `Locale` argument; `Formater.formatWithCurrentDate()` uses `DateTimeFormatter.ofPattern("yyyyMMdd")` — both are ISO patterns unaffected by locale.
- Monetary amounts are raw `double` values serialized directly by Jackson with no locale-dependent formatting — they appear as bare JSON numbers (e.g., `150000.0`).
- The 36+ report endpoints in `ReportResource.java` return dates as either `LocalDate`/`LocalDateTime` object fields (serialized by Jackson as ISO-8601 strings) or as hand-trimmed strings via `.toString().substring(0,10)` (line 1489) — both yield `yyyy-MM-dd` regardless of locale.
- `DayServiceImpl.getTimeStamp()` stores `LocalDateTime.now().plusHours(3)` — a hardcoded UTC+3 wall-clock offset, not a locale construct.

ADR-0014 (cross-cutting conventions), section 8, already mandates: "All user-facing validation and error messages MUST be externalized to `src/main/resources/messages.properties` ... A `MessageSource` bean configured in the common config reads these files." This ADR operationalizes that mandate with concrete scope, structure, locale-resolution strategy, and the critical exact-process guard for report formatting.

The deployment context is a single hospital in Tanzania. The operational language is English; Swahili is required as an optional variant for UI-facing messages (labels, validation errors, alert text). There is no requirement for any other locale. Report content must reproduce legacy output exactly — this is the binding constraint that limits where i18n may and may not be applied.

---

## Decision

### 1. Scope of i18n

i18n applies to **UI-facing message text only**: Bean Validation constraint messages, `DomainException` message strings surfaced in the RFC 7807 `ProblemDetail` body (`title`/`detail`, per ADR-0005/0014), and any server-rendered label or status description returned in a DTO `description` field. It does NOT apply to:

- Number and monetary formatting in API responses — all `BigDecimal` amounts serialize as JSON strings in the format `"150000.00"` (plain decimal, no thousands separator, no currency symbol) regardless of locale. The Angular frontend owns display formatting.
- Date/time values in API responses — all `Instant`/`LocalDate`/`LocalDateTime` fields serialize as ISO-8601 strings (`yyyy-MM-ddTHH:mm:ssZ` and `yyyy-MM-dd`). No locale-dependent date pattern is applied server-side.
- Report column values — see section 4 (exact-process guard).
- Internal log messages, developer-facing exception messages, and `@PreAuthorize` privilege codes — these are never user-facing.

### 2. Locale resolution

The server resolves locale from the HTTP `Accept-Language` request header. Spring's `AcceptHeaderLocaleResolver` is the resolver (registered in `CommonWebConfig`). Supported locales: `en` (English, default) and `sw` (Swahili). Any unsupported locale falls back to `en`. There is no cookie-based, session-based, or URL-embedded locale — this is a stateless API and the client (Angular) carries locale preference in the request header.

Configuration in `CommonWebConfig`:

```java
@Bean
public LocaleResolver localeResolver() {
    AcceptHeaderLocaleResolver r = new AcceptHeaderLocaleResolver();
    r.setDefaultLocale(Locale.ENGLISH);
    r.setSupportedLocales(List.of(Locale.ENGLISH, Locale.forLanguageTag("sw")));
    return r;
}
```

### 3. Message-bundle structure

All message bundles live under `src/main/resources/i18n/` to keep them separate from `application.yml` and Flyway migration scripts:

```
src/main/resources/i18n/
  messages.properties          # English base (authoritative)
  messages_sw.properties       # Swahili variant (net-new, no process change)
```

Key naming convention: `{context}.{entity}.{constraint-or-event}` — for example:

```
# registration
registration.patient.name.required=Patient name is required
registration.patient.mrno.format=MR number must match format MRNO/YYYY/NNNNNN
# billing
billing.invoice.already_settled=Invoice is already settled
billing.cash_gate.not_settled=Service cannot proceed: invoice not yet settled
# iam
iam.user.username.taken=Username is already in use
# common
common.not_found=Resource not found
common.validation_failed=Validation failed
```

Swahili variant keys mirror English keys exactly; missing Swahili keys fall back to English (Spring `MessageSource` default behaviour with `setUseCodeAsDefaultMessage(false)` and parent-source chaining).

`MessageSource` bean in `CommonConfig`:

```java
@Bean
public MessageSource messageSource() {
    ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
    ms.setBasename("classpath:i18n/messages");
    ms.setDefaultEncoding("UTF-8");
    ms.setFallbackToSystemLocale(false);
    return ms;
}

@Bean
public LocalValidatorFactoryBean validator(MessageSource messageSource) {
    LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
    factory.setValidationMessageInterpolator(
        new MessageSourceMessageInterpolator(messageSource));
    return factory;
}
```

Bean Validation constraint messages reference keys via the standard `{key}` syntax on the annotation:

```java
@NotBlank(message = "{registration.patient.name.required}")
private String name;
```

`DomainException` subclasses resolve their human-readable message via `MessageSource` at construction time in the service layer, using the `LocaleContextHolder` locale, so the resolved string is placed in the `ProblemDetail` `title`/`detail` as already-localized text.

### 4. Exact-process guard for reports

**Rule:** any value that appears as a column in the 29+ legacy report endpoints and is observable by an auditor, a patient, or an insurer is a process-observable value and MUST reproduce the legacy format regardless of locale. Specifically:

- **Dates in report payloads** — the legacy serializes dates as ISO `yyyy-MM-dd` strings (confirmed: `DayServiceImpl` pattern, `substring(0,10)` pattern in `IPDReport`). The fresh build preserves this format by serializing `LocalDate` fields directly via Jackson's `JavaTimeModule` with `WRITE_DATES_AS_TIMESTAMPS = false`. No `DateTimeFormatter` that varies by locale is used for report date fields.
- **Amounts in report payloads** — the legacy serializes amounts as plain `double` JSON numbers. The fresh build serializes `BigDecimal` as JSON strings in `"#0.00"` format (two decimal places, no thousands separator) via a custom `BigDecimalSerializer` registered in `JacksonConfig`. This is process-equivalent (same cent value) and locale-independent.
- **Document numbers** (`GRN20260602-47`, `MRNO/2026/42`, `USR-000042`, etc.) — these are already locale-free string templates (see ADR-0009); no change.
- **Status labels in reports** — the legacy returns raw status strings (`"PAID"`, `"COVERED"`, `"VERIFIED"`) as enum names. The fresh build preserves this: report DTOs carry `String status` fields populated from enum `.name()`, not from `MessageSource`. i18n of status labels is restricted to the Angular layer.

**Implementation rule:** report query DTOs (`*ReportDto`, `*ReportLine`) are annotated `@JsonSerialize` with locale-invariant serializers. The `GlobalExceptionHandler` and service-layer message resolution use `MessageSource` + `LocaleContextHolder`; report DTO serialization does not.

### 5. Angular frontend responsibility

The Angular 18 frontend owns:

- Locale switching (stores selected locale in browser storage, sends `Accept-Language` header on every API request).
- Display formatting of dates (Angular `DatePipe` with the active locale), monetary amounts (`CurrencyPipe`/`DecimalPipe`), and status label translation (via `ngx-translate` or Angular i18n with Swahili translation files).
- The server never renders a formatted date or formatted currency string for UI display — it always sends ISO dates and plain decimal amounts.

---

## Considered Alternatives

| Option | Assessment | Verdict |
|---|---|---|
| Locale embedded in URL path (`/sw/api/v1/...`) | Breaks REST resource identity, complicates OpenAPI generation, incompatible with ADR-0005 URL convention | Rejected |
| Session-based locale (`LocaleChangeInterceptor` + `SessionLocaleResolver`) | Requires stateful session; contradicts JWT stateless auth (ADR-0006) | Rejected |
| Cookie-based locale | Slightly stateful; unnecessary complexity for a single-locale deployment with optional Swahili | Rejected |
| `Accept-Language` header (chosen) | Stateless, standard HTTP, compatible with JWT auth, trivially supported by Angular `HttpClient` interceptor | Accepted |
| Full JVM-locale formatting of report numbers/dates | Would alter process-observable output in the 29 reports; violates the exact-process mandate | Rejected outright |
| Single `messages.properties` with no `_sw` variant | Eliminates Swahili option; contradicts the stated optional-Swahili requirement | Rejected |
| Place bundles at `src/main/resources/messages.properties` (root) | Collides with Spring Boot auto-config `spring.messages.basename` default; separating to `i18n/` avoids accidental double-loading | Rejected |

---

## Consequences

### Positive

- All user-facing validation and error strings are in one place; a translator produces `messages_sw.properties` without touching Java code.
- `ErrorCode` enum names (already in `GlobalExceptionHandler`) give the Angular frontend a machine-readable discriminator for programmatic reactions (resolves the FRONTEND_GAPS.md B5 pattern-matching anti-pattern).
- The locale-resolution strategy is stateless and requires zero schema changes.
- Report output is guaranteed locale-invariant by design, satisfying the exact-process guard without per-report test overrides.

### Negative / Risks

- **Discipline risk:** engineers new to the codebase will add inline strings in exception constructors. Mitigation: ArchUnit rule asserting no `DomainException` subclass constructor takes a `String` literal argument directly (it must pass a message-key constant); enforced as a CI gate alongside the existing `ApplicationModules.verify()` test.
- **Swahili completeness:** `messages_sw.properties` starts empty (no legacy Swahili content exists). The `setFallbackToSystemLocale(false)` + parent-chaining means missing Swahili keys silently serve English, which is correct behaviour but may hide untranslated strings in QA. Mitigation: a CI step (`mvn test -Pl10n-check`) counts keys in `messages_sw.properties` vs `messages.properties` and posts a warning (not a failure) on mismatch.
- **Report serializer registration:** if a developer adds a report DTO field typed `BigDecimal` without annotating it with the locale-invariant serializer, it may serialize differently across Jackson versions. Mitigation: a base `ReportDto` abstract class pre-registers the `BigDecimalSerializer` via `@JsonSerialize`; all report DTOs extend it.

---

## Exact-Process Impact

**No process change.** The legacy produces all report values in locale-invariant formats (ISO dates, plain doubles). The fresh build reproduces those formats exactly via locale-invariant serializers. Swahili message support is net-new capability that affects only UI validation text — it cannot alter workflow state, document numbers, report content, or billing arithmetic.

**Preserved verbatim:**
- All date strings in report DTOs: `yyyy-MM-dd` (ISO, locale-independent).
- All amount values in report DTOs: plain decimal string, 2 dp (e.g., `"150000.00"`), locale-independent.
- All document number strings: unchanged (ADR-0009).
- All status strings in report DTOs: enum `.name()` values, locale-independent.

**Legacy-analyst must confirm:**
- Whether any printed report (receipt, discharge summary, referral letter) renders a human-readable date label (e.g., "Tarehe:") that must be in Swahili — if so, those labels belong in `messages_sw.properties` and the document renderer (ADR-NEW-C) must resolve them via `MessageSource` at render time.
- Whether the `DayService.getBussinessDate()` `yyyy-MM-dd` return is displayed directly to the user on screen (current finding: it is) — if so, the Angular `DatePipe` should reformat it for display; the API value stays ISO.

---

## Implementation Notes

**File locations:**
- `src/main/resources/i18n/messages.properties` — English base
- `src/main/resources/i18n/messages_sw.properties` — Swahili variant (initially empty stubs)
- `com.otapp.hmis.engine.common.config.I18nConfig` — `MessageSource` + `LocaleResolver` + `LocalValidatorFactoryBean` beans
- `com.otapp.hmis.engine.common.config.JacksonConfig` — `BigDecimalSerializer` (plain `#0.00`, no locale) + `JavaTimeModule` (ISO-8601, no timestamps)
- `com.otapp.hmis.engine.common.api.GlobalExceptionHandler` — already resolves `DomainException.getMessage()` as a pre-resolved string; no change needed at the handler level

**Spring Boot property to set** (overrides the auto-config default basename):
```yaml
spring:
  messages:
    basename: i18n/messages
    encoding: UTF-8
    fallback-to-system-locale: false
```

**ArchUnit rule (add to `CommonArchTest.java`):**
```java
noClasses().that().areAssignableTo(DomainException.class)
    .should().callConstructor(String.class)
    .because("DomainException messages must be resolved via MessageSource, not inline literals");
```

**Angular integration:** add an `HttpInterceptor` that injects `Accept-Language: sw` or `Accept-Language: en` based on the active locale stored in `localStorage`. No backend change is needed when the user switches language — the next request carries the new header and receives localized message text in the `ProblemDetail` body.

**Swahili coverage CI step** (add to `.github/workflows/build.yml`):
```bash
en_count=$(grep -c '=' src/main/resources/i18n/messages.properties)
sw_count=$(grep -c '=' src/main/resources/i18n/messages_sw.properties)
if [ "$sw_count" -lt "$en_count" ]; then
  echo "::warning::Swahili messages incomplete: $sw_count/$en_count keys translated"
fi
```
