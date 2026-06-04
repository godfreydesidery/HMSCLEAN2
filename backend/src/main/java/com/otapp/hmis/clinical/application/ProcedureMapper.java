package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.ProcedureDto;
import com.otapp.hmis.clinical.domain.Procedure;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Manual mapper for {@link Procedure} → {@link ProcedureDto} (C9).
 *
 * <p>Manual rather than MapStruct because the encounter binding logic (exactly-one-non-null
 * consultation/nonConsultation/admissionUid) requires conditional mapping that is cleaner
 * in explicit Java. Mirrors the pattern used by LabTestMapper (C7) and RadiologyMapper (C8).
 *
 * <p>Package-private — consumed only within {@code clinical.application}.
 */
@Component
class ProcedureMapper {

    /**
     * Map a {@link Procedure} entity to its DTO representation.
     *
     * <p>Encounter binding: exactly one of consultationUid / nonConsultationUid / admissionUid
     * will be non-null in the result, mirroring the DB num_nonnulls=1 constraint.
     *
     * @param p the procedure entity (never null)
     * @return the DTO
     */
    ProcedureDto toDto(Procedure p) {
        String consultationUid = p.getConsultation() != null
                ? p.getConsultation().getUid() : null;
        String nonConsultationUid = p.getNonConsultation() != null
                ? p.getNonConsultation().getUid() : null;

        return new ProcedureDto(
                p.getUid(),
                p.getStatus(),
                p.isSettled(),
                p.getProcedureTypeUid(),
                p.getPatientUid(),
                p.getPatientBillUid(),
                p.getPaymentType(),
                p.getMembershipNo(),
                p.getInsurancePlanUid(),
                p.getDiagnosisTypeUid(),
                p.getClinicianUserUid(),
                p.getTheatreUid(),
                consultationUid,
                nonConsultationUid,
                p.getAdmissionUid(),
                p.getNote(),
                p.getType(),
                p.getDiagnosis(),
                p.getProcDate(),
                p.getProcTime(),
                p.getHours(),
                p.getMinutes(),
                p.getOrderedByUserUid(),
                p.getOrderedOnDayUid(),
                p.getOrderedAt(),
                p.getAcceptedByUserUid(),
                p.getAcceptedOnDayUid(),
                p.getAcceptedAt(),
                p.getVerifiedByUserUid(),
                p.getVerifiedOnDayUid(),
                p.getVerifiedAt(),
                p.getRejectedByUserUid(),
                p.getRejectedOnDayUid(),
                p.getRejectedAt(),
                p.getRejectComment(),
                p.getBusinessDayUid(),
                p.getCreatedAt()
        );
    }

    /**
     * Map a list of {@link Procedure} entities to DTOs.
     */
    List<ProcedureDto> toDtoList(List<Procedure> procedures) {
        return procedures.stream().map(this::toDto).toList();
    }
}
