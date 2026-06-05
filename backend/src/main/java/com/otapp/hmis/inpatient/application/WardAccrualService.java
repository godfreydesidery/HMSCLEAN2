package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.inpatient.domain.Admission;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ward-day accrual — the parity oracle (inc-07 07c-ii, CR-07-Q2, ADR-0018 JOB-001).
 *
 * <p><strong>EXTRACTED legacy parity (the TOTAL/STATUS semantics):</strong> reproduces
 * {@code UpdatePatient.java:258-340}. For each {@code IN_PROCESS}/{@code STOPPED} admission whose
 * single OPENED {@code AdmissionBed} is &ge; 24h old
 * ({@code ChronoUnit.HOURS.between(openedAt, now) >= 24}), CLOSE that bed and OPEN a new one
 * ({@code openedAt = now}) carrying a NEW ward-bed {@code PatientBill} at {@code WardType.price}
 * (billItem {@code "Bed"}, desc {@code "Ward Bed / Room"}). The accrued bill is {@code VERIFIED}
 * (cash) / {@code COVERED} (insurance, ward-type-keyed Option B per CR-07-WARD-INS-PRICE, with the
 * top-up split). The admission-time bill was {@code UNPAID}; accrued bills are {@code VERIFIED}.
 * De-dup branch (legacy :271-282): if &gt;1 OPENED beds exist, close the extras and skip.
 * Total over a stay = {@code (1 + N) × WardType.price} where N = completed rolling-24h windows.
 *
 * <p>The per-admission accrual body lives in {@link WardAccrualOneTx#accrueOne}, a SEPARATE bean so
 * its {@code @Transactional(REQUIRES_NEW)} is honoured (proxy invocation, not a self-call).
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
 * OWN transaction ({@code REQUIRES_NEW} on {@link WardAccrualOneTx#accrueOne}) so a failure for one
 * admission does not abort the rest. The sweep itself is NON-transactional (read-only enumeration +
 * a fan-out of independent child transactions) — wrapping it in an outer transaction would defeat
 * the per-admission isolation and force every write through one read-only boundary.
 *
 * <p>Legacy citations: UpdatePatient.java:258-340 (accrual loop), :291-292 (24h check),
 * :294-334 (close + new VERIFIED bill + new OPENED bed). inc-07 07c-ii.
 */
@Service
@RequiredArgsConstructor
public class WardAccrualService {

    private static final Logger log = LoggerFactory.getLogger(WardAccrualService.class);

    private static final List<AdmissionStatus> ACCRUAL_STATUSES =
            List.of(AdmissionStatus.IN_PROCESS, AdmissionStatus.STOPPED);

    private final AdmissionRepository admissionRepository;
    private final WardAccrualOneTx wardAccrualOneTx;

    /**
     * Sweep all eligible admissions and accrue a ward-day where due. Returns the number of
     * admissions that produced a new accrual (for the {@code job_run_log.records_affected}).
     *
     * <p>NON-transactional at the sweep level — the only sweep-level access is a read
     * ({@code findAllByStatusIn}); each admission's accrual runs in its own {@code REQUIRES_NEW}
     * transaction via {@link WardAccrualOneTx#accrueOne}. A {@code RuntimeException} from one
     * admission is logged and swallowed so the rest of the sweep proceeds (ADR-0018 JOB-001).
     *
     * @param now the accrual reference instant (the cron's fire time / the manual trigger's now)
     * @return the count of admissions that accrued a new ward-day
     */
    public int accrueWardDay(Instant now) {
        List<Admission> admissions = admissionRepository.findAllByStatusIn(ACCRUAL_STATUSES);
        int accrued = 0;
        for (Admission adm : admissions) {
            try {
                if (wardAccrualOneTx.accrueOne(adm.getUid(), now)) {
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
}
