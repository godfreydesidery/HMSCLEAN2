package com.otapp.hmis.clinical.api;

import java.util.List;

/**
 * Published cross-module read surface for the {@code clinical} Prescription aggregate, consumed by
 * the {@code pharmacy} module (inc-08a, AC-RX-PRE-02/08).
 *
 * <p>Today {@code PrescriptionPort} is intra-module (controller-only) in
 * {@code clinical.application}; the {@code pharmacy} module cannot legally depend on it. This is the
 * published seam — mirroring {@link ConsultationLookup} — implemented package-private in
 * {@code clinical.application} (e.g. {@code PrescriptionApiAdapter} delegating to the existing
 * package-private {@code PrescriptionService}).
 *
 * <p>The dependency edge is one-directional: {@code pharmacy → clinical :: api}. There is NO
 * {@code clinical → pharmacy} edge — clinical never calls back into pharmacy.
 */
public interface PrescriptionReadPort {

    /**
     * Read a prescription by its public uid.
     *
     * @param prescriptionUid the ULID of the prescription
     * @return the published projection (no internal id; status as the exact DB string)
     * @throws com.otapp.hmis.shared.error.NotFoundException if no prescription with that uid exists
     */
    PrescriptionView getByUid(String prescriptionUid);

    /**
     * Read the lot-trace rows recorded for a prescription (read-only).
     *
     * @param prescriptionUid the ULID of the prescription
     * @return the lot-trace projections, oldest first (empty if none)
     * @throws com.otapp.hmis.shared.error.NotFoundException if no prescription with that uid exists
     */
    List<PrescriptionBatchView> listBatches(String prescriptionUid);
}
