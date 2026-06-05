package com.otapp.hmis.inpatient.web;

import com.otapp.hmis.clinical.api.DeceasedNoteView;
import com.otapp.hmis.clinical.api.ReferralPlanView;
import com.otapp.hmis.inpatient.application.DispositionService;
import com.otapp.hmis.inpatient.application.dto.DeceasedNoteRequest;
import com.otapp.hmis.inpatient.application.dto.DischargePlanDto;
import com.otapp.hmis.inpatient.application.dto.DischargePlanRequest;
import com.otapp.hmis.inpatient.application.dto.ReferralPlanRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST controller for the inpatient disposition workflows: discharge, referral, deceased
 * (inc-07 07a-3).
 *
 * <p>Base path: {@code /api/v1/inpatient/admissions/{admissionUid}}
 *
 * <p><strong>Six endpoints (save + approve for each of the three dispositions):</strong>
 * <ul>
 *   <li>POST …/discharge-plan          (save — isAuthenticated)</li>
 *   <li>POST …/discharge-plan/approve  (approve — DISCHARGE-PLAN-APPROVE privilege)</li>
 *   <li>POST …/referral-plan           (save — isAuthenticated)</li>
 *   <li>POST …/referral-plan/approve   (approve — REFERRAL-PLAN-APPROVE privilege)</li>
 *   <li>POST …/deceased-note           (save — isAuthenticated)</li>
 *   <li>POST …/deceased-note/approve   (approve — DECEASED-NOTE-APPROVE privilege)</li>
 * </ul>
 *
 * <p><strong>Authorization (ADR-0006):</strong>
 * Save endpoints: {@code isAuthenticated()} (net-new hardening; legacy save endpoints were
 * ungated — PatientResource.java:5342, :5593, :5693).
 * Approve endpoints: require the corresponding APPROVE-suffixed privilege seeded in V47
 * (DISCHARGE-PLAN-APPROVE / REFERRAL-PLAN-APPROVE / DECEASED-NOTE-APPROVE).
 * These privileges encode the SoD second-approver requirement at the auth layer (CR-07-SoD).
 *
 * <p><strong>Request body pattern:</strong>
 * The approve endpoints carry a body with the plan/note UID — the uid of the specific
 * disposition record to approve. This avoids ambiguity in the rare case of re-saves.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>Discharge save+approve: PatientResource.java:5342-5390</li>
 *   <li>Referral save+approve:  PatientResource.java:5593-5685</li>
 *   <li>Deceased save+approve:  PatientResource.java:5693-5773 + :5837-5934</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inpatient/admissions/{admissionUid}")
@RequiredArgsConstructor
@Tag(name = "Inpatient Dispositions",
        description = "Discharge / referral / deceased disposition workflows (inc-07 07a-3)")
public class DispositionController {

    private final DispositionService dispositionService;
    private final BusinessDayService businessDayService;

    // =========================================================================
    // DISCHARGE
    // =========================================================================

    @PostMapping("/discharge-plan")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Save a discharge plan",
            description = "Create or update a PENDING discharge plan for the admission. "
                    + "Idempotent: re-save updates the narrative in place.",
            responses = {
                @ApiResponse(responseCode = "201", description = "Discharge plan saved (PENDING)"),
                @ApiResponse(responseCode = "404", description = "Admission not found"),
                @ApiResponse(responseCode = "422", description = "Business rule violation")
            })
    public ResponseEntity<DischargePlanDto> saveDischargePlan(
            @PathVariable String admissionUid,
            @Valid @RequestBody DischargePlanRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = buildCtx(jwt);
        DischargePlanDto dto = dispositionService.saveDischargePlan(admissionUid, request, ctx);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/uid/{uid}").buildAndExpand(dto.uid()).toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @PostMapping("/discharge-plan/approve")
    @PreAuthorize("hasAuthority('DISCHARGE-PLAN-APPROVE')")
    @Operation(
            summary = "Approve a discharge plan",
            description = "Bills-cleared gate + SoD second-approver gate, then signs out "
                    + "the admission (SIGNED-OUT), frees the bed, and closes the AdmissionBed.",
            responses = {
                @ApiResponse(responseCode = "200", description = "Discharge approved"),
                @ApiResponse(responseCode = "404", description = "Plan or admission not found"),
                @ApiResponse(responseCode = "422", description = "Bills outstanding / self-approval / business rule")
            })
    public ResponseEntity<DischargePlanDto> approveDischargePlan(
            @PathVariable String admissionUid,
            @Valid @RequestBody ApproveDispositionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = buildCtx(jwt);
        DischargePlanDto dto = dispositionService.approveDischargePlan(admissionUid, request.uid(), ctx);
        return ResponseEntity.ok(dto);
    }

    // =========================================================================
    // REFERRAL
    // =========================================================================

    @PostMapping("/referral-plan")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Save a referral plan",
            description = "Create or update a PENDING admission referral plan. "
                    + "externalMedicalProviderUid is mandatory (CR-07-Q7).",
            responses = {
                @ApiResponse(responseCode = "201", description = "Referral plan saved (PENDING)"),
                @ApiResponse(responseCode = "404", description = "Admission not found"),
                @ApiResponse(responseCode = "422", description = "Business rule violation")
            })
    public ResponseEntity<ReferralPlanView> saveReferralPlan(
            @PathVariable String admissionUid,
            @Valid @RequestBody ReferralPlanRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = buildCtx(jwt);
        ReferralPlanView view = dispositionService.saveReferralPlan(admissionUid, request, ctx);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/uid/{uid}").buildAndExpand(view.uid()).toUri();
        return ResponseEntity.created(location).body(view);
    }

    @PostMapping("/referral-plan/approve")
    @PreAuthorize("hasAuthority('REFERRAL-PLAN-APPROVE')")
    @Operation(
            summary = "Approve a referral plan",
            description = "Bills-cleared gate + SoD gate, then signs out the admission, "
                    + "frees the bed, and resets patient type to OUTPATIENT only "
                    + "(insurance plan NOT cleared — legacy asymmetry :5626 vs :5378-5381).",
            responses = {
                @ApiResponse(responseCode = "200", description = "Referral approved"),
                @ApiResponse(responseCode = "404", description = "Plan or admission not found"),
                @ApiResponse(responseCode = "422", description = "Bills outstanding / self-approval / business rule")
            })
    public ResponseEntity<ReferralPlanView> approveReferralPlan(
            @PathVariable String admissionUid,
            @Valid @RequestBody ApproveDispositionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = buildCtx(jwt);
        ReferralPlanView view = dispositionService.approveReferralPlan(admissionUid, request.uid(), ctx);
        return ResponseEntity.ok(view);
    }

    // =========================================================================
    // DECEASED
    // =========================================================================

    @PostMapping("/deceased-note")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Save a deceased note",
            description = "Create or update a PENDING deceased note. patientSummary and "
                    + "causeOfDeath are mandatory. Admission transitions to HELD and bed freed early.",
            responses = {
                @ApiResponse(responseCode = "201", description = "Deceased note saved (PENDING); admission HELD"),
                @ApiResponse(responseCode = "404", description = "Admission not found"),
                @ApiResponse(responseCode = "422",
                        description = "Missing summary/cause ('Summary and cause of death are missing') "
                                + "/ business rule")
            })
    public ResponseEntity<DeceasedNoteView> saveDeceasedNote(
            @PathVariable String admissionUid,
            @Valid @RequestBody DeceasedNoteRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = buildCtx(jwt);
        DeceasedNoteView view = dispositionService.saveDeceasedNote(admissionUid, request, ctx);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/uid/{uid}").buildAndExpand(view.uid()).toUri();
        return ResponseEntity.created(location).body(view);
    }

    @PostMapping("/deceased-note/approve")
    @PreAuthorize("hasAuthority('DECEASED-NOTE-APPROVE')")
    @Operation(
            summary = "Approve a deceased note",
            description = "Bills-cleared gate + SoD gate, then signs out the admission "
                    + "(SIGNED-OUT, no dischargedAt stamp) and sets patient type=DECEASED.",
            responses = {
                @ApiResponse(responseCode = "200", description = "Deceased note approved"),
                @ApiResponse(responseCode = "404", description = "Note or admission not found"),
                @ApiResponse(responseCode = "422", description = "Bills outstanding / self-approval / business rule")
            })
    public ResponseEntity<DeceasedNoteView> approveDeceasedNote(
            @PathVariable String admissionUid,
            @Valid @RequestBody ApproveDispositionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = buildCtx(jwt);
        DeceasedNoteView view = dispositionService.approveDeceasedNote(admissionUid, request.uid(), ctx);
        return ResponseEntity.ok(view);
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private TxAuditContext buildCtx(Jwt jwt) {
        String actor = jwt != null ? jwt.getSubject() : "anonymous";
        String dayUid = businessDayService.currentUid();
        return new TxAuditContext(dayUid, Instant.now(), actor);
    }

    /**
     * Generic approve-request body carrying the uid of the disposition record to approve.
     * Used by all three approve endpoints (discharge-plan/approve, referral-plan/approve,
     * deceased-note/approve).
     *
     * @param uid the ULID of the disposition record to approve
     */
    public record ApproveDispositionRequest(String uid) {
    }
}
