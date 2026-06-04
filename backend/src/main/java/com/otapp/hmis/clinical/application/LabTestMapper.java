package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.LabTestAttachmentDto;
import com.otapp.hmis.clinical.application.dto.LabTestDto;
import com.otapp.hmis.clinical.domain.LabTest;
import com.otapp.hmis.clinical.domain.LabTestAttachment;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Manual mapper for {@link LabTest} and {@link LabTestAttachment} → DTO (C7).
 *
 * <p>Manual rather than MapStruct because the encounter binding logic (exactly-one-non-null
 * consultation/nonConsultation/admissionUid) requires conditional mapping that is cleaner
 * in explicit Java. Mirrors the pattern used by WorkingDiagnosisMapper (C6).
 *
 * <p>Package-private — consumed only within {@code clinical.application}.
 */
@Component
class LabTestMapper {

    /**
     * Map a {@link LabTest} entity to its DTO representation.
     *
     * <p>Encounter binding: exactly one of consultationUid / nonConsultationUid / admissionUid
     * will be non-null in the result, mirroring the DB num_nonnulls=1 constraint.
     *
     * @param lt the lab test entity (never null)
     * @return the DTO
     */
    LabTestDto toDto(LabTest lt) {
        String consultationUid = lt.getConsultation() != null
                ? lt.getConsultation().getUid() : null;
        String nonConsultationUid = lt.getNonConsultation() != null
                ? lt.getNonConsultation().getUid() : null;

        return new LabTestDto(
                lt.getUid(),
                lt.getStatus(),
                lt.isSettled(),
                lt.getLabTestTypeUid(),
                lt.getPatientUid(),
                lt.getPatientBillUid(),
                lt.getPaymentType(),
                lt.getMembershipNo(),
                lt.getInsurancePlanUid(),
                lt.getDiagnosisTypeUid(),
                lt.getClinicianUserUid(),
                consultationUid,
                nonConsultationUid,
                lt.getAdmissionUid(),
                lt.getResult(),
                lt.getReport(),
                lt.getDescription(),
                lt.getTestRange(),
                lt.getLevel(),
                lt.getUnit(),
                lt.getPriorReport(),
                lt.getReportAmendedByUserUid(),
                lt.getReportAmendedOnDayUid(),
                lt.getReportAmendedAt(),
                lt.getOrderedByUserUid(),
                lt.getOrderedOnDayUid(),
                lt.getOrderedAt(),
                lt.getAcceptedByUserUid(),
                lt.getAcceptedOnDayUid(),
                lt.getAcceptedAt(),
                lt.getHeldByUserUid(),
                lt.getHeldOnDayUid(),
                lt.getHeldAt(),
                lt.getCollectedByUserUid(),
                lt.getCollectedOnDayUid(),
                lt.getCollectedAt(),
                lt.getVerifiedByUserUid(),
                lt.getVerifiedOnDayUid(),
                lt.getVerifiedAt(),
                lt.getRejectedByUserUid(),
                lt.getRejectedOnDayUid(),
                lt.getRejectedAt(),
                lt.getRejectComment(),
                lt.getBusinessDayUid(),
                lt.getCreatedAt()
        );
    }

    /**
     * Map a list of {@link LabTest} entities to DTOs.
     */
    List<LabTestDto> toDtoList(List<LabTest> labTests) {
        return labTests.stream().map(this::toDto).toList();
    }

    /**
     * Map a {@link LabTestAttachment} entity to its DTO.
     */
    LabTestAttachmentDto toAttachmentDto(LabTestAttachment a) {
        return new LabTestAttachmentDto(
                a.getUid(),
                a.getName(),
                a.getFileName(),
                a.getLabTest().getUid(),
                a.getCreatedAt()
        );
    }

    /**
     * Map a list of {@link LabTestAttachment} entities to DTOs.
     */
    List<LabTestAttachmentDto> toAttachmentDtoList(List<LabTestAttachment> attachments) {
        return attachments.stream().map(this::toAttachmentDto).toList();
    }
}
