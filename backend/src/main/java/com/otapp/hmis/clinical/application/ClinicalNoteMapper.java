package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.ClinicalNoteDto;
import com.otapp.hmis.clinical.domain.ClinicalNote;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link ClinicalNote} entity to {@link ClinicalNoteDto} (inc-05 C5).
 *
 * <p>Maps encounter references to their UIDs: the @OneToOne {@code consultation} and
 * {@code nonConsultation} fields are mapped to their respective uid Strings by traversing
 * the entity (MapStruct handles the null-safe traversal).
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §3).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface ClinicalNoteMapper {

    @Mapping(target = "consultationUid",    source = "consultation.uid")
    @Mapping(target = "nonConsultationUid", source = "nonConsultation.uid")
    ClinicalNoteDto toDto(ClinicalNote clinicalNote);
}
