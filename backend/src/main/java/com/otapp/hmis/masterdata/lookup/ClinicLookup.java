package com.otapp.hmis.masterdata.lookup;

import java.util.Optional;

/**
 * Cross-module name lookup for the {@code Clinic} catalog.
 *
 * <p>This interface is part of the {@code masterdata :: lookup} named interface (Spring
 * Modulith, ADR-0001, ADR-0008) — the ONLY types from the {@code masterdata} module that
 * other modules may reference. The implementation is package-private in
 * {@code masterdata.application}.
 *
 * <p>The clinical module uses {@link #nameByUid(String)} in
 * {@code ConsultationTransferService.completePendingTransferOnRebook} to resolve the
 * destination clinic name for the verbatim legacy error message:
 * "Can not send to the specified clinic. Patient has been transfered to &lt;clinicName&gt;
 * clinic. Please send the patient to the specified clinic"
 * (PatientServiceImpl.java:434 — inc-05 F9).
 *
 * <p>Only the clinic name is returned — the clinical module never needs any other
 * Clinic fields (ADR-0008 §1: cross-module refs are loose uids or minimal data only).
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>PatientServiceImpl.java:434 — clinic name interpolated into 422 message</li>
 * </ul>
 */
public interface ClinicLookup {

    /**
     * Return the name of a clinic by uid, or empty if not found.
     *
     * <p>The caller falls back to the uid string if the clinic is not found (defensive:
     * the uid itself is still informative even if the clinic row has been deleted).
     *
     * @param uid the ULID of the clinic
     * @return the clinic name, or empty if not found
     */
    Optional<String> nameByUid(String uid);
}
