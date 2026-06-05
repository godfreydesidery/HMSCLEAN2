package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.inpatient.application.dto.AdmissionDto;
import com.otapp.hmis.inpatient.application.dto.AdmissionRequest;
import com.otapp.hmis.inpatient.domain.Admission;
import com.otapp.hmis.inpatient.domain.AdmissionBed;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.masterdata.lookup.WardBedClaim;
import com.otapp.hmis.masterdata.lookup.WardLookup;
import com.otapp.hmis.registration.lookup.PatientStatusLookup;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import com.otapp.hmis.shared.event.PatientAdmittedEvent;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inpatient admission orchestration service (inc-07 07a).
 *
 * <p>Implements the {@code doAdmission} flow reproduced verbatim from the legacy
 * {@code PatientServiceImpl.java:1701-2021} and {@code PatientResource.java:5183-5210},
 * with the following owner-approved deviations:
 * <ul>
 *   <li><strong>CR-07-deceased-guard</strong> (NET-NEW): DECEASED patient guard at step 2
 *       ({@link PatientStatusLookup#isDeceased}). Legacy had no such guard.</li>
 *   <li><strong>CR-07-Q3 / ADR-0017</strong>: PESSIMISTIC_WRITE bed-claim locking via
 *       {@link WardBedClaim#claimBed} (net-new concurrency hardening). Legacy had a simple
 *       in-place status check with no lock.</li>
 *   <li><strong>TODO(07a-2)</strong>: Insurance/top-up ward pricing is deferred to chunk 07a-2.
 *       For now the CASH price path only — the ward bill is always created at
 *       {@code WardType.price} as a plain CASH UNPAID bill.</li>
 * </ul>
 *
 * <p><strong>Guard order (verbatim from legacy PatientResource.java:5186-5210):</strong>
 * <ol>
 *   <li>Patient existence — not implemented here as an explicit inpatient-side check; the patient
 *       uid is validated by {@link PatientStatusLookup} (if absent, isDeceased returns false and
 *       downstream billing validates existence). // TODO: add an existence signal if needed.</li>
 *   <li>DECEASED guard (CR-07-deceased-guard): {@link PatientStatusLookup#isDeceased} → 422
 *       {@link com.otapp.hmis.shared.error.ErrorCode#PATIENT_DECEASED}.</li>
 *   <li>Already-admitted guard: query own admissions table for any PENDING/IN-PROCESS admission
 *       for this patient → 422 "Could not process admission. The patient is already admitted".</li>
 *   <li>Bed claim: {@link WardBedClaim#claimBed} (PESSIMISTIC_WRITE; throws StaleEntityException
 *       on race — caller retries with a fresh bed selection).</li>
 * </ol>
 *
 * <p><strong>Admission steps (PatientServiceImpl.java:1701-2021, CASH path):</strong>
 * <ol>
 *   <li>Claim the bed WAITING (WardBedClaim.claimBed — done in guard step 4).</li>
 *   <li>Create Admission entity (PENDING).</li>
 *   <li>Create UNPAID ward-bed PatientBill via billing::api at WardType.price
 *       (billItem="Bed", description="Ward Bed / Room" — PatientServiceImpl.java:1758-1759).
 *       // TODO(07a-2): insurance/top-up path deferred.</li>
 *   <li>Create AdmissionBed (OPENED).</li>
 *   <li>Publish PatientAdmittedEvent → PatientClosureListener flips Patient.type=INPATIENT
 *       (PatientServiceImpl.java:1785 — event replaces inline set to avoid inpatient→registration
 *       compile cycle, inc-07 07a SEAM-A).</li>
 * </ol>
 *
 * <p><strong>Activation (CASH path — PatientBillResource.java:352-365):</strong>
 * Handled by {@link AdmissionSettlementListener} consuming
 * {@link com.otapp.hmis.shared.event.BillSettledEvent} BEFORE_COMMIT.
 * The admission stays PENDING until the ward-bed bill is paid.
 *
 * <p>Legacy citations: PatientServiceImpl.java:1701-2021; PatientResource.java:5183-5210.
 */
@Service
@RequiredArgsConstructor
public class AdmissionService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionService.class);

    private static final String AUDIT_ADMISSION     = "inpatient.Admission";
    private static final String AUDIT_ADMISSION_BED = "inpatient.AdmissionBed";

    private final AdmissionRepository admissionRepository;
    private final AdmissionBedRepository admissionBedRepository;
    private final PatientStatusLookup patientStatusLookup;
    private final WardLookup wardLookup;
    private final WardBedClaim wardBedClaim;
    private final BillingCommands billingCommands;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Admit a patient to a bed (doAdmission — PatientServiceImpl.java:1701-2021).
     *
     * <p>Runs the full guard order then orchestrates the five-step admission sequence.
     * Publishes {@link PatientAdmittedEvent} BEFORE_COMMIT so Patient.type is flipped to
     * INPATIENT atomically with the admission creation (inc-07 07a SEAM-A).
     *
     * @param request the admission request (patientUid, wardBedUid, paymentType, ...)
     * @param ctx     the transaction audit context (dayUid, actor, timestamp)
     * @return an {@link AdmissionDto} describing the newly created PENDING admission
     * @throws PatientDeceasedException         (422) if the patient is DECEASED
     * @throws PatientAlreadyAdmittedException  (422) if the patient is already admitted
     * @throws NotFoundException                (404) if the wardBed or wardType is not found
     * @throws com.otapp.hmis.shared.error.StaleEntityException (409) if the bed is not EMPTY
     *         under the PESSIMISTIC_WRITE lock (concurrent bed-claim race)
     */
    @Transactional
    public AdmissionDto doAdmission(AdmissionRequest request, TxAuditContext ctx) {
        String patientUid = request.patientUid();
        String wardBedUid = request.wardBedUid();

        // -----------------------------------------------------------------
        // Guard 2: DECEASED patient (CR-07-deceased-guard, net-new hardening)
        // -----------------------------------------------------------------
        if (patientStatusLookup.isDeceased(patientUid)) {
            log.debug("AdmissionService: rejected doAdmission for deceased patient uid={}", patientUid);
            throw new PatientDeceasedException();
        }

        // -----------------------------------------------------------------
        // Guard 3: already admitted (PatientResource.java:5194-5200)
        // -----------------------------------------------------------------
        List<Admission> openAdmissions = admissionRepository.findAllByPatientUidAndStatusIn(
                patientUid,
                List.of(AdmissionStatus.PENDING, AdmissionStatus.IN_PROCESS));
        if (!openAdmissions.isEmpty()) {
            throw new PatientAlreadyAdmittedException();
        }

        // -----------------------------------------------------------------
        // Guard 4: bed claim (PESSIMISTIC_WRITE, CR-07-Q3 / ADR-0017)
        // WardBedClaim.claimBed: active + EMPTY guard under lock; throws
        // StaleEntityException on WAITING/OCCUPIED — caller should reload and retry.
        // PatientServiceImpl.java:1703-1711.
        // -----------------------------------------------------------------
        wardBedClaim.claimBed(wardBedUid);

        // -----------------------------------------------------------------
        // Step 1: resolve ward-type price for the bed (CASH path)
        // PatientServiceImpl.java:1754 — wb.getWard().getWardType().getPrice()
        // TODO(07a-2): insurance/top-up pricing deferred
        // -----------------------------------------------------------------
        var bedView = wardLookup.findBedByUid(wardBedUid)
                .orElseThrow(() -> new NotFoundException("Ward bed not found: " + wardBedUid));
        // Ward type resolved here to confirm it exists and to carry wardTypeUid into
        // the ChargeRequest.serviceUid. The actual cash price is resolved inside the
        // billing engine via PriceLookup (ServiceKind.WARD, serviceUid=wardTypeUid).
        // TODO(07a-2): pass wardTypeView.price() directly for the insurance-top-up delta calc.
        wardLookup.findWardTypeByUid(bedView.wardTypeUid())
                .orElseThrow(() -> new NotFoundException(
                        "Ward type not found for bed: " + wardBedUid));

        // -----------------------------------------------------------------
        // Step 2: create Admission (PENDING)
        // PatientServiceImpl.java:1713-1748
        // -----------------------------------------------------------------
        PaymentMode paymentMode = resolvePaymentMode(request.paymentType());
        Admission admission = new Admission(
                patientUid,
                wardBedUid,
                paymentMode,
                request.insurancePlanUid(),
                request.membershipNo(),
                ctx.timestamp());
        admissionRepository.save(admission);
        auditRecorder.record(AUDIT_ADMISSION, admission.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        // -----------------------------------------------------------------
        // Step 3: create UNPAID ward-bed bill via billing::api (CASH path)
        // PatientServiceImpl.java:1753-1774
        // billItem="Bed", description="Ward Bed / Room" (verbatim legacy)
        // TODO(07a-2): insurance/top-up path — full insurance-cover branch sets status=COVERED
        //              and activates the admission immediately; deferred to 07a-2.
        // -----------------------------------------------------------------
        ChargeRequest chargeRequest = new ChargeRequest(
                patientUid,
                null,                   // planUid — null for CASH (TODO(07a-2): pass plan)
                null,                   // membershipNo — null for CASH
                ServiceKind.WARD,
                bedView.wardTypeUid(),  // serviceUid = wardTypeUid (the "service" being priced)
                BigDecimal.ONE,
                PaymentMode.CASH,       // TODO(07a-2): use paymentMode when insurance path lands
                true,                   // inpatient = true
                false,                  // followUp not applicable for ward charges
                "Bed",                  // billItem — verbatim legacy (PatientServiceImpl.java:1758)
                "Ward Bed / Room",      // description — verbatim legacy (:1759)
                admission.getUid()      // admissionUid — links bill to this admission (inc-07 07a)
        );
        var chargeResult = billingCommands.recordClinicalCharge(chargeRequest, ctx);
        String billUid = chargeResult.billUid();

        // -----------------------------------------------------------------
        // Step 4: create AdmissionBed (OPENED)
        // PatientServiceImpl.java:1776-1783
        // -----------------------------------------------------------------
        AdmissionBed admissionBed = new AdmissionBed(
                admission.getUid(),
                wardBedUid,
                patientUid,
                billUid,
                ctx.timestamp());
        admissionBedRepository.save(admissionBed);
        auditRecorder.record(AUDIT_ADMISSION_BED, admissionBed.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        // -----------------------------------------------------------------
        // Step 5: publish PatientAdmittedEvent → PatientClosureListener sets Patient.type=INPATIENT
        // PatientServiceImpl.java:1785 — extracted to event seam (inc-07 07a SEAM-A)
        // -----------------------------------------------------------------
        eventPublisher.publishEvent(new PatientAdmittedEvent(patientUid, ctx.actorUsername()));
        log.debug("AdmissionService: doAdmission complete; admissionUid={} patientUid={} status=PENDING",
                admission.getUid(), patientUid);

        return toDto(admission);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static PaymentMode resolvePaymentMode(String paymentType) {
        return switch (paymentType.toUpperCase()) {
            case "INSURANCE" -> PaymentMode.INSURANCE;
            default          -> PaymentMode.CASH;
        };
    }

    /**
     * Map an {@link Admission} entity to its DTO (no id exposure — ADR-0014 §1).
     */
    static AdmissionDto toDto(Admission a) {
        return new AdmissionDto(
                a.getUid(),
                a.getPatientUid(),
                a.getWardBedUid(),
                a.getPaymentType().name(),
                a.getInsurancePlanUid(),
                a.getMembershipNo(),
                a.getStatus().dbValue(),
                a.getAdmittedAt(),
                a.getDischargedAt());
    }
}
