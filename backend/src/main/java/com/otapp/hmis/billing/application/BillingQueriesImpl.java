package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.api.BillingQueries;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import com.otapp.hmis.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of {@link BillingQueries} (inc-06A C4, ITEM2/4).
 *
 * <p>Intentionally package-private in {@code billing.application} — callers depend only on the
 * {@link BillingQueries} interface from {@code billing.api}. Delegates to the existing
 * {@link PatientBillRepository} read path.
 *
 * <p>{@code @Transactional(readOnly = true)} with propagation REQUIRED — the read runs inside the
 * caller's (clinical) transaction. NOT {@code @PreAuthorize}-gated (authz at the caller's REST edge).
 */
@Service
@RequiredArgsConstructor
class BillingQueriesImpl implements BillingQueries {

    private final PatientBillRepository patientBillRepository;

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public BillStatus getBillStatus(String billUid) {
        return patientBillRepository.findByUid(billUid)
                .orElseThrow(() -> new NotFoundException("Bill not found: " + billUid))
                .getStatus();
    }
}
