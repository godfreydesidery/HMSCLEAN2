package com.otapp.hmis.billing.api;

import com.otapp.hmis.shared.domain.TxAuditContext;

/**
 * Cross-module API for recording a clinical charge (build-spec §4.1).
 *
 * <p>Callers (Registration/inc-03, Clinical/inc-05) invoke this inside their own
 * transaction — propagation REQUIRED (caller's tx). NO {@code @Async}, NO
 * {@code REQUIRES_NEW}. The charge is atomic with the clinical encounter.
 *
 * <p>NOT a REST endpoint. NOT {@code @PreAuthorize}-gated. Authorization is enforced
 * once at the caller's REST edge; this in-process Modulith call is trusted.
 *
 * <p>Implementation is package-private in {@code billing.application.BillingCommandsImpl}.
 *
 * <p>Legacy citations: PatientServiceImpl.java:821-849 (charge creation — billing was
 * inline in the clinical service; this interface is the modernised boundary).
 */
public interface BillingCommands {

    /**
     * Record a charge for one clinical/registration service item.
     *
     * <p>Runs the two-step cash-first then insurance-override pricing engine (build-spec §2.1),
     * the per-service not-covered fallback asymmetry (§2.2), and the PENDING-invoice accumulator.
     *
     * @param req the charge request (uids only — no internal ids)
     * @param ctx transaction audit context (dayUid, actor)
     * @return the charge result (billUid, status, amount, coverage)
     */
    ChargeResult recordClinicalCharge(ChargeRequest req, TxAuditContext ctx);
}
