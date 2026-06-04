package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.PrescriptionBatchDto;
import com.otapp.hmis.clinical.application.dto.PrescriptionDto;
import com.otapp.hmis.clinical.domain.Prescription;
import com.otapp.hmis.clinical.domain.PrescriptionBatch;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Manual mapper for {@link Prescription} and {@link PrescriptionBatch} → DTO (C10).
 *
 * <p>Manual rather than MapStruct because the encounter binding logic (exactly-one-non-null
 * consultation/nonConsultation/admissionUid) requires conditional mapping that is cleaner
 * in explicit Java. Mirrors the pattern used by LabTestMapper (C7).
 *
 * <p>Package-private — consumed only within {@code clinical.application}.
 */
@Component
class PrescriptionMapper {

    /**
     * Map a {@link Prescription} entity to its DTO representation.
     *
     * <p>Encounter binding: exactly one of consultationUid / nonConsultationUid / admissionUid
     * will be non-null in the result, mirroring the DB num_nonnulls=1 constraint.
     *
     * <p>The {@code alerts} field is always an empty list in C10; C11 populates it.
     *
     * @param p the prescription entity (never null)
     * @return the DTO
     */
    PrescriptionDto toDto(Prescription p) {
        String consultationUid = p.getConsultation() != null
                ? p.getConsultation().getUid() : null;
        String nonConsultationUid = p.getNonConsultation() != null
                ? p.getNonConsultation().getUid() : null;

        return new PrescriptionDto(
                p.getUid(),
                p.getStatus().dbValue(),
                p.isSettled(),
                p.getMedicineUid(),
                p.getPatientUid(),
                p.getPatientBillUid(),
                p.getPaymentType(),
                p.getMembershipNo(),
                p.getInsurancePlanUid(),
                p.getClinicianUserUid(),
                p.getIssuePharmacyUid(),
                consultationUid,
                nonConsultationUid,
                p.getAdmissionUid(),
                p.getQty(),
                p.getIssued(),
                p.getBalance(),
                p.getDosage(),
                p.getFrequency(),
                p.getRoute(),
                p.getDays(),
                p.getReference(),
                p.getInstructions(),
                p.getOrderedByUserUid(),
                p.getOrderedOnDayUid(),
                p.getOrderedAt(),
                p.getApprovedByUserUid(),
                p.getApprovedOnDayUid(),
                p.getApprovedAt(),
                p.getBusinessDayUid(),
                p.getCreatedAt(),
                List.of()  // alerts — empty in C10; C11 fills this field
        );
    }

    /**
     * Map a list of {@link Prescription} entities to DTOs.
     */
    List<PrescriptionDto> toDtoList(List<Prescription> prescriptions) {
        return prescriptions.stream().map(this::toDto).toList();
    }

    /**
     * Map a {@link PrescriptionBatch} entity to its DTO.
     */
    PrescriptionBatchDto toBatchDto(PrescriptionBatch b) {
        return new PrescriptionBatchDto(
                b.getUid(),
                b.getPrescription().getUid(),
                b.getNo(),
                b.getManufacturedDate(),
                b.getExpiryDate(),
                b.getQty(),
                b.getCreatedAt()
        );
    }

    /**
     * Map a list of {@link PrescriptionBatch} entities to DTOs.
     */
    List<PrescriptionBatchDto> toBatchDtoList(List<PrescriptionBatch> batches) {
        return batches.stream().map(this::toBatchDto).toList();
    }
}
