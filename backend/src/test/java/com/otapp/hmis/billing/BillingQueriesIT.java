package com.otapp.hmis.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.otapp.hmis.billing.api.BillingQueries;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import com.otapp.hmis.shared.domain.BusinessDayService;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.NoDayOpenException;
import com.otapp.hmis.shared.error.NotFoundException;
import com.otapp.hmis.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for the inc-06A C4 bill-status read seam ({@link BillingQueries}).
 *
 * <p>Proves the narrow read-only seam returns the live bill status and 404s on an unknown uid.
 * This seam exists for the legacy add_report bill-gate parity case (ITEM2/4) — see
 * {@link BillingQueries} for the ADR-0008 §6 scoped relaxation rationale.
 */
class BillingQueriesIT extends AbstractIntegrationTest {

    @Autowired BillingQueries billingQueries;
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

    private static String fakeUid(String prefix) {
        String base = (prefix + Long.toHexString(System.nanoTime())).replaceAll("[^A-Za-z0-9]", "");
        return (base + "00000000000000000000000000").substring(0, 26);
    }

    @Test
    void getBillStatus_returnsLiveStatus() {
        String dayUid = ensureDayOpen();
        PatientBill bill = new PatientBill(
                fakeUid("PAT"),
                ServiceKind.LAB_TEST,
                "Lab test",
                "Lab test (queries IT)",
                BigDecimal.ONE,
                Money.of(new BigDecimal("2500.00")),
                dayUid);
        String billUid = billRepository.save(bill).getUid();

        // A freshly-created CASH charge defaults to UNPAID — the seam must report it.
        assertThat(billingQueries.getBillStatus(billUid)).isEqualTo(BillStatus.UNPAID);
    }

    @Test
    void getBillStatus_unknownUid_throwsNotFound() {
        assertThatThrownBy(() -> billingQueries.getBillStatus("NOSUCHBILL0000000000000001"))
                .isInstanceOf(NotFoundException.class);
    }
}
