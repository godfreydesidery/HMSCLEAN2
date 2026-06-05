package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.inpatient.domain.Admission;
import com.otapp.hmis.inpatient.domain.AdmissionBed;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.masterdata.lookup.WardBedView;
import com.otapp.hmis.masterdata.lookup.WardLookup;
import com.otapp.hmis.masterdata.lookup.WardTypeView;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-admission ward-day accrual, run in its OWN transaction (inc-07 07c-ii, ADR-0018 JOB-001).
 *
 * <p><strong>Why a separate bean:</strong> {@code @Transactional(REQUIRES_NEW)} is honoured only
 * when the method is invoked through the Spring proxy. The sweep ({@link WardAccrualService}) is a
 * different bean and calls {@link #accrueOne} through the injected proxy, so each admission really
 * does run in its own transaction. (A self-invocation from inside {@code WardAccrualService} would
 * bypass the proxy and silently run inside the caller's transaction — the original 07c-ii defect.)
 *
 * <p><strong>Fault isolation (ADR-0018 §6):</strong> because each admission commits/rolls back
 * independently, one admission's failure does not abort the rest of the sweep. The
 * {@code openedAt = now} reset on each accrual is the natural idempotency key — a second run on the
 * same day finds the freshly-opened bed &lt; 24h old and no-ops.
 *
 * <p>Reproduces the per-admission body of {@code UpdatePatient.java:264-340}.
 */
@Service
@RequiredArgsConstructor
public class WardAccrualOneTx {

    private static final Logger log = LoggerFactory.getLogger(WardAccrualOneTx.class);

    private static final String OPENED = "OPENED";
    private static final List<AdmissionStatus> ACCRUAL_STATUSES =
            List.of(AdmissionStatus.IN_PROCESS, AdmissionStatus.STOPPED);
    private static final String AUDIT_TYPE = "inpatient.AdmissionBed";

    private final AdmissionRepository admissionRepository;
    private final AdmissionBedRepository admissionBedRepository;
    private final WardLookup wardLookup;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;
    private final BusinessDayService businessDayService;

    /**
     * Accrue a single admission in its OWN transaction (REQUIRES_NEW — fault isolation).
     * Reproduces the per-admission body of UpdatePatient.java:264-340.
     *
     * @return {@code true} if a new ward-day bill + bed were created; {@code false} on a no-op
     *         (bed &lt; 24h old, no OPENED bed, or a de-dup skip)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean accrueOne(String admissionUid, Instant now) {
        Admission adm = admissionRepository.findByUid(admissionUid).orElse(null);
        if (adm == null || !ACCRUAL_STATUSES.contains(adm.getStatus())) {
            return false;
        }
        List<AdmissionBed> openedBeds =
                admissionBedRepository.findAllByAdmissionUidAndStatus(admissionUid, OPENED);

        // De-dup branch (UpdatePatient.java:271-282): >1 OPENED -> close the extras, skip accrual.
        if (openedBeds.size() > 1) {
            for (AdmissionBed b : openedBeds) {
                b.close(now);
                admissionBedRepository.save(b);
                auditRecorder.record(AUDIT_TYPE, b.getUid(), AuditAction.UPDATE, systemActor());
            }
            log.warn("Ward-accrual de-dup: closed {} duplicate OPENED beds for admission uid={}",
                    openedBeds.size(), admissionUid);
            return false;
        }
        if (openedBeds.isEmpty()) {
            return false;
        }

        AdmissionBed openBed = openedBeds.get(0);
        long hours = ChronoUnit.HOURS.between(openBed.getOpenedAt(), now);
        if (hours < 24) {
            return false; // not yet due
        }

        // Resolve the ward type + cash price via the masterdata read seam (Option B keys on wardType).
        WardBedView bed = wardLookup.findBedByUid(adm.getWardBedUid())
                .orElseThrow(() -> new IllegalStateException(
                        "Ward bed not found for accrual: " + adm.getWardBedUid()));
        WardTypeView wardType = wardLookup.findWardTypeByUid(bed.wardTypeUid())
                .orElseThrow(() -> new IllegalStateException(
                        "Ward type not found for accrual: " + bed.wardTypeUid()));
        BigDecimal wardPrice = wardType.price();

        TxAuditContext ctx = new TxAuditContext(businessDayService.currentUid(), now, systemActor());

        // CLOSE the current bed (UpdatePatient.java:294-296).
        openBed.close(now);
        admissionBedRepository.save(openBed);

        // NEW VERIFIED (cash) / COVERED (insurance, Option B + top-up) ward-bed bill
        // (UpdatePatient.java:304-325 / the insurance branch). billItem "Bed", desc "Ward Bed / Room".
        String billUid = billingCommands.recordWardAccrual(
                adm.getPatientUid(), admissionUid, bed.wardTypeUid(), wardPrice,
                adm.getInsurancePlanUid(), adm.getMembershipNo(), ctx);

        // NEW OPENED bed resetting openedAt = now (UpdatePatient.java:327-334) — the rolling reset.
        AdmissionBed newBed = new AdmissionBed(
                admissionUid, adm.getWardBedUid(), adm.getPatientUid(), billUid, now);
        admissionBedRepository.save(newBed);
        auditRecorder.record(AUDIT_TYPE, newBed.getUid(), AuditAction.CREATE, systemActor());

        log.debug("Ward-accrual: admission uid={} accrued ward-day bill uid={} (rolled bed)",
                admissionUid, billUid);
        return true;
    }

    private static String systemActor() {
        return "system:ward-accrual";
    }
}
