package com.otapp.hmis.clinical.api;

import com.otapp.hmis.billing.domain.PaymentMode;

/**
 * Immutable command record for booking a consultation via {@link ConsultationBookingService}
 * (ADR-0022 D3/D5, inc-05 build-spec §3.2).
 *
 * <p>All fields are plain types or ULID strings — no entity references, no internal ids.
 * This is the cross-module contract between registration (the booking orchestrator) and
 * clinical (the consultation persistence owner).
 *
 * <p>The {@code settled} field is the booking-time pre-pass value:
 * <ul>
 *   <li>{@code true}  — INSURANCE/COVERED or follow-up NONE (auto-pass — no prepayment required)</li>
 *   <li>{@code false} — CASH-OPD (must pay before {@code open_consultation})</li>
 * </ul>
 * Computed by the caller as
 * {@code !SettlementPolicy.requiresPrepayment(paymentMode, false, false)}.
 *
 * <p>Uses {@code PaymentMode} from {@code billing.domain} (via the {@code billing::api}
 * named interface — ADR-0022 D5) to avoid a {@code clinical → registration} import edge.
 *
 * @param patientUid         loose uid of the patient (registration module)
 * @param visitUid           loose uid of the associated SUBSEQUENT visit (registration module)
 * @param clinicUid          loose uid of the target clinic (masterdata module)
 * @param clinicianUserUid   loose uid of the assigned clinician user (iam module)
 * @param patientBillUid     loose uid of the consultation-fee PatientBill (billing module)
 * @param paymentMode        payment mode, copied from patient at booking time
 * @param followUp           true if this is a follow-up (NONE bill — CR-20)
 * @param settled            booking-time settlement pre-pass flag (inc-05 §5)
 * @param membershipNo       insurance membership number (empty/null for CASH)
 * @param insurancePlanUid   loose uid of the insurance plan (null for CASH)
 * @param businessDayUid     loose uid of the current open business day
 */
public record BookConsultationCommand(
        String patientUid,
        String visitUid,
        String clinicUid,
        String clinicianUserUid,
        String patientBillUid,
        PaymentMode paymentMode,
        boolean followUp,
        boolean settled,
        String membershipNo,
        String insurancePlanUid,
        String businessDayUid
) {
}
