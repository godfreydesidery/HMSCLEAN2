package com.otapp.hmis.registration.application;

import com.otapp.hmis.registration.application.dto.ConsultationDto;
import com.otapp.hmis.registration.domain.Consultation;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link Consultation} entity to {@link ConsultationDto} (build-spec §3.2,
 * ADR-0014 §3).
 *
 * <p>Conventions (ADR-0014 §3):
 * <ul>
 *   <li>Package-private — not part of the module's public API surface.</li>
 *   <li>Spring component model applied globally; CONSTRUCTOR injection strategy.</li>
 *   <li>No repository injection — pure mapping only.</li>
 * </ul>
 *
 * <p>{@code patientUid} is derived from {@code consultation.patient.uid} via MapStruct's
 * nested property expression. {@code status} is mapped from the enum's {@code name()} via
 * a string target (CR-18: status is PENDING-only in inc-03; the string avoids a DTO enum
 * coupling that would break when inc-05 widens the status vocabulary).
 *
 * <p>Legacy citation: PatientServiceImpl.java:425-475 (consultation response fields).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface ConsultationMapper {

    /**
     * Map a {@link Consultation} entity to its response DTO.
     *
     * <p>{@code patientUid} is sourced from the nested {@code patient.uid} association.
     * {@code status} is mapped as a String from the enum name so that future status values
     * added in inc-05 do not require a DTO schema change.
     */
    @Mapping(target = "patientUid", source = "patient.uid")
    @Mapping(target = "status",     expression = "java(consultation.getStatus().name())")
    ConsultationDto toDto(Consultation consultation);
}
