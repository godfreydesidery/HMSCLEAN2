package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.ReceiptDto;
import com.otapp.hmis.billing.domain.PatientPayment;
import com.otapp.hmis.billing.domain.PatientPaymentRepository;
import com.otapp.hmis.shared.application.MoneyMapper;
import com.otapp.hmis.shared.domain.BusinessDay;
import com.otapp.hmis.shared.domain.BusinessDayRepository;
import com.otapp.hmis.shared.error.NotFoundException;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS receipt assembly (build-spec §5.3, NET-NEW BILL-1 hardening). The receipt is anchored on the
 * {@link PatientPayment} uid (no separate receipt sequence in legacy). It surfaces the
 * billing-owned fields plus the business-day calendar date resolved from the shared
 * {@link BusinessDay}; the patient MR number is a Registration (inc-03) concern and is not yet
 * available, so the loose {@code patientUid} is carried.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final PatientPaymentRepository paymentRepository;
    private final BusinessDayRepository businessDayRepository;
    private final MoneyMapper moneyMapper;

    /**
     * Build the receipt for a recorded payment.
     *
     * @param paymentUid the payment (receipt anchor) uid
     * @return the receipt projection
     * @throws NotFoundException if no payment exists for the uid
     */
    @Transactional(readOnly = true)
    public ReceiptDto receiptForPayment(String paymentUid) {
        PatientPayment payment = paymentRepository.findByUid(paymentUid)
                .orElseThrow(() -> new NotFoundException("PatientPayment not found: " + paymentUid));

        LocalDate businessDate = businessDayRepository.findByUid(payment.getBusinessDayUid())
                .map(BusinessDay::getBusinessDate)
                .orElse(null);

        return new ReceiptDto(
                payment.getUid(),
                payment.getPatientUid(),
                moneyMapper.toDto(payment.getAmount()),
                payment.getPaymentType().name(),
                payment.getStatus(),
                payment.getCreatedBy(),
                payment.getBusinessDayUid(),
                businessDate);
    }
}
