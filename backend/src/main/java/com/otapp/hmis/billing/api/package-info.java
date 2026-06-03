/**
 * Named interface "api" — the ONLY types from the {@code billing} module that other
 * modules may reference (Spring Modulith, ADR-0001, ADR-0008, build-spec §4.1).
 *
 * <p>Exposes {@link com.otapp.hmis.billing.api.BillingCommands} (the cross-module API
 * for recording clinical charges) and the associated request/result records.
 * Downstream modules (Registration/inc-03, Clinical/inc-05) call
 * {@code BillingCommands.recordClinicalCharge} in their own transaction.
 *
 * <p>Implementation is package-private in {@code billing.application}. Controllers and
 * all other billing internals are NOT accessible from outside the module.
 */
@org.springframework.modulith.NamedInterface("api")
package com.otapp.hmis.billing.api;
