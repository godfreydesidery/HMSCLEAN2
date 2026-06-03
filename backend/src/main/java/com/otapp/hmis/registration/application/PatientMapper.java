package com.otapp.hmis.registration.application;

import com.otapp.hmis.registration.application.dto.PatientDto;
import com.otapp.hmis.registration.domain.Patient;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link Patient} entity to {@link PatientDto} (build-spec §1.3,
 * ADR-0014 §3).
 *
 * <p>Conventions (ADR-0014 §3):
 * <ul>
 *   <li>Package-private — not part of the module's public API surface.</li>
 *   <li>Spring component model is set globally ({@code -Amapstruct.defaultComponentModel=spring});
 *       instances are injected via constructor ({@code injectionStrategy = CONSTRUCTOR}).</li>
 *   <li>No repository injection (pure mapping only).</li>
 *   <li>{@code lastVisitAt} is always null in C3; it will be populated by the query layer
 *       in C5 (build-spec §8 C5).</li>
 * </ul>
 *
 * <p>Legacy citation: PatientResource.java:267-287 (patient response fields).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface PatientMapper {

    /**
     * Map a {@link Patient} entity to its response DTO.
     *
     * <p>{@code lastVisitAt} is not carried on the entity — it is set to {@code null} in
     * C3 and will be enriched in C5 by the search/last-visit layer.
     */
    @Mapping(target = "lastVisitAt", ignore = true)
    PatientDto toDto(Patient patient);
}
