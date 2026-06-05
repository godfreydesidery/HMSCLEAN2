package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.PrescriptionChartPort;
import com.otapp.hmis.clinical.api.PrescriptionChartView;
import com.otapp.hmis.clinical.api.RecordPrescriptionChartCommand;
import com.otapp.hmis.shared.domain.TxAuditContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Package-private stub implementation of {@link PrescriptionChartPort} (inc-07 SEAM-2).
 *
 * <p>This class is intentionally package-private in {@code clinical.application} — callers
 * depend only on the {@link PrescriptionChartPort} interface from {@code clinical.api}.
 *
 * <p><strong>STUB — NOT PRODUCTION CODE.</strong> Every method throws
 * {@link UnsupportedOperationException} with a {@code TODO(07b)} marker. The full
 * implementation — including:
 * <ul>
 *   <li>GIVEN guard (PatientServiceImpl.java:2544)</li>
 *   <li>admission IN-PROCESS + nurse-uid guard (PatientServiceImpl.java:2564-2577)</li>
 *   <li>24-hour delete-window guard</li>
 *   <li>persist / read / delete against {@code PatientPrescriptionChartRepository}</li>
 * </ul>
 * — is built in inc-07 chunk 07b. This stub allows compilation and Spring context startup
 * without that work being done yet.
 *
 * <p>Legacy citations:
 * <ul>
 *   <li>GIVEN guard: PatientServiceImpl.java:2544</li>
 *   <li>admission + nurse guard: PatientServiceImpl.java:2564-2577</li>
 *   <li>Entity shape: PatientPrescriptionChart.java:34-82</li>
 * </ul>
 *
 * <p>inc-07 SEAM-2 / ADR-0008 §1.
 */
@Service
class PrescriptionChartPortImpl implements PrescriptionChartPort {

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — TODO(07b): implement write guards and persist
     */
    @Override
    public PrescriptionChartView record(RecordPrescriptionChartCommand cmd, TxAuditContext ctx) {
        // TODO(07b): implement record() — GIVEN guard (PatientServiceImpl.java:2544),
        //            admission IN-PROCESS + nurse-uid guard (PatientServiceImpl.java:2564-2577),
        //            persist PatientPrescriptionChart, return projection.
        throw new UnsupportedOperationException(
                "PrescriptionChartPort impl: built in inc-07 chunk 07b");
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — TODO(07b): implement repository read
     */
    @Override
    public List<PrescriptionChartView> findByAdmission(String admissionUid) {
        // TODO(07b): implement findByAdmission() — query PatientPrescriptionChartRepository
        //            by admissionUid, map entities to PrescriptionChartView projections.
        throw new UnsupportedOperationException(
                "PrescriptionChartPort impl: built in inc-07 chunk 07b");
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — TODO(07b): implement 24h window guard + delete
     */
    @Override
    public void delete24hWindow(String chartUid, TxAuditContext ctx) {
        // TODO(07b): implement delete24hWindow() — load chart by uid, verify createdAt is within
        //            24 hours, soft-delete or hard-delete, audit record.
        throw new UnsupportedOperationException(
                "PrescriptionChartPort impl: built in inc-07 chunk 07b");
    }
}
