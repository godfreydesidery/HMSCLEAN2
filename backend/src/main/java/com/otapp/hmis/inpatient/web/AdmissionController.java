package com.otapp.hmis.inpatient.web;

import com.otapp.hmis.inpatient.application.AdmissionService;
import com.otapp.hmis.inpatient.application.dto.AdmissionDto;
import com.otapp.hmis.inpatient.application.dto.AdmissionRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST controller for the inpatient admission lifecycle (inc-07 07a).
 *
 * <p>Base path: {@code /api/v1/inpatient}
 *
 * <p><strong>Authorization (ADR-0006 net-new hardening):</strong>
 * The legacy doAdmission endpoint was explicitly ungated
 * ({@code //@PreAuthorize} commented out at PatientResource.java:5184).
 * Per ADR-0006 deny-by-default policy, all endpoints in the modernised system require at
 * minimum authentication. Since {@code ADMISSION-CREATE} is not seeded in V2, the gate is
 * {@code isAuthenticated()} (net-new hardening, NOT a legacy-parity assertion — labelled
 * accordingly). If the privilege is seeded in a future migration it should replace this gate.
 *
 * <p>Legacy citation: PatientResource.java:5183-5211 ({@code /patients/do_admission}).
 */
@RestController
@RequestMapping("/api/v1/inpatient")
@RequiredArgsConstructor
@Tag(name = "Inpatient", description = "Inpatient admission lifecycle (inc-07)")
public class AdmissionController {

    private final AdmissionService admissionService;
    private final BusinessDayService businessDayService;

    /**
     * Admit a patient to a bed (doAdmission).
     *
     * <p>Executes the full guard order + five-step admission sequence atomically:
     * deceased guard → already-admitted guard → bed claim → Admission(PENDING) →
     * UNPAID ward bill → AdmissionBed(OPENED) → PatientAdmittedEvent.
     *
     * <p>Authorization: {@code isAuthenticated()} (net-new hardening — ADR-0006;
     * legacy endpoint was ungated, PatientResource.java:5184).
     *
     * @param request  the admission request body
     * @param jwt      the authenticated principal (for actor attribution)
     * @return 201 Created with the new PENDING admission DTO in the body and Location header
     */
    @PostMapping("/admissions")
    @PreAuthorize("isAuthenticated()")  // net-new hardening (ADR-0006); legacy was ungated
    @Operation(
            summary = "Admit a patient to a ward bed",
            description = "Creates a PENDING admission, claims the bed (WAITING), creates the "
                    + "UNPAID ward-bed bill, and publishes PatientAdmittedEvent. "
                    + "Activation (PENDING→IN-PROCESS + bed OCCUPIED) is payment-gated.",
            responses = {
                @ApiResponse(responseCode = "201", description = "Admission created (PENDING)"),
                @ApiResponse(responseCode = "404", description = "Ward bed or ward type not found"),
                @ApiResponse(responseCode = "409", description = "Bed not EMPTY (stale-entity race)"),
                @ApiResponse(responseCode = "422", description = "Deceased patient / already admitted / business rule")
            })
    public ResponseEntity<AdmissionDto> doAdmission(
            @Valid @RequestBody AdmissionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String actor = jwt != null ? jwt.getSubject() : "anonymous";
        String dayUid = businessDayService.currentUid();
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), actor);

        AdmissionDto dto = admissionService.doAdmission(request, ctx);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/uid/{uid}")
                .buildAndExpand(dto.uid())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }
}
