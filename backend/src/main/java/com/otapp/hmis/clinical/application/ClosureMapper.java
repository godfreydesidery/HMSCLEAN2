package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.DeceasedNoteDto;
import com.otapp.hmis.clinical.application.dto.ReferralPlanDto;
import com.otapp.hmis.clinical.domain.Consultation;
import com.otapp.hmis.clinical.domain.DeceasedNote;
import com.otapp.hmis.clinical.domain.ReferralPlan;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * MapStruct mapper for {@link DeceasedNote} → {@link DeceasedNoteDto} and
 * {@link ReferralPlan} → {@link ReferralPlanDto} (inc-05 C12).
 *
 * <p>The {@code consultationUid} is derived from the lazily-loaded {@link Consultation}
 * via a named expression so that MapStruct does not try to map the full entity.
 * Both notes carry an {@code admissionUid} loose String that maps directly.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §2).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
abstract class ClosureMapper {

    /**
     * Map a {@link DeceasedNote} to {@link DeceasedNoteDto}.
     *
     * <p>The {@code consultationUid} is extracted by calling {@code getUid()} on the
     * lazy {@code Consultation} association (safe within a transaction — the session is open
     * because the mapper is called from a {@code @Transactional} service method).
     */
    @Mapping(target = "consultationUid", expression = "java(safeConsultationUid(note.getConsultation()))")
    abstract DeceasedNoteDto toDto(DeceasedNote note);

    /**
     * Map a {@link ReferralPlan} to {@link ReferralPlanDto}.
     */
    @Mapping(target = "consultationUid", expression = "java(safeConsultationUid(plan.getConsultation()))")
    abstract ReferralPlanDto toDto(ReferralPlan plan);

    /** Null-safe helper: return null if the consultation is null (admission path), else return its uid. */
    protected String safeConsultationUid(Consultation c) {
        return c == null ? null : c.getUid();
    }
}
