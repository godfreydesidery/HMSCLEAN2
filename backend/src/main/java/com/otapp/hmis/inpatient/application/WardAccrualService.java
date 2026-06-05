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
 * Ward-day accrual — the parity oracle (inc-07 07c-ii, CR-07-Q2, ADR-0018 JOB-001).
 *
 * <p><strong>EXTRACTED legacy parity (the TOTAL/STATUS semantics):</strong> reproduces
 * {@code UpdatePatient.java:258-340}. For each {@code IN_PROCESS}/{@code STOPPED} admission whose
 * single OPENED {@link AdmissionBed} is &ge; 24h old
 * ({@code ChronoUnit.HOURS.between(openedAt, now) >= 24}), CLOSE that bed and OPEN a new one
 * ({@code openedAt = now}) carrying a NEW ward-bed {@code PatientBill} at {@code WardType.price}
 * (billItem {@code "Bed"}, desc {@code "Ward Bed / Room"}). The accrued bill is {@code VERIFIED}
 * (cash) / {@code COVERED} (insurance, ward-type-keyed Option B per CR-07-WARD-INS-PRICE, with the
 * top-up split). The admission-time bill was {@code UNPAID}; accrued bills are {@code VERIFIED}.
 * De-dup branch (legacy :271-282): if &gt;1 OPENED beds exist, close the extras and skip.
 * Total over a stay = {@code (1 + N) × WardType.price} where N = completed rolling-24h windows.
 *
 * <p><strong>NET-NEW trigger (the MECHANISM only):</strong> the always-running 5-minute
 * {@code UpdatePatient} polling Thread is replaced by a {@code @Scheduled} + ShedLock calendar-night
 * cron ({@link WardDayAccrualJob}). The owner accepted the documented midnight-vs-rolling-24h timing
 * variance (CR-07-Q2). The chained count N under the cron can differ from the legacy
 * {@code openedAt}-keyed rolling count; the golden-master anchors the AMOUNT to the legacy
 * elapsed-24h chained total (ADR-0018 §Decision JOB-001 corrected anchor), while the cron's
 * per-calendar-night idempotency is validated as net-new behaviour.
 *
 * <p><strong>Idempotency / fault isolation (ADR-0018 §6):</strong> each admission is accrued in its
 * OWN transaction ({@code REQUIRES_NEW}) so a failure for one admission does not abort the rest. The
 * {@code openedAt = now} reset on each accrual is the natural idempotency key — a second run on the
 * same day finds the freshly-opened bed &lt; 24h old and no-ops.
 *
 * <p>Legacy citations: UpdatePatient.java:258-340 (accrual loop), :291-292 (24h check),
 * :294-334 (close + new VERIFIED bill + new OPENED bed). inc-07 07c-ii.
 */
@Service
@RequiredArgsConstructor
public class WardAccrualService {

    private static final Logger log = LoggerFactory.getLogger(WardAccrualService.class);

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
     * Sweep all eligible admissions and accrue a ward-day where due. Returns the number of
     * admissions that produced a new accrual (for the {@code job_run_log.records_affected}).
     *
     * <p>Read-only at the sweep level; each admission's accrual runs in its own
     * {@code REQUIRES_NEW} transaction via {@link #accrueOne}. A {@code RuntimeException} from one
     * admission is logged and swallowed so the rest of the sweep proceeds (ADR-0018 JOB-001).
     *
     * @param now the accrual reference instant (the cron's fire time / the manual trigger's now)
     * @return the count of admissions that accrued a new ward-day
     */
    @Transactional(readOnly = true)
    public int accrueWardDay(Instant now) {
        List<Admission> admissions = admissionRepository.findAllByStatusIn(ACCRUAL_STATUSES);
        int accrued = 0;
        for (Admission adm : admissions) {
            try {
                if (accrueOne(adm.getUid(), now)) {
                    accrued++;
                }
            } catch (RuntimeException ex) {
                // Fault isolation: one admission's failure must not abort the sweep (ADR-0018).
                log.error("Ward-accrual failed for admission uid={}: {}", adm.getUid(), ex.toString());
            }
        }
        log.info("Ward-day accrual sweep complete: {} of {} admissions accrued", accrued, admissions.size());
        return accrued;
    }

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
