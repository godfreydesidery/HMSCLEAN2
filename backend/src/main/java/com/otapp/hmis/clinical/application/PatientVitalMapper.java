package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.PatientVitalDto;
import com.otapp.hmis.clinical.domain.PatientVital;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link PatientVital} entity to {@link PatientVitalDto} (inc-05 C5).
 *
 * <p>Maps encounter references to their UIDs. The {@code status} enum maps to its name()
 * String (EMPTY, SUBMITTED, ARCHIVED — plain identifiers, no hyphen conversion needed;
 * no converter like ConsultationStatusConverter is required here).
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §3).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface PatientVitalMapper {

    @Mapping(target = "consultationUid",    source = "consultation.uid")
    @Mapping(target = "nonConsultationUid", source = "nonConsultation.uid")
    @Mapping(target = "status",             expression = "java(patientVital.getStatus().name())")
    PatientVitalDto toDto(PatientVital patientVital);
}
