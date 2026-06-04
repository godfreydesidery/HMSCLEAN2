package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.api.NonConsultationDto;
import com.otapp.hmis.clinical.domain.NonConsultation;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link NonConsultation} entity to {@link NonConsultationDto} (ADR-0014 §3).
 *
 * <p>Conventions (ADR-0014 §3):
 * <ul>
 *   <li>Package-private — not part of the module's public API surface.</li>
 *   <li>Spring component model applied globally; CONSTRUCTOR injection strategy.</li>
 *   <li>No repository injection — pure mapping only.</li>
 * </ul>
 *
 * <p>{@code status} is mapped via {@code dbValue()} to preserve the exact hyphenated legacy
 * DB-spelling (e.g. "IN-PROCESS", "SIGNED-OUT") in the DTO response — identical pattern to
 * {@link ConsultationMapper}.
 *
 * <p>Legacy citation: NonConsultation.java:44-80 (entity shape).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface NonConsultationMapper {

    /**
     * Map a {@link NonConsultation} entity to its response DTO.
     *
     * <p>{@code status} is mapped via {@code dbValue()} to preserve the exact hyphenated
     * DB-spelling (e.g. "IN-PROCESS") rather than the Java enum constant name.
     */
    @Mapping(target = "status", expression = "java(nonConsultation.getStatus().dbValue())")
    NonConsultationDto toDto(NonConsultation nonConsultation);
}
