package com.otapp.hmis.inpatient.application;

import com.otapp.hmis.billing.api.BillingCommands;
import com.otapp.hmis.billing.api.ChargeRequest;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.clinical.api.ConsultationSignOut;
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
import com.otapp.hmis.masterdata.lookup.WardTypeView;
import com.otapp.hmis.registration.lookup.PatientStatusLookup;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import com.otapp.hmis.shared.event.PatientAdmittedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li><strong>CR-07-WARD-INS-PRICE / Option B</strong> (07a-2): Insurance ward price is
 *       resolved via {@code PriceLookup.resolve(planUid, WARD, wardTypeUid)} — keyed on the
 *       admitted bed's ward type. The top-up split is genuinely load-bearing: when
 *       {@code cashPrice > coveredPrice}, an UNPAID "Ward Bed / Room (Top up)" supplementary
 *       bill is created for the difference; the admission stays PENDING until the top-up is
 *       paid. When {@code coveredPrice >= cashPrice} (diff &le; 0, no top-up), the admission
 *       activates IN-PROCESS at admit and the bed moves to OCCUPIED immediately.
 *       The legacy first-row/ward-type-agnostic defect (dead max-price loop + unreachable
 *       top-up guard) is NOT reproduced — see Option B rationale in
 *       docs/delivery/increments/07-inpatient-discovery/06-AMBIGUITY-WARD-INSURANCE-PRICE.md.
 *       </li>
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
 * <p><strong>Admission steps (PatientServiceImpl.java:1701-2021):</strong>
 * <ol>
 *   <li>Claim the bed WAITING (WardBedClaim.claimBed — done in guard step 4).</li>
 *   <li>Create Admission entity (PENDING).</li>
 *   <li>Create ward-bed PatientBill(s) via billing::api:
 *       <ul>
 *         <li>CASH path: one UNPAID bill via {@code recordClinicalCharge} at WardType.price
 *             (billItem="Bed", description="Ward Bed / Room" — :1758-1759). Stays PENDING until
 *             bill paid; {@link AdmissionSettlementListener} activates.</li>
 *         <li>INSURANCE path (CR-07-WARD-INS-PRICE / Option B):
 *             <ul>
 *               <li>COVERED principal at covered plan price via {@code recordClinicalCharge}
 *                   (kind=WARD, paymentType=INSURANCE, planUid, membershipNo, wardTypeUid).
 *                   The billing engine runs PriceLookup.resolve(planUid, WARD, wardTypeUid) →
 *                   COVERED bill at the plan price.</li>
 *               <li>If {@code cashPrice > coveredPrice} (diff &gt; 0): UNPAID supplementary
 *                   "Ward Bed / Room (Top up)" bill for the diff via
 *                   {@link BillingCommands#recordWardTopUp}. Admission stays PENDING; bed
 *                   stays WAITING until the top-up is paid (settlement listener fires on
 *                   the top-up bill uid). AdmissionBed.patientBillUid = top-up bill uid.</li>
 *               <li>If {@code diff &le; 0} (no top-up): activate IN-PROCESS at admit, call
 *                   {@code WardBedClaim.occupyBed}. AdmissionBed.patientBillUid = principal
 *                   bill uid (the COVERED bill — the settlement listener will not re-fire
 *                   for a COVERED bill, but that is correct: no top-up, no cashier action).</li>
 *             </ul>
 *         </li>
 *       </ul>
 *   </li>
 *   <li>Create AdmissionBed (OPENED).</li>
 *   <li>Publish PatientAdmittedEvent → PatientClosureListener flips Patient.type=INPATIENT
 *       (PatientServiceImpl.java:1785 — event replaces inline set to avoid inpatient→registration
 *       compile cycle, inc-07 07a SEAM-A).</li>
 * </ol>
 *
 * <p><strong>Activate-at-admit (no-top-up insurance branch, PatientServiceImpl.java:1950-1963):</strong>
 * <ul>
 *   <li>Open OPD consultations (PENDING + IN_PROCESS) signed out first via
 *       {@link com.otapp.hmis.clinical.api.ConsultationSignOut#signOutOpenConsultations}
 *       (inc-07 07a-2 — legacy PatientServiceImpl.java:1951-1958).</li>
 *   <li>Admission activated IN-PROCESS via {@link Admission#activate()} (line :1959).</li>
 *   <li>Bed occupied via {@link WardBedClaim#occupyBed(String)} (line :1961).</li>
 * </ul>
 *
 * <p><strong>Activation (CASH path — PatientBillResource.java:352-365):</strong>
 * Handled by {@link AdmissionSettlementListener} consuming
 * {@link com.otapp.hmis.shared.event.BillSettledEvent} BEFORE_COMMIT.
 * The cash path also signs out IN_PROCESS consultations (PatientBillResource.java:353-364)
 * via {@link com.otapp.hmis.clinical.api.ConsultationSignOut#signOutInProcessConsultations}
 * (note: cash path signs out only IN_PROCESS, not PENDING — narrower than the insurance path).
 * The admission stays PENDING until the ward-bed bill is paid.
 *
 * <p>Legacy citations: PatientServiceImpl.java:1701-2021; PatientResource.java:5183-5210.
 * CR-07-WARD-INS-PRICE / Option B (see 06-AMBIGUITY-WARD-INSURANCE-PRICE.md).
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
    private final ConsultationSignOut consultationSignOut;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Admit a patient to a bed (doAdmission — PatientServiceImpl.java:1701-2021).
     *
     * <p>Runs the full guard order then orchestrates the five-step admission sequence.
     * Publishes {@link PatientAdmittedEvent} BEFORE_COMMIT so Patient.type is flipped to
     * INPATIENT atomically with the admission creation (inc-07 07a SEAM-A).
     *
     * <p>INSURANCE path implements Option B / CR-07-WARD-INS-PRICE: covered price keyed on the
     * admitted bed's ward type via {@code PriceLookup.resolve(planUid, WARD, wardTypeUid)};
     * top-up split genuinely load-bearing when {@code cashPrice > coveredPrice}.
     *
     * @param request the admission request (patientUid, wardBedUid, paymentType, ...)
     * @param ctx     the transaction audit context (dayUid, actor, timestamp)
     * @return an {@link AdmissionDto} describing the newly created admission (PENDING or IN_PROCESS)
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
        // Step 1a: resolve ward bed → ward type uid + cash price
        // PatientServiceImpl.java:1754 — wb.getWard().getWardType().getPrice()
        // The cash price is the WardType.price (the "sticker" per-stay cost).
        // -----------------------------------------------------------------
        var bedView = wardLookup.findBedByUid(wardBedUid)
                .orElseThrow(() -> new NotFoundException("Ward bed not found: " + wardBedUid));

        WardTypeView wardTypeView = wardLookup.findWardTypeByUid(bedView.wardTypeUid())
                .orElseThrow(() -> new NotFoundException(
                        "Ward type not found for bed: " + wardBedUid));

        // Cash price for the ward type — used in the top-up diff calculation (07a-2).
        BigDecimal cashWardPrice = wardTypeView.price().setScale(2, RoundingMode.HALF_UP);

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
        // Step 3: create ward-bed bill(s) and decide activation
        // PatientServiceImpl.java:1753-1774 (CASH) / :1795-1965 (INSURANCE, Option B)
        // -----------------------------------------------------------------
        final String billUidForSettlement;  // the UNPAID bill uid that triggers activation

        if (paymentMode == PaymentMode.INSURANCE) {
            billUidForSettlement = handleInsurancePath(
                    admission, patientUid, wardBedUid, bedView.wardTypeUid(),
                    cashWardPrice, request.insurancePlanUid(), request.membershipNo(),
                    ctx);
        } else {
            // CASH path — unchanged from 07a-1
            billUidForSettlement = handleCashPath(
                    admission, patientUid, bedView.wardTypeUid(), ctx);
        }

        // -----------------------------------------------------------------
        // Step 4: create AdmissionBed (OPENED)
        // PatientServiceImpl.java:1776-1783
        // patientBillUid is either:
        //   - CASH: the UNPAID ward bill uid (activated by settlement listener on payment)
        //   - INSURANCE with top-up (diff > 0): the UNPAID top-up bill uid (activated on
        //     top-up payment via settlement listener)
        //   - INSURANCE no-top-up (diff <= 0): the COVERED principal bill uid (stored for
        //     traceability — admission is already IN-PROCESS; the listener will not re-fire
        //     for a COVERED bill, which is correct)
        // See class javadoc for AdmissionBed.patientBillUid decision.
        // -----------------------------------------------------------------
        AdmissionBed admissionBed = new AdmissionBed(
                admission.getUid(),
                wardBedUid,
                patientUid,
                billUidForSettlement,
                ctx.timestamp());
        admissionBedRepository.save(admissionBed);
        auditRecorder.record(AUDIT_ADMISSION_BED, admissionBed.getUid(), AuditAction.CREATE,
                ctx.actorUsername());

        // -----------------------------------------------------------------
        // Step 5: publish PatientAdmittedEvent → PatientClosureListener sets Patient.type=INPATIENT
        // PatientServiceImpl.java:1785 — extracted to event seam (inc-07 07a SEAM-A)
        // -----------------------------------------------------------------
        eventPublisher.publishEvent(new PatientAdmittedEvent(patientUid, ctx.actorUsername()));
        log.debug("AdmissionService: doAdmission complete; admissionUid={} patientUid={} status={}",
                admission.getUid(), patientUid, admission.getStatus());

        return toDto(admission);
    }

    // -------------------------------------------------------------------------
    // CASH path (PatientServiceImpl.java:1753-1774, PARITY from 07a-1)
    // -------------------------------------------------------------------------

    /**
     * Handle the CASH path: one UNPAID ward bill. Returns the bill uid (the settlement key).
     * PatientServiceImpl.java:1753-1774.
     */
    private String handleCashPath(Admission admission, String patientUid,
                                  String wardTypeUid, TxAuditContext ctx) {
        ChargeRequest chargeRequest = new ChargeRequest(
                patientUid,
                null,                   // planUid — null for CASH
                null,                   // membershipNo — null for CASH
                ServiceKind.WARD,
                wardTypeUid,            // serviceUid = wardTypeUid
                BigDecimal.ONE,
                PaymentMode.CASH,
                true,                   // inpatient = true
                false,                  // followUp not applicable for ward charges
                "Bed",                  // billItem — verbatim legacy :1758
                "Ward Bed / Room",      // description — verbatim legacy :1759
                admission.getUid()      // admissionUid — links bill to this admission
        );
        var result = billingCommands.recordClinicalCharge(chargeRequest, ctx);
        log.debug("AdmissionService: CASH ward bill created; billUid={} status={}",
                result.billUid(), result.status());
        return result.billUid();
    }

    // -------------------------------------------------------------------------
    // INSURANCE path (PatientServiceImpl.java:1795-1965, Option B / CR-07-WARD-INS-PRICE)
    // -------------------------------------------------------------------------

    /**
     * Handle the INSURANCE path (Option B / CR-07-WARD-INS-PRICE):
     * <ol>
     *   <li>Create COVERED principal bill via {@code recordClinicalCharge} — the billing engine
     *       runs PriceLookup.resolve(planUid, WARD, wardTypeUid) which returns the covered
     *       price keyed on the admitted bed's ward type (ChargeResult.amount is the covered price).
     *       The bill is COVERED, status set by the pricing engine.</li>
     *   <li>Compute diff = cashWardPrice - coveredPrice (HALF_UP 2dp).</li>
     *   <li>If diff &gt; 0: create UNPAID top-up supplementary bill via
     *       {@link BillingCommands#recordWardTopUp}; admission stays PENDING; return top-up
     *       bill uid as the settlement key.</li>
     *   <li>If diff &le; 0 (no top-up): activate admission IN-PROCESS at admit + occupyBed;
     *       return principal bill uid (the COVERED bill).</li>
     * </ol>
     *
     * <p>Option B deviation from legacy: legacy's ward-type-agnostic first-row selection +
     * always-false top-up guard are NOT reproduced. See
     * docs/delivery/increments/07-inpatient-discovery/06-AMBIGUITY-WARD-INSURANCE-PRICE.md.
     *
     * <p>Legacy citation: PatientServiceImpl.java:1795-1965 (INSURANCE branch of doAdmission).
     *
     * @return the settlement-key bill uid (top-up uid if diff &gt; 0; principal uid if diff &le; 0)
     */
    private String handleInsurancePath(Admission admission, String patientUid, String wardBedUid,
                                       String wardTypeUid, BigDecimal cashWardPrice,
                                       String insurancePlanUid, String membershipNo,
                                       TxAuditContext ctx) {

        // -----------------------------------------------------------------
        // Step 3a: create COVERED principal ward bill via billing engine
        // recordClinicalCharge → PriceLookup.resolve(planUid, WARD, wardTypeUid) →
        // COVERED bill at the ward-type-keyed covered price (Option B).
        // PatientServiceImpl.java:1795-1845 (insurance bill creation).
        // -----------------------------------------------------------------
        ChargeRequest principalRequest = new ChargeRequest(
                patientUid,
                insurancePlanUid,       // planUid — triggers insurance override in billing engine
                membershipNo,
                ServiceKind.WARD,
                wardTypeUid,            // serviceUid = wardTypeUid (Option B key)
                BigDecimal.ONE,
                PaymentMode.INSURANCE,
                true,                   // inpatient = true
                false,                  // followUp not applicable
                "Bed",                  // billItem — verbatim legacy
                "Ward Bed / Room",      // description — verbatim legacy
                admission.getUid()      // admissionUid
        );
        var principalResult = billingCommands.recordClinicalCharge(principalRequest, ctx);
        String principalBillUid = principalResult.billUid();

        // The covered price as returned by the billing engine (ChargeResult.amount).
        // For a COVERED bill: amount == planPrice (the insurance plan's ward-type rate).
        BigDecimal coveredPrice = principalResult.amount().amount().setScale(2, RoundingMode.HALF_UP);

        log.debug("AdmissionService: INSURANCE principal ward bill; billUid={} status={} "
                + "coveredPrice={} cashPrice={}",
                principalBillUid, principalResult.status(), coveredPrice, cashWardPrice);

        // -----------------------------------------------------------------
        // Step 3b: top-up decision (Option B — genuinely load-bearing)
        // diff = cashWardPrice - coveredPrice (HALF_UP 2dp)
        // PatientServiceImpl.java:1880-1897 (top-up creation, Option B makes this reachable)
        // -----------------------------------------------------------------
        BigDecimal diff = cashWardPrice.subtract(coveredPrice).setScale(2, RoundingMode.HALF_UP);

        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            // -----------------------------------------------------------------
            // TOP-UP CASE (diff > 0): patient owes the difference at the cashier
            // Create UNPAID supplementary "Ward Bed / Room (Top up)" bill.
            // AdmissionBed.patientBillUid = top-up bill uid (the settlement key).
            // Admission stays PENDING; bed stays WAITING until the top-up is paid.
            // When the cashier pays the top-up, BillSettledEvent fires for the top-up uid →
            // AdmissionSettlementListener activates admission IN-PROCESS + occupies bed.
            // Option B / CR-07-WARD-INS-PRICE — PatientServiceImpl.java:1880-1897.
            // -----------------------------------------------------------------
            String topUpBillUid = billingCommands.recordWardTopUp(
                    principalBillUid, patientUid, admission.getUid(), diff, ctx);

            log.debug("AdmissionService: INSURANCE top-up bill created; topUpUid={} diff={} "
                    + "admissionUid={} — admission stays PENDING",
                    topUpBillUid, diff, admission.getUid());

            // Settlement key = top-up bill uid (UNPAID — will trigger the settlement listener)
            return topUpBillUid;

        } else {
            // -----------------------------------------------------------------
            // NO-TOP-UP CASE (diff <= 0): covered price covers the full cash ward price.
            // ACTIVATE AT ADMIT (PatientServiceImpl.java:1950-1963 legacy 'else' branch).
            //
            // Legacy order (PatientServiceImpl.java:1955-1962):
            //   1. Sign out PENDING + IN_PROCESS consultations (lines :1955-1958)
            //   2. Admission → IN-PROCESS (line :1959)
            //   3. WardBedClaim.occupyBed → bed OCCUPIED (line :1961)
            //
            // Reproduced verbatim via the clinical::api ConsultationSignOut write seam
            // (clinical.api.ConsultationSignOut — inc-07 07a-2, PatientServiceImpl.java:1950-1963).
            // -----------------------------------------------------------------

            // Step 1: sign out PENDING + IN_PROCESS consultations (PatientServiceImpl.java:1951-1958)
            consultationSignOut.signOutOpenConsultations(patientUid, ctx);

            // Step 2: activate admission IN-PROCESS (PatientServiceImpl.java:1959)
            admission.activate();
            auditRecorder.record(AUDIT_ADMISSION, admission.getUid(), AuditAction.UPDATE,
                    ctx.actorUsername());

            // Step 3: occupy the bed (PatientServiceImpl.java:1961)
            wardBedClaim.occupyBed(wardBedUid);

            log.debug("AdmissionService: INSURANCE no-top-up (diff={}) — signed out open "
                    + "consultations, admission {} activated IN-PROCESS + bed {} occupied at admit",
                    diff, admission.getUid(), wardBedUid);

            // Settlement key = principal bill uid (COVERED — stored for traceability;
            // the settlement listener will not re-fire for COVERED bills, which is correct
            // since no top-up exists).
            return principalBillUid;
        }
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

    // -------------------------------------------------------------------------
    // Read surface (inc-07 — the admissions list + detail screens)
    // -------------------------------------------------------------------------

    /**
     * List admissions, newest first, optionally filtered to a single status.
     *
     * @param statusFilter a status db-value (e.g. {@code "IN-PROCESS"}); {@code null}/blank = all
     * @return the matching admissions as DTOs (newest first)
     * @throws NotFoundException if {@code statusFilter} is non-blank but not a known status
     */
    @Transactional(readOnly = true)
    public List<AdmissionDto> list(String statusFilter) {
        List<Admission> admissions;
        if (statusFilter == null || statusFilter.isBlank()) {
            admissions = admissionRepository.findAllByOrderByAdmittedAtDesc();
        } else {
            // Resolve the filter safely — an unknown value is a client error (404), not a 500.
            String wanted = statusFilter.trim();
            AdmissionStatus status = java.util.Arrays.stream(AdmissionStatus.values())
                    .filter(s -> s.dbValue().equals(wanted))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Unknown admission status: " + statusFilter));
            admissions = admissionRepository.findAllByStatusOrderByAdmittedAtDesc(status);
        }
        return admissions.stream().map(AdmissionService::toDto).toList();
    }

    /**
     * Fetch a single admission by its public uid.
     *
     * @param uid the admission's ULID
     * @return the admission DTO
     * @throws NotFoundException (404) if no admission with that uid exists
     */
    @Transactional(readOnly = true)
    public AdmissionDto getByUid(String uid) {
        Admission admission = admissionRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Admission not found: " + uid));
        return toDto(admission);
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
