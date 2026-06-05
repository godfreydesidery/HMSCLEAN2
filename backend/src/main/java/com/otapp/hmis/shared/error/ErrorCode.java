package com.otapp.hmis.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error catalogue (ADR-0005 §5, ADR-0014 §6).
 *
 * <p>Each constant maps onto the RFC 7807 {@code type} URI. The Angular client branches on the
 * {@code type} (a {@code urn:hmis:error:*} URN), never on free-text messages.
 */
public enum ErrorCode {

    NO_DAY_OPEN("urn:hmis:error:no-day-open", HttpStatus.UNPROCESSABLE_ENTITY, "No business day is open"),
    NOT_FOUND("urn:hmis:error:not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT("urn:hmis:error:conflict", HttpStatus.CONFLICT, "Conflict"),
    BUSINESS_RULE("urn:hmis:error:business-rule", HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation"),
    VALIDATION("urn:hmis:error:validation", HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_CREDENTIALS("urn:hmis:error:invalid-credentials", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    INVALID_TOKEN("urn:hmis:error:invalid-token", HttpStatus.UNAUTHORIZED, "Invalid or expired token"),
    TOKEN_REUSE_DETECTED("urn:hmis:error:token-reuse-detected", HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
    UNAUTHENTICATED("urn:hmis:error:unauthenticated", HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN("urn:hmis:error:forbidden", HttpStatus.FORBIDDEN, "Access denied"),
    INTERNAL("urn:hmis:error:internal", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"),

    /**
     * No matching {@code service_prices} row found for the given
     * (planUid, kind, serviceUid, currency) tuple (build-spec §2.2, §2.4, AC-5).
     * HTTP 422 Unprocessable Entity — the request is syntactically valid but the pricing
     * data is missing.  Thrown by {@code PriceLookupImpl} when neither an insurance hit
     * nor a cash fallback exists.
     */
    SERVICE_PRICE_NOT_FOUND("urn:hmis:error:service-price-not-found",
            HttpStatus.UNPROCESSABLE_ENTITY, "Service price not found"),

    /**
     * A {@code service_prices} row with the same (plan_uid, kind, service_uid, currency)
     * composite key already exists (build-spec §2.1 COALESCE unique index, AC-5).
     * HTTP 409 Conflict. Distinct type URI from the generic CONFLICT to allow clients to
     * branch on this specific duplicate-price scenario.
     */
    DUPLICATE_SERVICE_PRICE("urn:hmis:error:duplicate-service-price",
            HttpStatus.CONFLICT, "Duplicate service price"),

    /**
     * The target user does not hold the {@code CLINICIAN} role and therefore cannot be
     * affiliated with a clinic (CR-08, build-spec §5.2, AC-4).
     * HTTP 403 Forbidden — distinct from the generic access-denied FORBIDDEN so the Angular
     * client can surface a specific "user must have CLINICIAN role" message.
     */
    CLINICIAN_ROLE_REQUIRED("urn:hmis:error:clinician-role-required",
            HttpStatus.FORBIDDEN, "User must hold the CLINICIAN role for this operation"),

    /**
     * A {@code POST /api/v1/masterdata/company-profile} attempt when a company-profile row
     * already exists (build-spec §1.5, CR-14).
     *
     * <p>The company-profile is a singleton: exactly one row is allowed. A second POST is
     * rejected with 409 rather than silently replacing the existing row (improvement over
     * legacy's {@code deleteAll()+keepOne} — CR-14 ratified).
     * HTTP 409 Conflict.
     */
    COMPANY_PROFILE_EXISTS("urn:hmis:error:company-profile-exists",
            HttpStatus.CONFLICT, "A company profile already exists; use PUT to update it"),

    /**
     * Insurance plan has no covered price for this clinic/service; the patient must change
     * payment method to cash (consultation hard-fail — PatientServiceImpl.java:599-601,
     * build-spec §2.2). HTTP 422. Verbatim legacy message preserved in the exception detail.
     */
    PLAN_NOT_AVAILABLE_FOR_CLINIC("urn:hmis:error:plan-not-available-for-clinic",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Plan not available for this clinic. Please change payment method"),

    /**
     * One or more bills in the payment request are not in a payable state
     * (PatientBillResource.java:295-296). Payable states: UNPAID, VERIFIED.
     * HTTP 422.
     */
    BILL_NOT_PAYABLE("urn:hmis:error:bill-not-payable",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "One or more bills have been paid/covered/canceled and cannot be paid again"),

    /**
     * The tendered total does not exactly equal the sum of selected bill amounts
     * (CR-12 — replaces legacy {@code double ==} with {@code BigDecimal.compareTo == 0}).
     * PatientBillResource.java:389-391. HTTP 422.
     */
    PAYMENT_AMOUNT_MISMATCH("urn:hmis:error:payment-amount-mismatch",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Tendered amount does not match total bill amount. Insufficient payment"),

    /**
     * A CASH outpatient/outsider service was requested before its charge was settled (paid)
     * (CR-05, RATIFIED scoped — NET-NEW hardening; legacy had only a UI filter, no hard gate).
     * Scoped per {@link com.otapp.hmis.billing.api.SettlementPolicy}: insurance-covered, inpatient
     * (settle at discharge), and emergency/unregistered charges bypass this gate. HTTP 422.
     */
    PAY_BEFORE_SERVICE("urn:hmis:error:pay-before-service",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Payment is required before this service can be provided"),

    /**
     * An INSURANCE registration request is missing a required {@code insurancePlanUid} or
     * {@code membershipNo} (build-spec §2.3 step 1, §5.3; PatientResource.java:299-301).
     * HTTP 422 Unprocessable Entity.
     */
    MISSING_INSURANCE_INFORMATION("urn:hmis:error:missing-insurance-information",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Insurance plan and membership number are required for insurance patients"),

    /**
     * A stock-decrementing operation (clinical dispense, OTC dispense, transfer goods-issue,
     * GRN approve) was requested for more quantity than is available (inc-08, AC-RX-DSP-07/20,
     * AC-OTC-08). Reproduces the legacy hard negative-stock REFUSAL
     * ({@code PatientResource.java:3243-3250}, {@code :6272-6273}) — the verbatim exact-process
     * gate — surfaced as a stable RFC 7807 type so the Angular client can react without
     * string-matching. The DB {@code CHECK (stock >= 0)} is a net-new backstop; this app-layer
     * 422 reject is the frozen parity element. HTTP 422 Unprocessable Entity.
     */
    INSUFFICIENT_STOCK("urn:hmis:error:insufficient-stock",
            HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient stock to fulfil this request");

    private final String type;
    private final HttpStatus status;
    private final String title;

    ErrorCode(String type, HttpStatus status, String title) {
        this.type = type;
        this.status = status;
        this.title = title;
    }

    public String type() {
        return type;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
