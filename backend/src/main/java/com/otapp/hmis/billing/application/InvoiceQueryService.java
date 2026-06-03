package com.otapp.hmis.billing.application;

import com.otapp.hmis.billing.application.dto.PatientInvoiceDto;
import com.otapp.hmis.billing.application.dto.PatientPaymentDto;
import com.otapp.hmis.billing.application.dto.RecordPaymentRequest;
import com.otapp.hmis.billing.domain.InvoiceStatus;
import com.otapp.hmis.billing.domain.PatientInvoice;
import com.otapp.hmis.billing.domain.PatientInvoiceRepository;
import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.shared.application.MoneyMapper;
import com.otapp.hmis.shared.domain.Money;
import com.otapp.hmis.shared.domain.TxAuditContext;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service backing the billing REST controllers (P1 subset).
 *
 * <p>Provides:
 * <ul>
 *   <li>Invoice queue query (GET /billing/invoices?patientUid&status)</li>
 *   <li>Invoice detail (GET /billing/invoices/uid/{uid})</li>
 *   <li>Payment recording via {@link PaymentService} (POST /billing/invoices/uid/{uid}/payments)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class InvoiceQueryService {

    private final PatientInvoiceRepository invoiceRepository;
    private final PatientInvoiceMapper invoiceMapper;
    private final PatientPaymentMapper paymentMapper;
    private final PaymentService paymentService;
    private final MoneyMapper moneyMapper;

    /**
     * List invoices for a patient, optionally filtered by status (cashier queue).
     * GET /billing/invoices?patientUid=&status=
     */
    @Transactional(readOnly = true)
    public List<PatientInvoiceDto> listInvoices(String patientUid, InvoiceStatus status) {
        List<PatientInvoice> invoices;
        if (status != null) {
            invoices = invoiceRepository.findByPatientUidAndStatus(patientUid, status);
        } else {
            invoices = invoiceRepository.findByPatientUid(patientUid);
        }
        return invoiceMapper.toDtoList(invoices);
    }

    /**
     * Fetch a single invoice by uid (detail view).
     * GET /billing/invoices/uid/{uid}
     */
    @Transactional(readOnly = true)
    public PatientInvoiceDto getInvoice(String uid) {
        PatientInvoice invoice = invoiceRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("PatientInvoice not found: " + uid));
        return invoiceMapper.toDto(invoice);
    }

    /**
     * Record a payment for one or more bills linked to an invoice.
     * POST /billing/invoices/uid/{uid}/payments
     */
    @Transactional
    public PatientPaymentDto recordPayment(String invoiceUid, RecordPaymentRequest request,
                                           TxAuditContext ctx) {
        // Validate the invoice exists
        invoiceRepository.findByUid(invoiceUid)
                .orElseThrow(() -> new NotFoundException("PatientInvoice not found: " + invoiceUid));

        Money tendered = moneyMapper.toDomain(request.tenderedTotal());
        PaymentMode mode = request.paymentMode() != null ? request.paymentMode() : PaymentMode.CASH;

        var payment = paymentService.recordPayment(
                request.billUids(), tendered, mode, ctx);

        return paymentMapper.toDto(payment);
    }
}
