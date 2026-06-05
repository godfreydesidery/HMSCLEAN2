package com.otapp.hmis.inpatient.web;

import com.otapp.hmis.clinical.api.ConsumableChartView;
import com.otapp.hmis.inpatient.application.ConsumableChartService;
import com.otapp.hmis.inpatient.application.dto.ConsumableChartRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for inpatient consumable chart operations (inc-07 07c-i).
 *
 * <p>Base path: {@code /api/v1/inpatient/admissions/{admissionUid}/consumable-charts}.
 * Three operations: POST (issue), GET (list), DELETE (cancel within 24h).
 *
 * <p><strong>Authorization (ADR-0006):</strong> {@code isAuthenticated()} — deny-by-default.
 *
 * <p>The admission-IN-PROCESS gate, consumable-registered guard, medicine-exists guard, and
 * stock-decrement seam are all orchestrated by {@link ConsumableChartService}.
 *
 * <p>Legacy citation: PatientServiceImpl.java:2250-2475 (savePatientConsumableChart);
 * PatientResource.java:3035-3088 (deleteConsumableChart).
 * inc-07 07c-i / CR-07-consumable-stock / CR-07-Q11 / CR-07-Q13-billing-display.
 */
@RestController
@RequestMapping("/api/v1/inpatient/admissions/{admissionUid}/consumable-charts")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Inpatient Consumable Charts",
        description = "Inpatient consumable issue: record/list/cancel within 24h (inc-07 07c-i)")
public class ConsumableChartController {

    private final ConsumableChartService consumableChartService;
    private final BusinessDayService businessDayService;

    /**
     * Record a consumable chart entry for an IN-PROCESS admission.
     *
     * <p>Creates a MEDICINE bill (billItem="Medication", description="Consumable: name"),
     * persists the chart, and decrements pharmacy stock.
     */
    @PostMapping
    @Operation(summary = "Record a consumable chart entry (MEDICINE bill + stock decrement)")
    public ResponseEntity<ConsumableChartView> saveConsumableChart(
            @PathVariable String admissionUid,
            @Valid @RequestBody ConsumableChartRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        ConsumableChartView view = consumableChartService.recordConsumableChart(
                admissionUid, req, buildCtx(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** List all consumable charts for an admission, oldest first. */
    @GetMapping
    @Operation(summary = "List consumable charts for an admission")
    public List<ConsumableChartView> listConsumableCharts(@PathVariable String admissionUid) {
        return consumableChartService.findConsumableCharts(admissionUid);
    }

    /**
     * Delete a consumable chart within the 24-hour window.
     *
     * <p>Reverses the MEDICINE bill (credit note if paid), and restores pharmacy stock.
     *
     * <p><strong>CR-07-Q11 FIXES applied (anti-regression):</strong>
     * FIX #2 — credit note reference is "Canceled consumable" (not "Canceled lab test");
     * FIX #3 — parent invoice deleted ONLY when truly empty (real {@code isEmpty()} check).
     */
    @DeleteMapping("/{chartUid}")
    @Operation(summary = "Delete a consumable chart within 24h (billing reversal + stock restore)")
    public ResponseEntity<Void> deleteConsumableChart(
            @PathVariable String admissionUid,
            @PathVariable String chartUid,
            @AuthenticationPrincipal Jwt jwt) {
        consumableChartService.deleteConsumableChart(admissionUid, chartUid, buildCtx(jwt));
        return ResponseEntity.noContent().build();
    }

    private TxAuditContext buildCtx(Jwt jwt) {
        String actor = jwt != null ? jwt.getSubject() : "anonymous";
        String dayUid = businessDayService.currentUid();
        return new TxAuditContext(dayUid, Instant.now(), actor);
    }
}
