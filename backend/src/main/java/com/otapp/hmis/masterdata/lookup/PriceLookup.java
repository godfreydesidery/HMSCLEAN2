package com.otapp.hmis.masterdata.lookup;

/**
 * Cross-module pricing primitive (build-spec §2.2, CR-04).
 *
 * <p>This interface is the ONLY pricing API that modules outside {@code masterdata} may call.
 * The implementation ({@code PriceLookupImpl}) is package-private in
 * {@code masterdata.application}.
 *
 * <p><b>This is a STORAGE-TIER primitive only.</b> It performs a row lookup against
 * {@code service_prices} and returns the matched row. It does NOT implement the
 * legacy resolve-time business logic documented in build-spec §2.3 (PatientServiceImpl).
 * That logic — ward referral-override + principal/supplementary top-up split, per-service
 * not-covered fallback asymmetry (consultation hard-fail; lab/rad/proc/med cash-VERIFIED for
 * inpatients; registration silent-UNPAID), and the two-step cash-first then insurance-override
 * bill construction — belongs in the BILLING increment's application code, which consumes
 * this method. See build-spec §2.3 for the full consumer contract.
 *
 * <h2>Resolution order (storage tier only)</h2>
 * <ol>
 *   <li>If {@code planUid != null}: query the COVERED insurance row
 *       {@code (plan_uid=planUid, kind, service_uid match, currency, covered=TRUE)}.
 *       If found → return it (insurance hit).</li>
 *   <li>Cash fallback: query {@code (plan_uid IS NULL, kind, service_uid match, currency)}.
 *       If found → return the cash row.</li>
 *   <li>Neither → throw {@link com.otapp.hmis.masterdata.application.ServicePriceNotFoundException}
 *       → RFC7807 {@code urn:hmis:error:service-price-not-found}, HTTP 422.</li>
 * </ol>
 *
 * <h2>service_uid matching rule</h2>
 * <ul>
 *   <li>For {@link ServiceKind#REGISTRATION}: {@code service_uid IS NULL} (plan-only keyed —
 *       CR-18; NOT the magic string "DEFAULT").</li>
 *   <li>For all other kinds: {@code service_uid = serviceUid} (the caller supplies the entity uid).</li>
 *   <li>For {@link ServiceKind#WARD}: {@code serviceUid} = {@code WardType.uid}
 *       (WardType-only — CR-12; per-ward override is [GATED:CR-12]).</li>
 * </ul>
 *
 * <h2>Inert fields</h2>
 * {@code minAmount}, {@code maxAmount}, and {@code currency} are stored and returned in
 * {@link ServicePriceResult} but MUST NOT drive behaviour in this method or in the calling
 * billing code (CR-11). {@code active} is also inert in resolution (CR-04 extract item 6).
 */
public interface PriceLookup {

    /**
     * Resolve the effective price for a service charge.
     *
     * @param planUid    the insurance plan uid, or {@code null} for a cash lookup
     * @param kind       the billable service category (never {@code null})
     * @param serviceUid the service entity uid; {@code null} is valid only for
     *                   {@link ServiceKind#REGISTRATION}
     * @param currency   the ISO 4217 currency code (e.g. "TZS")
     * @return the matched price row as a {@link ServicePriceResult}
     * @throws com.otapp.hmis.masterdata.application.ServicePriceNotFoundException
     *         (HTTP 422 {@code urn:hmis:error:service-price-not-found}) when no covered
     *         insurance row and no cash row exists for the given parameters
     */
    ServicePriceResult resolve(String planUid, ServiceKind kind,
                               String serviceUid, String currency);
}
