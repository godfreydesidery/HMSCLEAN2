package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.GeneralExaminationDto;
import com.otapp.hmis.clinical.domain.GeneralExamination;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link GeneralExamination} entity to {@link GeneralExaminationDto}
 * (inc-05 C5).
 *
 * <p>Maps encounter references to their UIDs. All 11 vital-sign fields map by name directly
 * (String-to-String, no conversion needed).
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §3).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface GeneralExaminationMapper {

    @Mapping(target = "consultationUid",    source = "consultation.uid")
    @Mapping(target = "nonConsultationUid", source = "nonConsultation.uid")
    GeneralExaminationDto toDto(GeneralExamination generalExamination);
}
