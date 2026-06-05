package com.otapp.hmis.inpatient.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AdmissionBed}.
 *
 * <p>Only uid-keyed and bill-uid finders are exposed (ADR-0014 §1).
 *
 * <p>Legacy citation: PatientServiceImpl.java:1776-1783 (creation);
 * PatientBillResource.java:352-365 (settlement match via bill).
 */
public interface AdmissionBedRepository extends JpaRepository<AdmissionBed, Long> {

    /** Locate an admission-bed record by its public ULID. */
    Optional<AdmissionBed> findByUid(String uid);

    /**
     * Find the admission-bed record whose {@code patientBillUid} matches the given value.
     *
     * <p>This is the KEY lookup used by
     * {@link com.otapp.hmis.inpatient.application.AdmissionSettlementListener}: when a
     * {@link com.otapp.hmis.shared.event.BillSettledEvent} arrives carrying only the bill uid,
     * this finder resolves the associated admission-bed row — and from it, the admission uid —
     * so the admission can be flipped to IN_PROCESS.
     *
     * <p>Mirrors {@code ConsultationRepository.findByPatientBillUid} (clinical settlement seam,
     * ADR-0022 D5, inc-05 §5).
     *
     * @param patientBillUid the ULID of the ward-bed PatientBill
     * @return the matching AdmissionBed, or empty if no such bill is known here
     */
    Optional<AdmissionBed> findByPatientBillUid(String patientBillUid);

    /**
     * Find all OPENED bed records for an admission (discharge gap-check — 07a-3).
     *
     * @param admissionUid the loose uid of the admission
     * @param status       the status to match (typically "OPENED")
     */
    List<AdmissionBed> findAllByAdmissionUidAndStatus(String admissionUid, String status);
}
