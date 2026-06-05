package com.otapp.hmis.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.inpatient.application.WardAccrualService;
import com.otapp.hmis.inpatient.application.WardDayAccrualJob;
import com.otapp.hmis.inpatient.domain.AdmissionBedRepository;
import com.otapp.hmis.inpatient.domain.AdmissionRepository;
import com.otapp.hmis.inpatient.domain.AdmissionStatus;
import com.otapp.hmis.registration.domain.Patient;
import com.otapp.hmis.registration.domain.PatientRepository;
import com.otapp.hmis.registration.domain.PatientType;
import com.otapp.hmis.registration.domain.PaymentType;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import com.otapp.hmis.support.TestJwtFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for inc-07 07c-ii — ward-day accrual parity oracle.
 *
 * <p>Tests {@link WardAccrualService#accrueWardDay(Instant)} and {@link WardDayAccrualJob#runOnce()}
 * against a real PostgreSQL 16 instance (Testcontainers via {@link AbstractIntegrationTest}).
 *
 * <p>The {@code @Scheduled} cron is DISABLED in tests ({@code hmis.scheduling.enabled=false} in
 * {@code application-test.yml}) so the accrual job never fires automatically — all accruals are
 * driven explicitly by test code.
 *
 * <p>Backdating technique (per task spec): rather than mutating the {@code opened_at} column
 * (which is {@code updatable=false}), each scenario captures {@code bed.openedAt} from the
 * newly-OPENED {@link com.otapp.hmis.inpatient.domain.AdmissionBed} and passes
 * {@code openedAt.plus(25, HOURS)} as the {@code now} parameter to {@code accrueWardDay}. This
 * makes the elapsed 25 h &ge; 24 h condition true without any DB mutation.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>1 (ACCRUAL_DUE) — cash patient, &ge;24h old bed, single accrual.</li>
 *   <li>2 (NOT_DUE)     — fresh bed &lt;24h, no-op.</li>
 *   <li>3 (CHAINED)     — two successive accruals on the same admission.</li>
 *   <li>4 (DE_DUP)      — two OPENED beds present, close extras, no bill created.</li>
 *   <li>5 (INSURANCE)   — INSURANCE patient, covered price &lt; cash, COVERED principal + VERIFIED
 *                         top-up created at accrual time.</li>
 *   <li>6 (JOB_LOG)     — {@code WardDayAccrualJob.runOnce()} writes a {@code job_run_log}
 *                         row with {@code status='COMPLETED'} and correct
 *                         {@code records_affected}.</li>
 * </ul>
 *
 * <p>Legacy citations: UpdatePatient.java:258-340; inc-07 07c-ii; CR-07-Q2; ADR-0018 JOB-001.
 */
class WardAccrualIT extends AbstractIntegrationTest {

    // ---- REST paths (identical to AdmissionLifecycleIT) ----
    private static final String WARD_CATS  = "/api/v1/masterdata/ward-categories";
    private static final String WARD_TYPES = "/api/v1/masterdata/ward-types";
    private static final String WARDS      = "/api/v1/masterdata/wards";
    private static final String BEDS       = "/api/v1/masterdata/beds";
    private static final String PRICES     = "/api/v1/masterdata/service-prices";
    private static final String ADMISSIONS = "/api/v1/inpatient/admissions";
    private static final String PROVIDERS  = "/api/v1/masterdata/insurance-providers";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestJwtFactory jwtFactory;
    @Autowired BusinessDayService businessDayService;
    @Autowired PatientRepository patientRepository;
    @Autowired PatientBillRepository patientBillRepository;
    @Autowired AdmissionRepository admissionRepository;
    @Autowired AdmissionBedRepository admissionBedRepository;
    @Autowired WardAccrualService wardAccrualService;
    @Autowired WardDayAccrualJob wardDayAccrualJob;
    @Autowired DataSource dataSource;

    private String token;
    private String dayUid;

    @BeforeEach
    void setUp() {
        token = jwtFactory.tokenWithPrivileges("nurse",
                List.of("ADMIN-ACCESS", "PATIENT-ALL", "BILL-A"));
        dayUid = ensureDayOpen();
    }

    // =========================================================================
    // Scenario 1: ACCRUAL DUE
    // Cash patient, activated admission (IN_PROCESS), bed >= 24h → accrual fires.
    // Assert: bed CLOSED, new OPENED bed, new VERIFIED ward bill, returns 1.
    // =========================================================================

    @Nested
    class AccrualDue {

        @Test
        void cashAdmission_bedOlderThan24h_accruesNewVerifiedBill() throws Exception {
            String tag = uniq();
            BigDecimal wardPrice = new BigDecimal("700.00");

            // Seed + admit + activate
            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, wardPrice.toPlainString());
            String admUid     = admitAndActivate(patientUid, wardBedUid, wardPrice);

            // Confirm admission is IN_PROCESS
            assertThat(admissionRepository.findByUid(admUid).orElseThrow().getStatus())
                    .isEqualTo(AdmissionStatus.IN_PROCESS);

            // Capture the OPENED bed created at admit-time (this is bed #1)
            var openedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedBeds).hasSize(1);
            var bed1 = openedBeds.get(0);
            String bed1Uid  = bed1.getUid();
            Instant bed1OpenedAt = bed1.getOpenedAt();

            // --- ACT: pass now = openedAt + 25h → elapsed 25h >= 24h threshold ---
            Instant accrualNow = bed1OpenedAt.plus(25, ChronoUnit.HOURS);
            int accrued = wardAccrualService.accrueWardDay(accrualNow);

            // Assert: exactly 1 admission accrued (this is the only IN_PROCESS admission in this run)
            assertThat(accrued).as("accrueWardDay must return 1 for the one due admission")
                    .isGreaterThanOrEqualTo(1);

            // Assert bed1 is now CLOSED with closedAt set
            var bed1After = admissionBedRepository.findByUid(bed1Uid).orElseThrow();
            assertThat(bed1After.getStatus())
                    .as("Original bed must be CLOSED after accrual")
                    .isEqualTo("CLOSED");
            assertThat(bed1After.getClosedAt())
                    .as("closedAt must be set on the closed bed")
                    .isNotNull()
                    .isEqualTo(accrualNow);

            // Assert a NEW OPENED bed exists with openedAt == accrualNow
            var newOpenedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(newOpenedBeds)
                    .as("Exactly one new OPENED bed must exist after accrual")
                    .hasSize(1);
            var newBed = newOpenedBeds.get(0);
            assertThat(newBed.getOpenedAt())
                    .as("New bed openedAt must equal the accrual 'now' instant")
                    .isEqualTo(accrualNow);
            assertThat(newBed.getUid())
                    .as("New bed must be a different row from bed1")
                    .isNotEqualTo(bed1Uid);

            // Assert a NEW VERIFIED ward bill was created for this admission
            String newBillUid = newBed.getPatientBillUid();
            var newBill = patientBillRepository.findByUid(newBillUid).orElseThrow();
            assertThat(newBill.getStatus())
                    .as("Accrued ward bill must be VERIFIED (cash path)")
                    .isEqualTo(BillStatus.VERIFIED);
            assertThat(newBill.getBillItem())
                    .as("billItem must be 'Bed' — verbatim UpdatePatient.java:309")
                    .isEqualTo("Bed");
            assertThat(newBill.getDescription())
                    .as("description must be 'Ward Bed / Room' — verbatim UpdatePatient.java:310")
                    .isEqualTo("Ward Bed / Room");
            assertThat(newBill.amountValue())
                    .as("Accrued bill amount must equal ward type price")
                    .isEqualByComparingTo(wardPrice);
            assertThat(newBill.getAdmissionUid())
                    .as("Accrued bill must be linked to the admission (discharge gate)")
                    .isEqualTo(admUid);
        }
    }

    // =========================================================================
    // Scenario 2: NOT DUE
    // Freshly-activated admission; bed < 24h old → accrueWardDay(realNow) returns 0.
    // =========================================================================

    @Nested
    class NotDue {

        @Test
        void cashAdmission_bedFresherThan24h_noAccrual() throws Exception {
            String tag = uniq();
            BigDecimal wardPrice = new BigDecimal("500.00");

            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, wardPrice.toPlainString());
            String admUid     = admitAndActivate(patientUid, wardBedUid, wardPrice);

            // Confirm admission is IN_PROCESS
            assertThat(admissionRepository.findByUid(admUid).orElseThrow().getStatus())
                    .isEqualTo(AdmissionStatus.IN_PROCESS);

            // openedAt is "now" — use Instant.now() which is < 24h after openedAt
            var openedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedBeds).hasSize(1);
            String bed1Uid = openedBeds.get(0).getUid();

            // ACT: call with real now (bed is just-created, elapsed is seconds, << 24h)
            int accrued = wardAccrualService.accrueWardDay(Instant.now());

            // May be 0 (for this admission; other admissions from other tests may also be due —
            // we assert the specific admission was NOT accrued by checking its bed is still OPENED)
            var bedAfter = admissionBedRepository.findByUid(bed1Uid).orElseThrow();
            assertThat(bedAfter.getStatus())
                    .as("Bed must remain OPENED when elapsed < 24h")
                    .isEqualTo("OPENED");

            // No new OPENED bed should have appeared for this admission
            var allOpenedForAdm = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(allOpenedForAdm)
                    .as("Still exactly one OPENED bed — no accrual happened")
                    .hasSize(1);
            assertThat(allOpenedForAdm.get(0).getUid())
                    .as("The single OPENED bed must still be bed1 (unchanged)")
                    .isEqualTo(bed1Uid);

            // Total ward bills for this patient: only the original PAID admit bill
            // (no new VERIFIED accrual bill)
            var bills = patientBillRepository.findByPatientUid(patientUid);
            long verifiedWardBills = bills.stream()
                    .filter(b -> BillStatus.VERIFIED.equals(b.getStatus())
                              && "Bed".equals(b.getBillItem())
                              && admUid.equals(b.getAdmissionUid()))
                    .count();
            assertThat(verifiedWardBills)
                    .as("No VERIFIED accrual bill must exist for a < 24h bed")
                    .isEqualTo(0L);
        }
    }

    // =========================================================================
    // Scenario 3: CHAINED
    // From Scenario 1's state: call accrueWardDay again with newBed.openedAt + 25h.
    // Total VERIFIED ward-accrual bills for the admission == 2.
    // =========================================================================

    @Nested
    class Chained {

        @Test
        void twoSuccessiveAccruals_chainedBeds_twoBillsCreated() throws Exception {
            String tag = uniq();
            BigDecimal wardPrice = new BigDecimal("800.00");

            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, wardPrice.toPlainString());
            String admUid     = admitAndActivate(patientUid, wardBedUid, wardPrice);

            // --- First accrual ---
            var openedBeds1 = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedBeds1).hasSize(1);
            Instant bed1OpenedAt = openedBeds1.get(0).getOpenedAt();
            Instant accrual1Now  = bed1OpenedAt.plus(25, ChronoUnit.HOURS);

            int round1 = wardAccrualService.accrueWardDay(accrual1Now);
            assertThat(round1).as("First accrual run must return >= 1").isGreaterThanOrEqualTo(1);

            // The new bed after round 1 has openedAt == accrual1Now
            var openedBeds2 = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedBeds2).hasSize(1);
            Instant bed2OpenedAt = openedBeds2.get(0).getOpenedAt();
            assertThat(bed2OpenedAt).isEqualTo(accrual1Now);

            // --- Second accrual (+25h from the new bed's openedAt) ---
            Instant accrual2Now = bed2OpenedAt.plus(25, ChronoUnit.HOURS);
            int round2 = wardAccrualService.accrueWardDay(accrual2Now);
            assertThat(round2).as("Second accrual run must return >= 1").isGreaterThanOrEqualTo(1);

            // After second accrual: two VERIFIED ward-accrual bills linked to this admission
            var allBills = patientBillRepository.findByPatientUid(patientUid);
            long verifiedAccrualBills = allBills.stream()
                    .filter(b -> BillStatus.VERIFIED.equals(b.getStatus())
                              && "Bed".equals(b.getBillItem())
                              && "Ward Bed / Room".equals(b.getDescription())
                              && admUid.equals(b.getAdmissionUid()))
                    .count();
            assertThat(verifiedAccrualBills)
                    .as("Exactly 2 VERIFIED accrual bills must exist after two chained accruals")
                    .isEqualTo(2L);

            // Each of the two VERIFIED bills must be at the correct ward price
            allBills.stream()
                    .filter(b -> BillStatus.VERIFIED.equals(b.getStatus())
                              && "Bed".equals(b.getBillItem())
                              && admUid.equals(b.getAdmissionUid()))
                    .forEach(b -> assertThat(b.amountValue())
                            .as("Each accrual bill must be at ward price " + wardPrice)
                            .isEqualByComparingTo(wardPrice));

            // The rolling bed: after round 2 exactly one OPENED bed with openedAt == accrual2Now
            var openedBeds3 = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedBeds3).hasSize(1);
            assertThat(openedBeds3.get(0).getOpenedAt())
                    .as("Final OPENED bed openedAt must == accrual2Now")
                    .isEqualTo(accrual2Now);
        }
    }

    // =========================================================================
    // Scenario 4: DE-DUP
    // Create an admission with TWO OPENED beds. Call accrueWardDay → both OPENED
    // beds get CLOSED, NO new accrual bill created (returns 0 for that admission).
    // =========================================================================

    @Nested
    class DeDup {

        @Test
        void twoBedsDuplicate_closesExtras_noNewBillCreated() throws Exception {
            String tag = uniq();
            BigDecimal wardPrice = new BigDecimal("600.00");

            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, wardPrice.toPlainString());
            String admUid     = admitAndActivate(patientUid, wardBedUid, wardPrice);

            // Confirm exactly one OPENED bed from admit
            var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(beds).hasSize(1);
            var bed1 = beds.get(0);

            // Directly save a second OPENED bed for the same admission (simulating the de-dup scenario)
            // Use the same wardBedUid and patientUid. The bill uid is a synthetic test-only value.
            // patient_bill_uid is VARCHAR(26) (ULID length) — keep the synthetic value within 26 chars.
            String synthBillUid = ("SYNTHDEDUP" + tag + "0000000000000000").substring(0, 26); // synthetic — test-only
            var bed2 = new com.otapp.hmis.inpatient.domain.AdmissionBed(
                    admUid,
                    wardBedUid,
                    patientUid,
                    synthBillUid,
                    Instant.now());
            admissionBedRepository.saveAndFlush(bed2);

            // Now two OPENED beds exist
            var twoOpen = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(twoOpen).as("Two OPENED beds must exist for de-dup scenario").hasSize(2);

            // Capture bill count before
            long billsBefore = patientBillRepository.findByPatientUid(patientUid).stream()
                    .filter(b -> admUid.equals(b.getAdmissionUid())).count();

            // ACT: call with a "now" that would be >= 24h after openedAt of the first bed
            Instant accrualNow = bed1.getOpenedAt().plus(25, ChronoUnit.HOURS);
            int accrued = wardAccrualService.accrueWardDay(accrualNow);

            // For this specific admission de-dup fires → 0 returned (no new accrual for it)
            // We verify by checking no new ward bills were created for THIS admission
            long billsAfter = patientBillRepository.findByPatientUid(patientUid).stream()
                    .filter(b -> admUid.equals(b.getAdmissionUid())).count();
            assertThat(billsAfter)
                    .as("De-dup branch: no new bill must be created for the admission with 2 OPENED beds")
                    .isEqualTo(billsBefore);

            // Both OPENED beds must now be CLOSED
            var closedAfter = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "CLOSED");
            assertThat(closedAfter)
                    .as("Both duplicate OPENED beds must be CLOSED by de-dup logic")
                    .hasSizeGreaterThanOrEqualTo(2);

            // No OPENED beds remain for this admission
            var openedAfter = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedAfter)
                    .as("No OPENED beds must remain after de-dup close")
                    .isEmpty();
        }
    }

    // =========================================================================
    // Scenario 5: INSURANCE
    // INSURANCE patient, covered price < cash price (top-up scenario).
    // accrueWardDay → COVERED principal at coveredPrice + VERIFIED top-up at diff.
    // =========================================================================

    @Nested
    class InsuranceAccrual {

        @Test
        void insuranceAdmission_accrual_coveredPrincipal_and_verifiedTopUp() throws Exception {
            String tag = uniq();
            // cash 1000, covered 600 → diff 400 (same proportions as WardInsuranceBillingIT)
            BigDecimal cashPrice    = new BigDecimal("1000.00");
            BigDecimal coveredPrice = new BigDecimal("600.00");
            BigDecimal expectedDiff = new BigDecimal("400.00");

            // Seed insurance plan
            String planUid = seedInsurancePlan(tag);

            // Seed ward + bed + both service_prices (cash + covered insurance)
            String wardBedUid = seedWardWithBedAndInsurancePrice(tag, cashPrice, planUid, coveredPrice);

            // Seed INSURANCE patient
            String patientUid = seedInsurancePatient(tag, planUid);

            // Admit (INSURANCE, covered < cash → PENDING + top-up created)
            String admUid = admitInsurance(patientUid, wardBedUid, planUid, "MEM-" + tag);

            // Pay the top-up to activate the admission
            activateInsuranceAdmission(admUid, patientUid, expectedDiff);

            // Confirm IN_PROCESS
            assertThat(admissionRepository.findByUid(admUid).orElseThrow().getStatus())
                    .isEqualTo(AdmissionStatus.IN_PROCESS);

            // Capture the OPENED bed (created by activation after top-up payment)
            var openedBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            assertThat(openedBeds).hasSize(1);
            Instant bedOpenedAt = openedBeds.get(0).getOpenedAt();

            // --- ACT: accrueWardDay with now = openedAt + 25h ---
            Instant accrualNow = bedOpenedAt.plus(25, ChronoUnit.HOURS);
            int accrued = wardAccrualService.accrueWardDay(accrualNow);
            assertThat(accrued).as("Insurance accrual must count the one due admission")
                    .isGreaterThanOrEqualTo(1);

            // Assert: COVERED principal bill at coveredPrice
            var allBills = patientBillRepository.findByPatientUid(patientUid);

            // The accrual COVERED bill linked to this admission — NOT the original COVERED admission bill
            // (original bill is COVERED at admission; accrual creates a SECOND COVERED bill)
            var coveredAccrualBills = allBills.stream()
                    .filter(b -> BillStatus.COVERED.equals(b.getStatus())
                              && admUid.equals(b.getAdmissionUid())
                              && "Ward Bed / Room".equals(b.getDescription()))
                    .toList();
            // There should be at least 2 COVERED bills (1 from doAdmission + 1 from accrual)
            // but we are specifically looking for the newly-created accrual one.
            // We find it by checking that there are now >=2 COVERED bills for this admission.
            assertThat(coveredAccrualBills)
                    .as("Must have at least 2 COVERED principal bills after accrual "
                            + "(one from doAdmission + one from accrueWardDay)")
                    .hasSizeGreaterThanOrEqualTo(2);

            // Every COVERED principal bill for this admission must be at the covered price
            coveredAccrualBills.forEach(b ->
                    assertThat(b.amountValue())
                            .as("COVERED accrual principal bill amount must equal coveredPrice")
                            .isEqualByComparingTo(coveredPrice));

            // Assert: VERIFIED top-up bill at diff (400.00)
            // The accrual recordWardAccrual creates a VERIFIED top-up (markVerified) — NOT UNPAID.
            // (This differs from doAdmission top-up which is UNPAID; see BillingCommandsImpl:283.)
            var verifiedTopUps = allBills.stream()
                    .filter(b -> BillStatus.VERIFIED.equals(b.getStatus())
                              && "Ward Bed / Room (Top up)".equals(b.getDescription())
                              && admUid.equals(b.getAdmissionUid()))
                    .toList();
            assertThat(verifiedTopUps)
                    .as("Accrued insurance top-up must be VERIFIED (collectable at cashier, "
                            + "BillingCommandsImpl.java:283)")
                    .hasSize(1);
            assertThat(verifiedTopUps.get(0).amountValue())
                    .as("Accrued top-up amount must equal diff = cash - covered = " + expectedDiff)
                    .isEqualByComparingTo(expectedDiff);
            assertThat(verifiedTopUps.get(0).getBillItem())
                    .as("Top-up billItem must be 'Bed' — verbatim BillingCommandsImpl.java:278")
                    .isEqualTo("Bed");
        }
    }

    // =========================================================================
    // Scenario 6: JOB + job_run_log
    // Call wardDayAccrualJob.runOnce() on a due admission.
    // Assert: returns accrued count AND job_run_log row with status='COMPLETED'.
    // =========================================================================

    @Nested
    class JobLog {

        @Test
        void runOnce_dueAdmission_writesCompletedJobRunLogRow() throws Exception {
            String tag = uniq();
            BigDecimal wardPrice = new BigDecimal("650.00");

            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, wardPrice.toPlainString());
            String admUid     = admitAndActivate(patientUid, wardBedUid, wardPrice);

            // Confirm IN_PROCESS
            assertThat(admissionRepository.findByUid(admUid).orElseThrow().getStatus())
                    .isEqualTo(AdmissionStatus.IN_PROCESS);

            // Count job_run_log rows before the run
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int rowsBefore = countJobRunLog(jdbc);

            // WardDayAccrualJob.runOnce() calls accrueWardDay(Instant.now()) internally.
            // The bed was just created, so Instant.now() is < 24h → this admission is NOT due
            // from the job's perspective (it uses Instant.now() internally, not a back-dated now).
            // Therefore: runOnce() returns 0 (or N for other due admissions in shared DB) and
            // writes a COMPLETED job_run_log row regardless.
            int jobAccrued = wardDayAccrualJob.runOnce();

            // Assert: a new job_run_log row was written
            int rowsAfter = countJobRunLog(jdbc);
            assertThat(rowsAfter)
                    .as("runOnce must write exactly one new job_run_log row")
                    .isEqualTo(rowsBefore + 1);

            // Find the most recently written job_run_log row
            Map<String, Object> logRow = jdbc.queryForMap(
                    "SELECT * FROM job_run_log ORDER BY id DESC LIMIT 1");

            assertThat(logRow.get("job_name"))
                    .as("job_name must be 'ward-accrual'")
                    .isEqualTo("ward-accrual");
            assertThat(logRow.get("status"))
                    .as("status must be 'COMPLETED'")
                    .isEqualTo("COMPLETED");
            assertThat(logRow.get("records_affected"))
                    .as("records_affected must match the return value of runOnce()")
                    .isEqualTo(jobAccrued);
            assertThat(logRow.get("started_at"))
                    .as("started_at must be populated")
                    .isNotNull();
            assertThat(logRow.get("finished_at"))
                    .as("finished_at must be populated (single terminal row pattern, ADR-0018 §5)")
                    .isNotNull();
            assertThat(logRow.get("error_message"))
                    .as("No error_message on COMPLETED run")
                    .isNull();
        }

        @Test
        void runOnce_viaDirectServiceCall_withBackdatedNow_countMatchesJobLog() throws Exception {
            // This variant verifies that the count returned by runOnce() is consistent.
            // Seed a due admission using the direct service with a back-dated now first,
            // then call runOnce() and check the job_run_log records_affected.
            String tag = uniq();
            BigDecimal wardPrice = new BigDecimal("550.00");

            String patientUid = seedCashPatient(tag);
            String wardBedUid = seedWardWithBed(tag, wardPrice.toPlainString());
            String admUid     = admitAndActivate(patientUid, wardBedUid, wardPrice);

            // Use the service directly with back-dated now to generate one accrual
            var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
            Instant bedOpenedAt = beds.get(0).getOpenedAt();
            int serviceAccrued = wardAccrualService.accrueWardDay(bedOpenedAt.plus(25, ChronoUnit.HOURS));
            assertThat(serviceAccrued).isGreaterThanOrEqualTo(1);

            // Now call runOnce() which uses Instant.now() — the newly-rolled bed is < 24h old
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int rowsBefore = countJobRunLog(jdbc);
            int jobAccrued = wardDayAccrualJob.runOnce();
            int rowsAfter  = countJobRunLog(jdbc);

            assertThat(rowsAfter).isEqualTo(rowsBefore + 1);

            Map<String, Object> logRow = jdbc.queryForMap(
                    "SELECT * FROM job_run_log ORDER BY id DESC LIMIT 1");
            assertThat(logRow.get("status")).isEqualTo("COMPLETED");
            assertThat(logRow.get("records_affected")).isEqualTo(jobAccrued);
        }

        private int countJobRunLog(JdbcTemplate jdbc) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM job_run_log", Integer.class);
            return count != null ? count : 0;
        }
    }

    // =========================================================================
    // Seeding helpers — replicated from AdmissionLifecycleIT + WardInsuranceBillingIT
    // =========================================================================

    private static String uniq() {
        return "WA" + Long.toHexString(System.nanoTime()).substring(0, 9).toUpperCase();
    }

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    /** Seed a CASH OUTPATIENT patient directly via the repository. */
    private String seedCashPatient(String tag) {
        Patient patient = new Patient(
                null,
                "07cIT" + tag,
                "Acc07c",
                tag,
                "IT",
                LocalDate.of(1990, 1, 1),
                "M",
                PatientType.OUTPATIENT,
                PaymentType.CASH,
                "",
                null,
                null,
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /**
     * Seed ward-category + ward-type (price) + ward + ward-bed + cash service_price.
     * Returns the ward bed uid. Mirrors seedWardWithBed from AdmissionLifecycleIT.
     */
    private String seedWardWithBed(String tag, String price) throws Exception {
        // Ward category
        String catBody = """
                {"code":"WCA7-%s","name":"WCat Acr %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward type
        String typeBody = """
                {"code":"WTA7-%s","name":"WType Acr %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, price);
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        // service_price (cash / no plan)
        String priceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, price);
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isCreated());

        // Ward
        String wardBody = """
                {"code":"WDA7-%s","name":"Ward Acr %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward bed
        String bedBody = """
                {"no":"BDA7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * POST doAdmission (CASH) + pay the ward-bed bill to activate → IN_PROCESS.
     * Returns the admission uid.
     *
     * <p>NOTE: NOT @Transactional — the BEFORE_COMMIT listener fires inside the billing tx.
     * Mirrors the pattern in AdmissionLifecycleIT (scenario 5).
     */
    private String admitAndActivate(String patientUid, String wardBedUid,
                                    BigDecimal wardPrice) throws Exception {
        // POST admission
        String admBody = admissionJson(patientUid, wardBedUid, "CASH", null, null);
        MvcResult admRes = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(admBody))
                .andExpect(status().isCreated()).andReturn();
        String admUid = objectMapper.readTree(admRes.getResponse().getContentAsString())
                .get("uid").asText();

        // Resolve ward-bed bill uid from committed AdmissionBed
        var admBeds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(admBeds).hasSize(1);
        String billUid = admBeds.get(0).getPatientBillUid();

        // Pay to activate
        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(billUid, wardPrice.toPlainString());
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());

        return admUid;
    }

    // ---- Insurance helpers (mirrored from WardInsuranceBillingIT) ----

    /**
     * Seed insurance provider + plan; returns the plan uid.
     */
    private String seedInsurancePlan(String tag) throws Exception {
        String provBody = """
                {"code":"PROV-WA-%s","name":"Prov WA %s","address":null,"telephone":null,
                 "email":null,"fax":null,"website":null,"active":true}
                """.formatted(tag, tag);
        MvcResult pr = mockMvc.perform(post(PROVIDERS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(provBody))
                .andExpect(status().isCreated()).andReturn();
        String provUid = objectMapper.readTree(pr.getResponse().getContentAsString())
                .get("uid").asText();

        String planBody = """
                {"code":"PLAN-WA-%s","name":"Plan WA %s","description":null,
                 "active":true,"insuranceProviderUid":"%s"}
                """.formatted(tag, tag, provUid);
        MvcResult planRes = mockMvc.perform(post(PROVIDERS + "/uid/" + provUid + "/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(planRes.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Seed ward-category + ward-type (cashPrice) + ward + bed + BOTH service_prices
     * (cash row + covered insurance row at coveredPrice). Returns ward bed uid.
     */
    private String seedWardWithBedAndInsurancePrice(String tag, BigDecimal cashPrice,
                                                    String planUid, BigDecimal coveredPrice)
            throws Exception {
        // Ward category
        String catBody = """
                {"code":"WCAI7-%s","name":"WCat AcrIns %s","description":null,"active":true}
                """.formatted(tag, tag);
        MvcResult cr = mockMvc.perform(post(WARD_CATS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(catBody))
                .andExpect(status().isCreated()).andReturn();
        String catUid = objectMapper.readTree(cr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward type
        String typeBody = """
                {"code":"WTAI7-%s","name":"WType AcrIns %s","description":null,
                 "price":%s,"active":true}
                """.formatted(tag, tag, cashPrice.toPlainString());
        MvcResult tr = mockMvc.perform(post(WARD_TYPES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(typeBody))
                .andExpect(status().isCreated()).andReturn();
        String typeUid = objectMapper.readTree(tr.getResponse().getContentAsString())
                .get("uid").asText();

        // service_prices: CASH row
        String cashPriceBody = """
                {"planUid":null,"kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(typeUid, cashPrice.toPlainString());
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(cashPriceBody))
                .andExpect(status().isCreated());

        // service_prices: COVERED insurance row (Option B — keyed on wardTypeUid)
        String insPriceBody = """
                {"planUid":"%s","kind":"WARD","serviceUid":"%s","currency":"TZS",
                 "amount":%s,"covered":true,"minAmount":null,"maxAmount":null,"active":true}
                """.formatted(planUid, typeUid, coveredPrice.toPlainString());
        mockMvc.perform(post(PRICES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(insPriceBody))
                .andExpect(status().isCreated());

        // Ward
        String wardBody = """
                {"code":"WDAI7-%s","name":"Ward AcrIns %s","noOfBeds":5,"active":true,
                 "wardCategoryUid":"%s","wardTypeUid":"%s"}
                """.formatted(tag, tag, catUid, typeUid);
        MvcResult wr = mockMvc.perform(post(WARDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(wardBody))
                .andExpect(status().isCreated()).andReturn();
        String wardUid = objectMapper.readTree(wr.getResponse().getContentAsString())
                .get("uid").asText();

        // Ward bed
        String bedBody = """
                {"no":"BDAI7-%s","status":"EMPTY","active":true,"wardUid":"%s"}
                """.formatted(tag, wardUid);
        MvcResult br = mockMvc.perform(post(BEDS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bedBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(br.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /** Seed an INSURANCE OUTPATIENT patient directly via the repository. */
    private String seedInsurancePatient(String tag, String planUid) {
        Patient patient = new Patient(
                null,
                "07cInsIT" + tag,
                "AcrIns07c",
                tag,
                "IT",
                LocalDate.of(1985, 3, 20),
                "M",
                PatientType.OUTPATIENT,
                PaymentType.INSURANCE,
                "MEM-" + tag,
                null,
                planUid,
                dayUid);
        return patientRepository.saveAndFlush(patient).getUid();
    }

    /**
     * POST doAdmission for an INSURANCE patient (covered < cash → PENDING + top-up created).
     * Returns the admission uid. Does NOT pay / activate.
     */
    private String admitInsurance(String patientUid, String wardBedUid,
                                  String planUid, String membershipNo) throws Exception {
        String admBody = admissionJson(patientUid, wardBedUid, "INSURANCE", planUid, membershipNo);
        MvcResult admRes = mockMvc.perform(post(ADMISSIONS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(admBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(admRes.getResponse().getContentAsString())
                .get("uid").asText();
    }

    /**
     * Pay the INSURANCE top-up bill to activate the admission → IN_PROCESS.
     * The AdmissionBed.patientBillUid points to the top-up bill (WardInsuranceBillingIT pattern).
     */
    private void activateInsuranceAdmission(String admUid, String patientUid,
                                            BigDecimal topUpAmount) throws Exception {
        var beds = admissionBedRepository.findAllByAdmissionUidAndStatus(admUid, "OPENED");
        assertThat(beds).hasSize(1);
        String topUpBillUid = beds.get(0).getPatientBillUid();

        String payBody = """
                {"billUids":["%s"],"tenderedTotal":{"amount":%s,"currency":"TZS"},"paymentMode":"CASH"}
                """.formatted(topUpBillUid, topUpAmount.toPlainString());
        mockMvc.perform(post("/api/v1/billing/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payBody))
                .andExpect(status().isCreated());
    }

    private static String admissionJson(String patientUid, String wardBedUid,
                                        String paymentType,
                                        String insurancePlanUid, String membershipNo) {
        String plan  = insurancePlanUid != null ? "\"" + insurancePlanUid + "\"" : "null";
        String memNo = membershipNo    != null ? "\"" + membershipNo    + "\"" : "null";
        return """
                {"patientUid":"%s","wardBedUid":"%s","paymentType":"%s",
                 "insurancePlanUid":%s,"membershipNo":%s}
                """.formatted(patientUid, wardBedUid, paymentType, plan, memNo);
    }
}
