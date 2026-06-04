package com.otapp.hmis.clinical.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for the dispense (issueMedicine) endpoint (C10).
 *
 * <p>The issued quantity must equal the full prescribed qty (all-or-nothing rule —
 * PatientResource.java:3217-3245). The pharmacy uid is optional at the HTTP layer;
 * the service will use it to set {@code issue_pharmacy_uid} on the prescription.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>issueMedicine: PatientResource.java:3217-3245</li>
 *   <li>issued==qty all-or-nothing: PatientResource.java:3230</li>
 * </ul>
 */
public record IssueRequest(

        /**
         * Quantity to issue. Must be > 0 and must equal the prescribed qty (service enforces).
         */
        @NotNull @DecimalMin(value = "0.000001", message = "issued must be greater than zero")
        BigDecimal issued,

        /**
         * Loose uid of the pharmacy dispensing the medicine.
         * Nullable — set to null if pharmacy is unknown / not tracked.
         * Stored as {@code issue_pharmacy_uid} on the prescription.
         */
        String issuePharmacyUid

) {
}
