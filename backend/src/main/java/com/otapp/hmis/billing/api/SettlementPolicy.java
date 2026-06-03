package com.otapp.hmis.billing.api;

import com.otapp.hmis.billing.domain.PaymentMode;

/**
 * The pay-before-service scoping rule (CR-05, RATIFIED scoped; build-spec §4.2). Published in
 * {@code billing.api} as the single source of truth so the clinical modules (inc-03/05/06) decide
 * consistently at their {@code accept()} — they evaluate it against their OWN local {@code settled}
 * flag and never call back into billing (no reverse edge; ADR-0008 §6).
 *
 * <p><strong>Scope (HDE BLOCKER-CLINICAL-2 — a blanket gate is a patient-safety hazard):</strong>
 * <ul>
 *   <li>CASH outpatient/outsider → prepayment REQUIRED (the only blocked case);</li>
 *   <li>insurance (COVERED) → auto-pass;</li>
 *   <li>inpatient → pass (VERIFIED/UNPAID settle at discharge);</li>
 *   <li>emergency / unregistered → bypass.</li>
 * </ul>
 *
 * <p>Legacy had NO hard gate — only a UI filter (03-extract-cashier-collection-eod.md §5). This is
 * net-new hardening; the rule lives here, the enforcement at the clinical edge (inc-05/06).
 */
public final class SettlementPolicy {

    private SettlementPolicy() {
        // Policy holder — no instances.
    }

    /**
     * Whether a charge with this context must be settled (paid) before the service is rendered.
     *
     * @param paymentType the charge payment mode
     * @param inpatient   whether the patient is currently admitted
     * @param emergency   whether this is an emergency/unregistered encounter
     * @return {@code true} only for CASH, non-inpatient, non-emergency charges
     */
    public static boolean requiresPrepayment(PaymentMode paymentType, boolean inpatient, boolean emergency) {
        if (paymentType == PaymentMode.INSURANCE) {
            return false;   // COVERED auto-passes
        }
        if (inpatient) {
            return false;   // settle at discharge
        }
        if (emergency) {
            return false;   // emergency / unregistered bypass
        }
        return true;        // CASH outpatient / outsider
    }

    /**
     * Enforce the gate: throw {@link PayBeforeServiceException} (422) when prepayment is required
     * for this context but the (clinical-local) {@code settled} flag is still false. A no-op for
     * every bypassing case.
     *
     * @param settled     the caller's local settlement flag for the charge
     * @param paymentType the charge payment mode
     * @param inpatient   whether the patient is currently admitted
     * @param emergency   whether this is an emergency/unregistered encounter
     * @param billUid      the charge's bill uid (for the error detail), may be null
     */
    public static void requireSettled(boolean settled, PaymentMode paymentType,
                                      boolean inpatient, boolean emergency, String billUid) {
        if (requiresPrepayment(paymentType, inpatient, emergency) && !settled) {
            throw new PayBeforeServiceException(billUid);
        }
    }
}
