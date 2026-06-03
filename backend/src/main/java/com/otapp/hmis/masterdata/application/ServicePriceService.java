package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ServicePriceDto;
import com.otapp.hmis.masterdata.application.dto.ServicePriceRequest;
import com.otapp.hmis.masterdata.domain.ServicePrice;
import com.otapp.hmis.masterdata.domain.ServicePriceRepository;
import com.otapp.hmis.masterdata.lookup.PriceLookup;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.ServicePriceResult;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link ServicePrice} upsert and lookup
 * (build-spec §2.1, §endpoints, §3, AC-1, AC-5).
 *
 * <p>Duplicate detection pre-checks via JPQL before inserting so that the 409 is returned
 * cleanly, without relying on catching a DB constraint violation (which would roll back the
 * transaction). The COALESCE semantics used by the JPQL in
 * {@link ServicePriceRepository#existsByCompositeKey} match those of the unique index
 * {@code uq_service_prices_plan_kind_svc_cur} (both treat NULL planUid and NULL serviceUid
 * as a single-value bucket — AC-5).
 *
 * <p>Delegates price resolution to {@link PriceLookup} (interface, not the concrete class —
 * Spring Modulith named-interface contract). {@link PriceLookupImpl} is the implementation
 * bean but is package-private and must not be referenced outside this package.
 */
@Service
@RequiredArgsConstructor
public class ServicePriceService {

    private final ServicePriceRepository repository;
    private final ServicePriceMapper mapper;
    private final AuditRecorder auditRecorder;
    private final PriceLookup priceLookup;

    /**
     * Upsert a single {@code service_prices} row.
     *
     * <p>Returns the created DTO. Throws {@link DuplicateServicePriceException} (409) when
     * a row with the same composite key already exists (AC-5). Handles NULL planUid (cash)
     * and NULL serviceUid (REGISTRATION) correctly via the pre-check query.
     */
    @Transactional
    public ServicePriceDto upsert(ServicePriceRequest request) {
        String currency = request.currency() != null ? request.currency() : "TZS";

        // Pre-check for duplicate to produce 409 cleanly (not from a DB constraint violation)
        if (repository.existsByCompositeKey(
                request.planUid(), request.kind(), request.serviceUid(), currency)) {
            throw new DuplicateServicePriceException(
                    request.planUid(), request.kind(), request.serviceUid(), currency);
        }

        ServicePrice price = new ServicePrice(
                request.planUid(),
                request.kind(),
                request.serviceUid(),
                currency,
                request.amount(),
                request.covered(),
                request.minAmount(),
                request.maxAmount(),
                request.active());
        repository.save(price);
        auditRecorder.record("masterdata.ServicePrice", price.getUid(), AuditAction.CREATE);
        return mapper.toDto(price);
    }

    /**
     * Resolve the effective price for a service charge (delegates to PriceLookup).
     * Returns the matched row as a DTO. Throws {@link ServicePriceNotFoundException} (422)
     * when neither an insurance hit nor a cash fallback exists (AC-1).
     */
    @Transactional(readOnly = true)
    public ServicePriceResult resolve(String planUid, ServiceKind kind,
                                      String serviceUid, String currency) {
        String effectiveCurrency = currency != null ? currency : "TZS";
        return priceLookup.resolve(planUid, kind, serviceUid, effectiveCurrency);
    }

    @Transactional(readOnly = true)
    public ServicePriceDto get(String uid) {
        ServicePrice price = repository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("ServicePrice not found: " + uid));
        return mapper.toDto(price);
    }

    @Transactional(readOnly = true)
    public List<ServicePriceDto> list() {
        return mapper.toDtoList(repository.findAllByOrderByKindAscPlanUidAsc());
    }
}
