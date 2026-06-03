package com.otapp.hmis.registration.web;

import com.otapp.hmis.registration.application.PatientQueryService;
import com.otapp.hmis.registration.application.PatientRegistrationProcess;
import com.otapp.hmis.registration.application.dto.ChangePatientTypeRequest;
import com.otapp.hmis.registration.application.dto.ChangePaymentTypeRequest;
import com.otapp.hmis.registration.application.dto.LastVisitDto;
import com.otapp.hmis.registration.application.dto.PatientDto;
import com.otapp.hmis.registration.application.dto.PatientSearchResult;
import com.otapp.hmis.registration.application.dto.RegisterPatientRequest;
import com.otapp.hmis.registration.application.dto.UpdatePatientRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PatientQueryService patientQueryService;
    private final BusinessDayService businessDayService;

    /**
     * Paginated patient search (build-spec §6). Reads are authenticated-only — NO privilege gate
     * (CR-04 parity: legacy patient reads were ungated). Matches no/names/phone/membership.
     *
     * @param query optional case-insensitive substring (blank matches all)
     * @param page  zero-based page index
     * @param size  page size
     */
    @GetMapping
    public PatientSearchResult search(
            @RequestParam(name = "query", required = false, defaultValue = "") String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return patientQueryService.search(query, page, size);
    }

    /**
     * Get a patient by uid (incl. lastVisitAt). Authenticated-only (CR-04). 404 if absent.
     */
    @GetMapping("/uid/{uid}")
    public PatientDto getByUid(@PathVariable("uid") String uid) {
        return patientQueryService.getByUid(uid);
    }

    /**
     * The patient's last-visit timestamp (build-spec §6, CR-08). Authenticated-only (CR-04).
     */
    @GetMapping("/uid/{uid}/last-visit")
    public LastVisitDto lastVisit(@PathVariable("uid") String uid) {
        return patientQueryService.lastVisit(uid);
    }

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

    /**
     * Update a patient's demographics + next-of-kin (NOT payment/type/no — those have dedicated
     * endpoints). RBAC: {@code PATIENT-ALL} or {@code PATIENT-UPDATE} (PatientResource.java:378-379).
     *
     * @param uid     the patient uid
     * @param request the demographics/kin payload
     * @param jwt     the authenticated principal
     * @return 200 with the updated patient
     */
    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")
    public PatientDto updatePatient(
            @PathVariable("uid") String uid,
            @Valid @RequestBody UpdatePatientRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return registrationProcess.updateDemographics(uid, request, ctxFrom(jwt));
    }

    /**
     * Change the patient type (OUTPATIENT ↔ OUTSIDER; INPATIENT/DECEASED rejected) with the legacy
     * change_type guards (PatientResource.java:398-506). RBAC: {@code PATIENT-ALL}/{@code PATIENT-UPDATE}.
     *
     * @param uid     the patient uid
     * @param request the desired target type
     * @param jwt     the authenticated principal
     * @return 200 with the updated patient
     */
    @PatchMapping("/uid/{uid}/patient-type")
    @PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")
    public PatientDto changePatientType(
            @PathVariable("uid") String uid,
            @Valid @RequestBody ChangePatientTypeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return registrationProcess.changePatientType(uid, request, ctxFrom(jwt));
    }

    /**
     * Change the payment type (CASH ↔ INSURANCE; INSURANCE requires plan + membership, CASH
     * collapses them) — PatientResource.java:359-373. RBAC: {@code PATIENT-ALL}/{@code PATIENT-UPDATE}
     * (CR-03 FIX — the legacy endpoint was ungated).
     *
     * @param uid     the patient uid
     * @param request the desired payment classification (+ plan/membership for INSURANCE)
     * @param jwt     the authenticated principal
     * @return 200 with the updated patient
     */
    @PatchMapping("/uid/{uid}/payment-type")
    @PreAuthorize("hasAnyAuthority('PATIENT-ALL','PATIENT-UPDATE')")
    public PatientDto changePaymentType(
            @PathVariable("uid") String uid,
            @Valid @RequestBody ChangePaymentTypeRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return registrationProcess.changePaymentType(uid, request, ctxFrom(jwt));
    }

    /** Build the per-operation audit context at the controller edge (ADR-0008 §3). */
    private TxAuditContext ctxFrom(Jwt jwt) {
        return new TxAuditContext(businessDayService.currentUid(), Instant.now(), jwt.getSubject());
    }
}
