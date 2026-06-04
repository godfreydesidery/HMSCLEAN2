package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.ConsultationDto;
import com.otapp.hmis.clinical.domain.Consultation;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link Consultation} entity to {@link ConsultationDto} (ADR-0014 §3,
 * ADR-0022 D6).
 *
 * <p>Conventions (ADR-0014 §3):
 * <ul>
 *   <li>Package-private — not part of the module's public API surface.</li>
 *   <li>Spring component model applied globally; CONSTRUCTOR injection strategy.</li>
 *   <li>No repository injection — pure mapping only.</li>
 * </ul>
 *
 * <p>{@code patientUid} is now a direct field on the entity (no more nested {@code patient.uid}
 * traversal — ADR-0022 D2). {@code status} is mapped from the enum's {@code dbValue()} to
 * preserve the exact hyphenated legacy spellings in the DTO (e.g. "IN-PROCESS", "SIGNED-OUT").
 *
 * <p>Moved from {@code registration.application.ConsultationMapper} → {@code clinical.application}
 * per ADR-0022 D1/D6.
 *
 * <p>Legacy citation: PatientServiceImpl.java:425-475 (consultation response fields).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface ConsultationMapper {

    /**
     * Map a {@link Consultation} entity to its response DTO.
     *
     * <p>{@code status} is mapped via {@code dbValue()} to preserve the exact hyphenated
     * DB-spelling (e.g. "IN-PROCESS") rather than the Java enum constant name.
     */
    @Mapping(target = "status", expression = "java(consultation.getStatus().dbValue())")
    ConsultationDto toDto(Consultation consultation);
}
