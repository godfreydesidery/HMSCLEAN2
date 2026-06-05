package com.otapp.hmis.billing.api;

import com.otapp.hmis.billing.domain.PaymentMode;
import com.otapp.hmis.masterdata.lookup.ServiceKind;
import java.math.BigDecimal;

/**
 * Cross-module charge request carried into {@link BillingCommands#recordClinicalCharge}
 * (build-spec §4.1).
 *
 * <p>All identifiers are public uids — NO internal {@code id} fields (ADR-0014 §1).
 * The record is immutable; callers build it in the clinical module and pass it
 * in-process with the caller's {@link com.otapp.hmis.shared.domain.TxAuditContext}.
 *
 * @param patientUid   loose cross-module ref to the patient (ULID)
 * @param planUid      loose cross-module ref to the insurance plan; null for cash patients
 * @param membershipNo patient's insurance membership number; null for cash
 * @param kind         service category ({@link ServiceKind})
 * @param serviceUid   the service entity uid; null only for {@link ServiceKind#REGISTRATION}
 * @param qty          quantity (1 for most services; multiplied for MEDICINE)
 * @param paymentType  requested payment mode
 * @param inpatient    whether the patient is currently admitted
 * @param followUp     true if this is a follow-up consultation (no charge — CR-20);
 *                     only meaningful when {@code kind == CONSULTATION}; must be
 *                     {@code false} for all other service kinds
 * @param billItem     optional override for the legacy {@code bill_item} label on the
 *                     created {@link com.otapp.hmis.billing.domain.PatientBill}
 *                     (inc-07 CR-07-Q13-billing-display). When {@code null}, the default
 *                     {@code labelFor(kind)} value derived in
 *                     {@code BillingChargeService} is used unchanged — existing caller
 *                     behaviour is fully preserved. Typical non-null values:
 *                     {@code "Medication"}, {@code "Consumable: <name>"}. Never PHI.
 * @param description  optional override for the human-readable description on the bill
 *                     (inc-07 CR-07-Q13-billing-display). When {@code null}, the existing
 *                     default (currently mirrors {@code billItem}) is used. Never PHI.
 * @param admissionUid optional loose uid of the owning inpatient admission (inc-07 07a).
 *                     Non-null only for ward-bed and consumable charges created during
 *                     {@code doAdmission}; null for all outpatient / OTC / registration
 *                     charges. Stored on the created {@link com.otapp.hmis.billing.domain.PatientBill}
 *                     to enable the discharge bills-cleared gate
 *                     ({@link com.otapp.hmis.billing.api.BillingQueries#admissionHasOutstandingBills}).
 *                     Never PHI (ULID only).
 */
public record ChargeRequest(
        String patientUid,
        String planUid,
        String membershipNo,
        ServiceKind kind,
        String serviceUid,
        BigDecimal qty,
        PaymentMode paymentType,
        boolean inpatient,
        boolean followUp,
        String billItem,
        String description,
        String admissionUid
) {
}
