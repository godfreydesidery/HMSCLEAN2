package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.domain.WardBed;
import com.otapp.hmis.masterdata.domain.WardBedRepository;
import com.otapp.hmis.masterdata.lookup.WardBedClaim;
import com.otapp.hmis.shared.error.NotFoundException;
import com.otapp.hmis.shared.error.StaleEntityException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link WardBedClaim} (inc-07 SEAM-1, ADR-0008 §1).
 *
 * <p>This bean is package-private in {@code masterdata.application}. Callers depend on the
 * {@link WardBedClaim} interface from {@code masterdata.lookup} — they never reference this
 * class directly (Spring Modulith named-interface contract, ADR-0008 §1).
 *
 * <p><strong>First-of-kind mutating named-interface implementation.</strong> All three methods
 * run inside the CALLER's transaction ({@code Propagation.REQUIRED}) so that the bed status
 * change and the admission row creation commit (or roll back) together atomically.
 * NOT {@code @PreAuthorize}-gated — authorization is enforced at the caller's REST edge.
 *
 * <p>{@code claimBed} acquires a PESSIMISTIC_WRITE lock via
 * {@link WardBedRepository#findByUidForUpdate}. The lock serialises concurrent bed-claim
 * attempts; the loser observes a non-EMPTY status under the lock and receives a
 * {@link StaleEntityException} (HTTP 409 — retriable). Net-new hardening over legacy, which
 * had no row lock and silently oversold beds (CR-07-Q3, ADR-0017 ratified).
 *
 * <p>Status literals are free-text strings ({@code "EMPTY"} / {@code "WAITING"} /
 * {@code "OCCUPIED"}) — NO enum. Legacy WardBed.java:43 (CR-16).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>claimBed guard: PatientServiceImpl.java:1703-1711</li>
 *   <li>occupyBed: doAdmission full-cover branch + PatientBillResource.java:352-365</li>
 *   <li>freeBed: get_discharge/referral/deceased_summary frees WardBed</li>
 * </ul>
 *
 * <p>inc-07 SEAM-1 / CR-07-Q3 / ADR-0017 ratified.
 */
@Service
@RequiredArgsConstructor
class WardBedClaimImpl implements WardBedClaim {

    private static final String STATUS_EMPTY    = "EMPTY";
    private static final String STATUS_WAITING  = "WAITING";
    private static final String STATUS_OCCUPIED = "OCCUPIED";

    private final WardBedRepository wardBedRepository;

    /**
     * {@inheritDoc}
     *
     * <p>Acquires PESSIMISTIC_WRITE on the WardBed row via
     * {@link WardBedRepository#findByUidForUpdate}. Re-reads under the lock and guards:
     * {@code active == true && "EMPTY".equals(status)}. On success transitions EMPTY → WAITING.
     * On guard failure throws {@link StaleEntityException} (409 / STALE_ENTITY — bed-claim race
     * loser or inactive bed). Legacy: PatientServiceImpl.java:1703-1711.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void claimBed(String wardBedUid) {
        WardBed bed = wardBedRepository.findByUidForUpdate(wardBedUid)
                .orElseThrow(() -> new NotFoundException("WardBed not found: " + wardBedUid));

        // Guard under the lock — re-read state is authoritative (CR-07-Q3, ADR-0017)
        if (!bed.isActive() || !STATUS_EMPTY.equals(bed.getStatus())) {
            throw new StaleEntityException(
                    "Bed is not available for claim (status=" + bed.getStatus()
                    + ", active=" + bed.isActive() + "); reload and retry");
        }

        bed.changeStatus(STATUS_WAITING);
        wardBedRepository.save(bed);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Payment-driven activation: transitions status → OCCUPIED. Idempotent if already
     * OCCUPIED (a retry after a payment callback should not fail).
     * Legacy: doAdmission full-cover branch + PatientBillResource.java:352-365.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void occupyBed(String wardBedUid) {
        WardBed bed = wardBedRepository.findByUid(wardBedUid)
                .orElseThrow(() -> new NotFoundException("WardBed not found: " + wardBedUid));

        // Idempotent: already OCCUPIED is a no-op (payment-retry safety)
        if (!STATUS_OCCUPIED.equals(bed.getStatus())) {
            bed.changeStatus(STATUS_OCCUPIED);
            wardBedRepository.save(bed);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Discharge/sign-out: transitions status → EMPTY unconditionally.
     * Legacy: get_discharge_summary / get_referral_summary / get_deceased_summary free the bed.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void freeBed(String wardBedUid) {
        WardBed bed = wardBedRepository.findByUid(wardBedUid)
                .orElseThrow(() -> new NotFoundException("WardBed not found: " + wardBedUid));

        bed.changeStatus(STATUS_EMPTY);
        wardBedRepository.save(bed);
    }
}
