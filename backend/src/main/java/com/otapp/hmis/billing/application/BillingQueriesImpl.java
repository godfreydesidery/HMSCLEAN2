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

    /**
     * {@inheritDoc}
     *
     * <p>TODO(07a/07c): {@code PatientBill} carries no {@code admissionUid} column in the
     * current schema. Once chunk 07a/07c adds that column and populates it at ward/consumable
     * charge time, replace this stub with a real query such as:
     * {@code patientBillRepository.existsByAdmissionUidAndStatusIn(admissionUid,
     *     List.of(BillStatus.UNPAID, BillStatus.VERIFIED))}.
     * Until then this method safely returns {@code false} — the discharge gate in the inpatient
     * module will not fire prematurely (PatientResource.java:5342-5357, :5593-5603, :5851-5882).
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public boolean admissionHasOutstandingBills(String admissionUid) {
        // TODO(07a/07c): no admission_uid linkage on PatientBill yet — returns false until
        // chunk 07a/07c adds the column and the real query (see BillingQueries javadoc).
        return false;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    public boolean worklistAdmits(String billUid, boolean inpatient) {
        // A FILTER never throws — a missing/unresolvable bill is simply not admitted.
        BillStatus status = patientBillRepository.findByUid(billUid)
                .map(b -> b.getStatus())
                .orElse(null);
        if (status == null) {
            return false;
        }
        // Legacy pharmacy worklist filter (PatientResource.java:4347/4364/4381/4410):
        // OUTPATIENT/OUTSIDER admit PAID|COVERED; INPATIENT additionally admits VERIFIED
        // (inpatient credit/post-pay, NOT insurer-verification — D18).
        return switch (status) {
            case PAID, COVERED -> true;
            case VERIFIED -> inpatient;
            default -> false;            // UNPAID, NONE, CANCELED
        };
    }
}
