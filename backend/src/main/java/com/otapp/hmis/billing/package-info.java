/**
 * Billing &amp; Cashiering bounded context (build-spec inc-04, ADR-0008).
 *
 * <p>Exposes only the {@code billing.api} named interface ({@link BillingCommands} +
 * read projections). Consumes {@code masterdata::lookup} ({@link PriceLookup}) and
 * {@code iam::lookup}. No reverse edges; no async; no Envers.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientBill status machine — PatientServiceImpl.java:821-849, PatientBillResource.java:295-307</li>
 *   <li>Two-step cash-first build — PatientServiceImpl.java:821-849 (lab exemplar)</li>
 *   <li>Payment recording — PatientBillResource.java:269-393</li>
 *   <li>Insurance override — PatientServiceImpl.java:842-849</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "masterdata :: lookup", "iam :: lookup"})
package com.otapp.hmis.billing;
