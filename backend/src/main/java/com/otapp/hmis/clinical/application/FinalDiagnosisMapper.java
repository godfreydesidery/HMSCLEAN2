package com.otapp.hmis.clinical.application;

import com.otapp.hmis.clinical.application.dto.FinalDiagnosisDto;
import com.otapp.hmis.clinical.domain.FinalDiagnosis;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link FinalDiagnosis} entity to {@link FinalDiagnosisDto}
 * (inc-05 C6).
 *
 * <p>Byte-for-byte structural twin of {@link WorkingDiagnosisMapper} for the final diagnoses
 * table. Maps the intra-module {@code @ManyToOne consultation} association to its uid String.
 *
 * <p>Package-private — not part of the module's public API surface (ADR-0014 §3).
 */
@Mapper(injectionStrategy = InjectionStrategy.CONSTRUCTOR)
interface FinalDiagnosisMapper {

    @Mapping(target = "consultationUid", source = "consultation.uid")
    FinalDiagnosisDto toDto(FinalDiagnosis entity);

    List<FinalDiagnosisDto> toDtoList(List<FinalDiagnosis> entities);
}
