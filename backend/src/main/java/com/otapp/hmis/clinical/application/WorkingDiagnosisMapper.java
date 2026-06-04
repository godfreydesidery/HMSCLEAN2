package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.WorkingDiagnosisDto;
import com.otapp.hmis.clinical.domain.WorkingDiagnosis;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link WorkingDiagnosis} entity to {@link WorkingDiagnosisDto}
 * (inc-05 C6).
 *
 * <p>Maps the intra-module {@code @ManyToOne consultation} association to its uid String
 * via MapStruct's null-safe traversal. All other fields are direct column mappings.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §3).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface WorkingDiagnosisMapper {

    @Mapping(target = "consultationUid", source = "consultation.uid")
    WorkingDiagnosisDto toDto(WorkingDiagnosis entity);

    List<WorkingDiagnosisDto> toDtoList(List<WorkingDiagnosis> entities);
}
