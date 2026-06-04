package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.RadiologyAttachmentDto;
import com.otapp.hmis.clinical.application.dto.RadiologyDto;
import com.otapp.hmis.clinical.domain.Radiology;
import com.otapp.hmis.clinical.domain.RadiologyAttachment;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Manual mapper for {@link Radiology} and {@link RadiologyAttachment} → DTO (C8).
 *
 * <p>Manual rather than MapStruct because the encounter binding logic (exactly-one-non-null
 * consultation/nonConsultation/admissionUid) requires conditional mapping that is cleaner
 * in explicit Java. Mirrors the pattern used by LabTestMapper (C7).
 *
 * <p>The inline {@code attachment} blob is NOT included in {@link RadiologyDto} — only
 * a boolean {@code hasAttachment} flag is set. This avoids sending large binary data over
 * the API for every radiology query. A future dedicated download endpoint can return the blob.
 *
 * <p>Package-private — consumed only within {@code clinical.application}.
 */
@Component
class RadiologyMapper {

    /**
     * Map a {@link Radiology} entity to its DTO representation.
     *
     * <p>Encounter binding: exactly one of consultationUid / nonConsultationUid / admissionUid
     * will be non-null in the result, mirroring the DB num_nonnulls=1 constraint.
     *
     * <p>The inline attachment blob is represented as {@code hasAttachment} (boolean) — the
     * actual bytes are not included in the DTO to avoid bandwidth overhead.
     *
     * @param r the radiology entity (never null)
     * @return the DTO
     */
    RadiologyDto toDto(Radiology r) {
        String consultationUid = r.getConsultation() != null
                ? r.getConsultation().getUid() : null;
        String nonConsultationUid = r.getNonConsultation() != null
                ? r.getNonConsultation().getUid() : null;

        return new RadiologyDto(
                r.getUid(),
                r.getStatus(),
                r.isSettled(),
                r.getRadiologyTypeUid(),
                r.getPatientUid(),
                r.getPatientBillUid(),
                r.getPaymentType(),
                r.getMembershipNo(),
                r.getInsurancePlanUid(),
                r.getDiagnosisTypeUid(),
                r.getClinicianUserUid(),
                consultationUid,
                nonConsultationUid,
                r.getAdmissionUid(),
                r.getResult(),
                r.getReport(),
                r.getDescription(),
                r.getAttachment() != null && r.getAttachment().length > 0,
                r.getOrderedByUserUid(),
                r.getOrderedOnDayUid(),
                r.getOrderedAt(),
                r.getAcceptedByUserUid(),
                r.getAcceptedOnDayUid(),
                r.getAcceptedAt(),
                r.getHeldByUserUid(),
                r.getHeldOnDayUid(),
                r.getHeldAt(),
                r.getVerifiedByUserUid(),
                r.getVerifiedOnDayUid(),
                r.getVerifiedAt(),
                r.getRejectedByUserUid(),
                r.getRejectedOnDayUid(),
                r.getRejectedAt(),
                r.getRejectComment(),
                r.getBusinessDayUid(),
                r.getCreatedAt()
        );
    }

    /**
     * Map a list of {@link Radiology} entities to DTOs.
     */
    List<RadiologyDto> toDtoList(List<Radiology> radiologies) {
        return radiologies.stream().map(this::toDto).toList();
    }

    /**
     * Map a {@link RadiologyAttachment} entity to its DTO.
     */
    RadiologyAttachmentDto toAttachmentDto(RadiologyAttachment a) {
        return new RadiologyAttachmentDto(
                a.getUid(),
                a.getName(),
                a.getFileName(),
                a.getRadiology().getUid(),
                a.getCreatedAt()
        );
    }

    /**
     * Map a list of {@link RadiologyAttachment} entities to DTOs.
     */
    List<RadiologyAttachmentDto> toAttachmentDtoList(List<RadiologyAttachment> attachments) {
        return attachments.stream().map(this::toAttachmentDto).toList();
    }
}
