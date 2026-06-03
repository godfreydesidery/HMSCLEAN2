package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.ServicePrice;
import com.otapp.hmis.masterdata.domain.ServicePriceRepository;
import com.otapp.hmis.masterdata.lookup.PriceLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage-tier implementation of {@link PriceLookup} (build-spec §2.2, CR-04).
 *
 * <p>This bean is package-private in {@code masterdata.application}. Other modules consume
 * the {@link PriceLookup} interface from {@code masterdata.lookup} — they never reference
 * this class directly (Spring Modulith named-interface contract, ADR-0008).
 *
 * <h2>Resolution order (storage primitive — NOT the legacy billing engine)</h2>
 * <ol>
 *   <li>If {@code planUid != null}: query for a covered insurance row
 *       {@code (plan_uid=planUid, kind, service_uid match, currency, covered=TRUE)}.
 *       Found → return it.</li>
 *   <li>Cash fallback: query for {@code (plan_uid IS NULL, kind, service_uid match, currency)}.
 *       Found → return cash row.</li>
 *   <li>Neither → throw {@link ServicePriceNotFoundException} → HTTP 422
 *       {@code urn:hmis:error:service-price-not-found}.</li>
 * </ol>
 *
 * <p><b>service_uid match rule:</b> for {@link ServiceKind#REGISTRATION}, service_uid IS NULL
 * (plan-only keyed — CR-18). For all other kinds, service_uid = the given value.
 *
 * <p><b>DO NOT add any billing logic here.</b> The ward referral-override + top-up split
 * (PatientServiceImpl.java:1795-1966), the per-service not-covered fallback asymmetry
 * (consultation hard-fail at :599-601; lab/rad/proc/med cash-VERIFIED for inpatients only;
 * registration silent-UNPAID), and the two-step cash-first then insurance-override bill
 * construction are ALL documented in build-spec §2.3 as the BILLING increment's
 * responsibility. This method is exclusively a row lookup.
 *
 * <p>{@code active}, {@code minAmount}, {@code maxAmount}, and {@code currency} on the
 * returned row are inert in this method — they are passed through to the result record
 * unchanged (CR-11, CR-04 extract item 6).
 */
@Service
@RequiredArgsConstructor
class PriceLookupImpl implements PriceLookup {

    private final ServicePriceRepository repository;

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@code @Transactional(readOnly = true)} — this is a read-only query; no
     * writes occur. The transaction boundary ensures a consistent read even when called
     * from a non-transactional context (e.g. the REST controller path in
     * {@code ServicePriceController}).
     */
    @Override
    @Transactional(readOnly = true)
    public ServicePriceResult resolve(String planUid, ServiceKind kind,
                                      String serviceUid, String currency) {
        // Step 1 — covered insurance hit (only when planUid is supplied)
        if (planUid != null) {
            var hit = repository.findCoveredInsuranceRow(planUid, kind, serviceUid, currency);
            if (hit.isPresent()) {
                return toResult(hit.get());
            }
        }

        // Step 2 — cash fallback (plan_uid IS NULL)
        var cash = repository.findCashRow(kind, serviceUid, currency);
        if (cash.isPresent()) {
            return toResult(cash.get());
        }

        // Step 3 — neither found
        throw new ServicePriceNotFoundException(planUid, kind, serviceUid, currency);
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    private static ServicePriceResult toResult(ServicePrice sp) {
        return new ServicePriceResult(
                sp.getAmount(),
                sp.isCovered(),
                sp.getPlanUid(),
                sp.getKind(),
                sp.getServiceUid(),
                sp.getCurrency(),
                sp.getMinAmount(),
                sp.getMaxAmount());
    }
}
