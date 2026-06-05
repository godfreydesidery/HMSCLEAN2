package com.otapp.hmis.inpatient.web;

import com.otapp.hmis.inpatient.application.WardDayAccrualJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual operations trigger for the ward-day accrual job (ADR-0018 §Implementation-notes 6,
 * inc-07 07c-ii).
 *
 * <p><strong>NET-NEW ops hook</strong> (NOT a parity assertion — the legacy accrual had no HTTP
 * endpoint; it was an in-process polling Thread). Allows an administrator to run the accrual sweep
 * on demand for operational recovery (e.g. after a missed nightly run). Calls the SAME
 * {@link WardDayAccrualJob#runOnce()} the scheduler calls; it does not bypass ShedLock semantics for
 * the scheduled path (this manual call is a direct invocation and writes its own job_run_log row).
 *
 * <p>Gated by {@code ADMIN-ACCESS} (the seeded admin privilege) — operational, admin-only.
 */
@RestController
@RequestMapping("/api/v1/ops/jobs")
@RequiredArgsConstructor
@Tag(name = "Ops — Scheduled Jobs", description = "Manual triggers for background jobs (admin-only, inc-07 07c-ii)")
public class WardAccrualOpsController {

    private final WardDayAccrualJob wardDayAccrualJob;

    /**
     * Manually trigger the ward-day accrual sweep (admin-only operational recovery).
     *
     * @return the number of admissions that accrued a new ward-day
     */
    @PostMapping("/ward-accrual/trigger")
    @PreAuthorize("hasAuthority('ADMIN-ACCESS')")
    @Operation(summary = "Manually trigger the ward-day accrual sweep (net-new ops hook, ADR-0018)")
    public ResponseEntity<Map<String, Object>> triggerWardAccrual() {
        int accrued = wardDayAccrualJob.runOnce();
        return ResponseEntity.ok(Map.of("job", "ward-accrual", "accrued", accrued));
    }
}
