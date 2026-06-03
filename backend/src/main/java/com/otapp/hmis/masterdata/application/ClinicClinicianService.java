package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.iam.lookup.ClinicianAffiliationService;
import com.otapp.hmis.iam.lookup.IamLookupService;
import com.otapp.hmis.iam.lookup.UserSummary;
import com.otapp.hmis.masterdata.domain.ClinicRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.ClinicianRoleRequiredException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for clinic–clinician affiliation (CR-08, build-spec §5.2, AC-4).
 *
 * <p>Orchestration pattern (masterdata→iam edge):
 * <ol>
 *   <li>Verify the clinic exists in masterdata ({@link ClinicRepository}).
 *   <li>Verify the user exists AND holds the {@code CLINICIAN} role via
 *       {@link IamLookupService} (cross-module lookup; never imports iam.domain).
 *   <li>Delegate the actual affiliation mutation to {@link ClinicianAffiliationService}
 *       (also a cross-module lookup interface).
 *   <li>Record the audit event.
 * </ol>
 *
 * <p>No {@code LocalDateTime.now()} / {@code Instant.now()} called here (ArchUnit gate).
 * No {@code @Transactional} on the controller (ArchUnit gate).
 */
@Service
@RequiredArgsConstructor
public class ClinicClinicianService {

    private final ClinicRepository clinicRepository;
    private final IamLookupService iamLookupService;
    private final ClinicianAffiliationService clinicianAffiliationService;
    private final AuditRecorder auditRecorder;

    /**
     * Affiliates a clinician user with a clinic.
     *
     * <p>Guards:
     * <ul>
     *   <li>Clinic must exist → 404 {@link NotFoundException}
     *   <li>User must exist AND hold CLINICIAN role → 403 {@link ClinicianRoleRequiredException}
     * </ul>
     *
     * @param clinicUid the masterdata Clinic.uid (path variable)
     * @param userUid   the iam User.uid (request body)
     */
    @Transactional
    public void affiliateClinician(String clinicUid, String userUid) {
        // 1. Verify clinic exists
        clinicRepository.findByUid(clinicUid)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + clinicUid));

        // 2. Verify user exists and holds CLINICIAN role
        UserSummary user = iamLookupService.findUser(userUid)
                .orElseThrow(() -> new NotFoundException("User not found: " + userUid));
        if (!user.roleNames().contains("CLINICIAN")) {
            throw new ClinicianRoleRequiredException(userUid);
        }

        // 3. Delegate affiliation to iam
        clinicianAffiliationService.affiliateClinic(userUid, clinicUid);

        // 4. Audit the action (entity type = "masterdata.ClinicClinician"; uid = clinicUid)
        auditRecorder.record("masterdata.ClinicClinician", clinicUid, AuditAction.CREATE);
    }

    /**
     * Removes a clinician–clinic affiliation.
     *
     * <p>Idempotent: calling when the affiliation does not exist returns 204 with no error.
     * If the clinic does not exist, throws 404 (to surface stale-uid bugs; idempotency only
     * applies to the affiliation row itself).
     *
     * @param clinicUid the masterdata Clinic.uid (path variable)
     * @param userUid   the iam User.uid (path variable)
     */
    @Transactional
    public void removeAffiliation(String clinicUid, String userUid) {
        // Verify clinic exists (surfaces stale-uid bugs; DELETE is otherwise idempotent)
        clinicRepository.findByUid(clinicUid)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + clinicUid));

        // Delegate removal to iam (idempotent no-op if not present)
        clinicianAffiliationService.removeClinicAffiliation(userUid, clinicUid);

        // Audit the action
        auditRecorder.record("masterdata.ClinicClinician", clinicUid, AuditAction.DELETE);
    }

    /**
     * Lists all clinician user summaries affiliated with the given clinic.
     *
     * @param clinicUid the masterdata Clinic.uid
     * @return list of {@link UserSummary} for all affiliated clinicians
     */
    @Transactional(readOnly = true)
    public List<UserSummary> listClinicians(String clinicUid) {
        clinicRepository.findByUid(clinicUid)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + clinicUid));

        List<String> userUids = clinicianAffiliationService.clinicianUserUidsForClinic(clinicUid);
        return iamLookupService.findUsers(userUids);
    }

    /**
     * Lists all users holding the {@code CLINICIAN} role (admin-only).
     * Implements the unscoped admin listing per ADR-0020.
     *
     * @return list of {@link UserSummary} for all CLINICIAN-role users
     */
    @Transactional(readOnly = true)
    public List<UserSummary> listAllClinicians() {
        return iamLookupService.findUsersByRole("CLINICIAN");
    }
}
