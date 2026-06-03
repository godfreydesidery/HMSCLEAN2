package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.PatientBillDto;
import com.otapp.hmis.billing.domain.BillStatus;
import com.otapp.hmis.billing.domain.PatientBill;
import com.otapp.hmis.billing.domain.PatientBillRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient bill read service — the cashier collection queue (legacy cashier queue,
 * PatientBillResource.java:415+ {@code findByPatientUidAndStatusIn}).
 *
 * <p>Outpatient CASH charges are created "silent" (no invoice — BillingChargeService §"silent"),
 * so they are NOT discoverable via {@code listInvoices}. This service exposes a patient's bills
 * directly so the cashier can find the UNPAID/VERIFIED charges to collect.
 */
@Service
@RequiredArgsConstructor
public class BillQueryService {

    private final PatientBillRepository billRepository;
    private final PatientBillMapper billMapper;

    /**
     * List a patient's bills, optionally filtered to a single status.
     *
     * @param patientUid the patient uid (required)
     * @param status     optional status filter; {@code null} returns all bills for the patient
     * @return the patient's bills as DTOs
     */
    @Transactional(readOnly = true)
    public List<PatientBillDto> listBills(String patientUid, BillStatus status) {
        List<PatientBill> bills = (status != null)
                ? billRepository.findByPatientUidAndStatusIn(patientUid, List.of(status))
                : billRepository.findByPatientUid(patientUid);
        return billMapper.toDtoList(bills);
    }
}
