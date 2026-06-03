package com.otapp.hmis.registration.web;

import com.otapp.hmis.registration.application.PatientRegistrationProcess;
import com.otapp.hmis.registration.application.dto.PatientDto;
import com.otapp.hmis.registration.application.dto.RegisterPatientRequest;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.TxAuditContext;
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

/**
 * REST controller for patient registration (build-spec §1.3, §5.1, PatientResource.java:288-305).
 *
 * <p>Design constraints (ADR-0014 §5):
 * <ul>
 *   <li>No {@code @Transactional} on the controller — the service owns the transaction boundary.</li>
 *   <li>No {@code {id}} path variables — only ULID {@code uid} routes (ADR-0014 §1).</li>
 *   <li>{@link TxAuditContext} is constructed once here and threaded into the service —
 *       {@code businessDayService.currentUid()} is called at the controller edge, not inside
 *       the service, matching the {@code PaymentController} exemplar (ADR-0008 §3).</li>
 * </ul>
 *
 * <p>RBAC (§5.1, PatientResource.java:288-289):
 * <ul>
 *   <li>POST (register): {@code PATIENT-ALL} or {@code PATIENT-CREATE}</li>
 * </ul>
 *
 * <p>GET endpoints (search, get by uid, last-visit) are deferred to C5 (build-spec §8 C5).
 *
 * <p>Legacy citation: PatientResource.java:288-305.
 */
@RestController
@RequestMapping("/api/v1/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientRegistrationProcess registrationProcess;
    private final BusinessDayService businessDayService;

    /**
     * Register a new patient (CASH or INSURANCE).
     *
     * <p>Responds with {@code 201 Created} + {@code Location: /api/v1/patients/uid/{uid}} + the
     * created {@link PatientDto}. No {@code id} field appears in the JSON (ADR-0014 §1).
     *
     * @param request the registration payload (validated via Bean Validation)
     * @param jwt     the authenticated principal (registration clerk)
     * @return 201 with the created patient and a Location header
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-CREATE')")
    public ResponseEntity<PatientDto> registerPatient(
            @Valid @RequestBody RegisterPatientRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        TxAuditContext ctx = new TxAuditContext(
                businessDayService.currentUid(),
                Instant.now(),
                jwt.getSubject());

        PatientDto dto = registrationProcess.register(request, ctx);

        URI location = URI.create("/api/v1/patients/uid/" + dto.uid());
        return ResponseEntity.created(location).body(dto);
    }
}
