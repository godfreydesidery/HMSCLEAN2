package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.otapp.hmis.billing.application.CreditNoteService;
import com.otapp.hmis.billing.application.PaymentService;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.billing.domain.PatientCreditNote;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrency + format golden master for the PCN document number (build-spec §4.3, §7.2 item 6; CR-09).
 *
 * <p>The legacy {@code MAX(id)+1} request idiom read the counter <em>before</em> insert, so two
 * concurrent cancellations could mint the same {@code no} and trip the {@code UNIQUE(no)} constraint
 * on the second insert (04-extract-creditnote-numbering-refund.md §4). The sequence-backed
 * implementation must produce zero duplicates under contention. These tests run the real, writable
 * {@code cancelCharge} path ("two cashiers refunding at once"), not a bare {@code nextval}.
 *
 * <p>Not {@code @Transactional}: each cashier action must COMMIT in its own thread/transaction so the
 * unique constraint is actually exercised across concurrent inserts.
 */
class DocumentNumberConcurrencyIT extends AbstractIntegrationTest {

    private static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired CreditNoteService creditNoteService;
    @Autowired PaymentService paymentService;
    @Autowired PatientBillRepository billRepository;
    @Autowired BusinessDayService businessDayService;

    private String ensureDayOpen() {
        try {
            return businessDayService.currentUid();
        } catch (NoDayOpenException e) {
            businessDayService.openToday();
            return businessDayService.currentUid();
        }
    }

    // =========================================================================
    // PCN format = "PCN" + EAT-yyyyMMdd + "-" + unpadded seq  (Formater.java:14-17 + CR-09 EAT)
    // =========================================================================

    @Test
    void pcnNumber_hasLegacyFormatWithEatDate() {
        String dayUid = ensureDayOpen();
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "fmt-cashier");

        PatientBill bill = payNewBill("DNC-FMT-001", "1000.00", dayUid, ctx);
        PatientCreditNote note = creditNoteService.cancelCharge(bill.getUid(), "fmt", ctx).orElseThrow();

        String expectedDate = LocalDate.now(EAT).format(YYYYMMDD);
        assertThat(note.getNo())
                .as("PCN format: PCN{EAT-yyyyMMdd}-{seq}, suffix unpadded")
                .matches("^PCN\\d{8}-\\d+$")
                .startsWith("PCN" + expectedDate + "-");
    }

    // =========================================================================
    // Concurrency: N cashiers cancel N distinct paid bills at once → N distinct PCNs, zero errors
    // =========================================================================

    @Test
    void concurrentCancellations_produceDistinctPcnNumbers_zeroUniqueViolations() throws Exception {
        final int n = 8;
        String dayUid = ensureDayOpen();
        TxAuditContext ctx = new TxAuditContext(dayUid, Instant.now(), "race-cashier");

        // Pre-create + pay N bills (each commits before the race begins)
        List<String> billUids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            billUids.add(payNewBill("DNC-PAT-" + i, "1500.00", dayUid, ctx).getUid());
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try {
            for (String billUid : billUids) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        creditNoteService.cancelCharge(billUid, "Concurrent cancel", ctx)
                                .ifPresent(cn -> numbers.add(cn.getNo()));
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();                       // release all threads simultaneously
            assertThat(done.await(30, TimeUnit.SECONDS)).as("all cashier threads finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("no unique-violation or other errors under contention").isEmpty();
        assertThat(numbers).as("every concurrent cancellation produced a distinct PCN").hasSize(n);
    }

    // -------------------------------------------------------------------------

    private PatientBill payNewBill(String patientUid, String amount, String dayUid, TxAuditContext ctx) {
        PatientBill bill = billRepository.save(new PatientBill(
                patientUid, com.otapp.hmis.masterdata.lookup.ServiceKind.LAB_TEST,
                "Lab Test", "Lab Test", BigDecimal.ONE, Money.of(new BigDecimal(amount)), dayUid));
        paymentService.recordPayment(List.of(bill.getUid()),
                Money.of(new BigDecimal(amount)), PaymentMode.CASH, ctx);
        return bill;
    }
}
