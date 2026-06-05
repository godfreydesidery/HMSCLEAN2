package com.otapp.hmis.inpatient.web;

import com.otapp.hmis.clinical.api.DressingChartView;
import com.otapp.hmis.clinical.api.NursingCarePlanView;
import com.otapp.hmis.clinical.api.NursingChartView;
import com.otapp.hmis.clinical.api.NursingProgressNoteView;
import com.otapp.hmis.clinical.api.PrescriptionChartView;
import com.otapp.hmis.inpatient.application.NursingChartService;
import com.otapp.hmis.inpatient.application.dto.DosingNoteRequest;
import com.otapp.hmis.inpatient.application.dto.DressingChartRequest;
import com.otapp.hmis.inpatient.application.dto.NursingCarePlanRequest;
import com.otapp.hmis.inpatient.application.dto.NursingChartRequest;
import com.otapp.hmis.inpatient.application.dto.ProgressNoteRequest;
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
 * REST controller for the inpatient nurse-facing charting surface (inc-07 07b).
 *
 * <p>Base path: {@code /api/v1/inpatient/admissions/{admissionUid}}. Five chart types, each with
 * save (POST) + list (GET) + delete-within-24h (DELETE): nursing charts, nursing care plans,
 * progress notes, dressing charts (a billing record), and free-text dosing notes (NOT MAR).
 *
 * <p><strong>Authorization (ADR-0006):</strong> {@code isAuthenticated()} — net-new deny-by-default
 * hardening (the legacy nursing surface was entirely UNGATED; this is NOT a parity assertion).
 *
 * <p>The admission-IN-PROCESS gate (verbatim legacy messages) + the dressing-registered guard run
 * in {@link NursingChartService}; the clinical-owned persist runs via {@code clinical :: api}.
 *
 * <p>Legacy citations: PatientServiceImpl.java:2540-2698 (chart saves + gates),
 * PatientResource.java:3135-3138 (24h delete window). inc-07 07b.
 */
@RestController
@RequestMapping("/api/v1/inpatient/admissions/{admissionUid}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Inpatient Nursing Charts",
        description = "Nurse-facing charting surface: vitals/nursing/care-plan/progress/dressing/dosing (inc-07 07b)")
public class NursingChartController {

    private final NursingChartService nursingChartService;
    private final BusinessDayService businessDayService;

    // ---- Nursing chart ----

    @PostMapping("/nursing-charts")
    @Operation(summary = "Record a nursing observation chart entry")
    public ResponseEntity<NursingChartView> saveNursingChart(
            @PathVariable String admissionUid,
            @Valid @RequestBody NursingChartRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        NursingChartView v = nursingChartService.recordNursingChart(admissionUid, req, buildCtx(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @GetMapping("/nursing-charts")
    @Operation(summary = "List nursing chart entries for an admission")
    public List<NursingChartView> listNursingCharts(@PathVariable String admissionUid) {
        return nursingChartService.findNursingCharts(admissionUid);
    }

    @DeleteMapping("/nursing-charts/{chartUid}")
    @Operation(summary = "Delete a nursing chart entry (within 24h)")
    public ResponseEntity<Void> deleteNursingChart(
            @PathVariable String admissionUid, @PathVariable String chartUid,
            @AuthenticationPrincipal Jwt jwt) {
        nursingChartService.deleteNursingChart(chartUid, buildCtx(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- Care plan ----

    @PostMapping("/nursing-care-plans")
    @Operation(summary = "Record a nursing care plan entry")
    public ResponseEntity<NursingCarePlanView> saveCarePlan(
            @PathVariable String admissionUid,
            @Valid @RequestBody NursingCarePlanRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        NursingCarePlanView v = nursingChartService.recordCarePlan(admissionUid, req, buildCtx(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @GetMapping("/nursing-care-plans")
    @Operation(summary = "List nursing care plans for an admission")
    public List<NursingCarePlanView> listCarePlans(@PathVariable String admissionUid) {
        return nursingChartService.findCarePlans(admissionUid);
    }

    @DeleteMapping("/nursing-care-plans/{carePlanUid}")
    @Operation(summary = "Delete a nursing care plan (within 24h)")
    public ResponseEntity<Void> deleteCarePlan(
            @PathVariable String admissionUid, @PathVariable String carePlanUid,
            @AuthenticationPrincipal Jwt jwt) {
        nursingChartService.deleteCarePlan(carePlanUid, buildCtx(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- Progress note ----

    @PostMapping("/progress-notes")
    @Operation(summary = "Record a nursing progress note")
    public ResponseEntity<NursingProgressNoteView> saveProgressNote(
            @PathVariable String admissionUid,
            @Valid @RequestBody ProgressNoteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        NursingProgressNoteView v = nursingChartService.recordProgressNote(admissionUid, req, buildCtx(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @GetMapping("/progress-notes")
    @Operation(summary = "List progress notes for an admission")
    public List<NursingProgressNoteView> listProgressNotes(@PathVariable String admissionUid) {
        return nursingChartService.findProgressNotes(admissionUid);
    }

    @DeleteMapping("/progress-notes/{noteUid}")
    @Operation(summary = "Delete a progress note (within 24h)")
    public ResponseEntity<Void> deleteProgressNote(
            @PathVariable String admissionUid, @PathVariable String noteUid,
            @AuthenticationPrincipal Jwt jwt) {
        nursingChartService.deleteProgressNote(noteUid, buildCtx(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- Dressing chart (billing record) ----

    @PostMapping("/dressing-charts")
    @Operation(summary = "Record a dressing chart (creates a PROCEDURE bill)")
    public ResponseEntity<DressingChartView> saveDressingChart(
            @PathVariable String admissionUid,
            @Valid @RequestBody DressingChartRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        DressingChartView v = nursingChartService.recordDressingChart(admissionUid, req, buildCtx(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @GetMapping("/dressing-charts")
    @Operation(summary = "List dressing charts for an admission")
    public List<DressingChartView> listDressingCharts(@PathVariable String admissionUid) {
        return nursingChartService.findDressingCharts(admissionUid);
    }

    @DeleteMapping("/dressing-charts/{chartUid}")
    @Operation(summary = "Delete a dressing chart (within 24h; billing reversal)")
    public ResponseEntity<Void> deleteDressingChart(
            @PathVariable String admissionUid, @PathVariable String chartUid,
            @AuthenticationPrincipal Jwt jwt) {
        nursingChartService.deleteDressingChart(chartUid, buildCtx(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- Dosing note (free-text PatientPrescriptionChart — Q1, NOT MAR) ----

    @PostMapping("/dosing-notes")
    @Operation(summary = "Record a free-text dosing note against a GIVEN prescription")
    public ResponseEntity<PrescriptionChartView> saveDosingNote(
            @PathVariable String admissionUid,
            @Valid @RequestBody DosingNoteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        PrescriptionChartView v = nursingChartService.recordDosingNote(admissionUid, req, buildCtx(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @GetMapping("/dosing-notes")
    @Operation(summary = "List dosing notes for an admission")
    public List<PrescriptionChartView> listDosingNotes(@PathVariable String admissionUid) {
        return nursingChartService.findDosingNotes(admissionUid);
    }

    @DeleteMapping("/dosing-notes/{chartUid}")
    @Operation(summary = "Delete a dosing note (within 24h)")
    public ResponseEntity<Void> deleteDosingNote(
            @PathVariable String admissionUid, @PathVariable String chartUid,
            @AuthenticationPrincipal Jwt jwt) {
        nursingChartService.deleteDosingNote(chartUid, buildCtx(jwt));
        return ResponseEntity.noContent().build();
    }

    private TxAuditContext buildCtx(Jwt jwt) {
        String actor = jwt != null ? jwt.getSubject() : "anonymous";
        String dayUid = businessDayService.currentUid();
        return new TxAuditContext(dayUid, Instant.now(), actor);
    }
}
