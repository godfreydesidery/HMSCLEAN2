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
import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link ServicePrice} upsert, delete and lookup
 * (build-spec §2.1, §endpoints, §3, AC-1, AC-5).
 *
 * <h2>Upsert semantics (RF-1)</h2>
 * <ul>
 *   <li>If NO row with the same composite key {@code (plan_uid, kind, service_uid, currency)}
 *       exists → INSERT; the controller returns 201 Created + Location.</li>
 *   <li>If a row ALREADY EXISTS for that key → UPDATE its {@code amount}, {@code covered},
 *       {@code min_amount}, {@code max_amount}; the controller returns 200 OK.</li>
 * </ul>
 * This restores the legacy per-kind {@code update_*_price_by_insurance} /
 * {@code change_*_coverage} capability (InsurancePlanResource.java multiple methods).
 *
 * <h2>Price/coverage coupling (RF-2)</h2>
 * Reproduces the legacy write rule from
 * {@code InsurancePlanResource.java:274-279} (mirrored across all 7 service kinds):
 * <ul>
 *   <li>{@code amount < 0} → reject 400 (VALIDATION).</li>
 *   <li>{@code amount == 0} → force {@code covered = false} (override caller-supplied value).</li>
 *   <li>{@code amount > 0} → keep the caller-supplied {@code covered}.</li>
 * </ul>
 *
 * <p>Duplicate detection pre-checks via JPQL before inserting so that insert-vs-update
 * branching is clean and avoids relying on catching a DB constraint violation.
 * The COALESCE semantics used by the JPQL in
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

    private static final String ENTITY_TYPE = "masterdata.ServicePrice";

    private final ServicePriceRepository repository;
    private final ServicePriceMapper mapper;
    private final AuditRecorder auditRecorder;
    private final PriceLookup priceLookup;

    /**
     * Upsert result indicating whether a row was created (INSERT) or updated (UPDATE).
     */
    public enum UpsertOutcome { CREATED, UPDATED }

    /**
     * Upsert carrier — wraps the DTO with whether the row was newly inserted or updated.
     */
    public record UpsertResult(ServicePriceDto dto, UpsertOutcome outcome) {}

    /**
     * True upsert: INSERT on a new composite key, UPDATE on an existing one.
     *
     * <p>RF-2 price/coverage coupling is applied before persisting:
     * {@code amount < 0} → 400; {@code amount == 0} → forces {@code covered=false}.
     *
     * @return a {@link UpsertResult} carrying the DTO and whether it was CREATED or UPDATED
     */
    @Transactional
    public UpsertResult upsert(ServicePriceRequest request) {
        String currency = request.currency() != null ? request.currency() : "TZS";

        // RF-2: price/coverage coupling (legacy InsurancePlanResource.java:274-279)
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidServicePriceAmountException(
                    "Invalid Price value. Price should not be less than zero");
        }
        // amount == 0 → force covered=false regardless of caller input (RF-2)
        boolean effectiveCovered = request.amount().compareTo(BigDecimal.ZERO) != 0
                && request.covered();

        Optional<ServicePrice> existing = repository.findByCompositeKey(
                request.planUid(), request.kind(), request.serviceUid(), currency);

        if (existing.isPresent()) {
            // UPDATE path — row with this composite key already exists
            ServicePrice price = existing.get();
            price.update(
                    price.getPlanUid(),
                    price.getKind(),
                    price.getServiceUid(),
                    price.getCurrency(),
                    request.amount(),
                    effectiveCovered,
                    request.minAmount(),
                    request.maxAmount(),
                    price.isActive());
            auditRecorder.record(ENTITY_TYPE, price.getUid(), AuditAction.UPDATE);
            return new UpsertResult(mapper.toDto(price), UpsertOutcome.UPDATED);
        }

        // INSERT path — new composite key
        ServicePrice price = new ServicePrice(
                request.planUid(),
                request.kind(),
                request.serviceUid(),
                currency,
                request.amount(),
                effectiveCovered,
                request.minAmount(),
                request.maxAmount(),
                request.active());
        repository.save(price);
        auditRecorder.record(ENTITY_TYPE, price.getUid(), AuditAction.CREATE);
        return new UpsertResult(mapper.toDto(price), UpsertOutcome.CREATED);
    }

    /**
     * Delete a {@code service_prices} row by uid.  Idempotent: if the uid is not found,
     * returns silently (no exception — callers treat a second DELETE as already done).
     * Audits DELETE when the row existed.
     */
    @Transactional
    public void delete(String uid) {
        repository.findByUid(uid).ifPresent(price -> {
            repository.delete(price);
            auditRecorder.record(ENTITY_TYPE, uid, AuditAction.DELETE);
        });
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

    // -------------------------------------------------------------------------
    // Typed exception — RF-2
    // -------------------------------------------------------------------------

    /**
     * Thrown when {@code amount < 0} on the ServicePrice write path.
     * Maps to HTTP 400 via {@link ErrorCode#VALIDATION}.
     *
     * <p>Legacy: {@code InvalidOperationException("Invalid Price value. Price should not be less
     * than zero")} — InsurancePlanResource.java:276-278, mirrored across all 7 service kinds.
     */
    public static final class InvalidServicePriceAmountException extends HmisException {
        public InvalidServicePriceAmountException(String detail) {
            super(ErrorCode.VALIDATION, detail);
        }
    }
}
