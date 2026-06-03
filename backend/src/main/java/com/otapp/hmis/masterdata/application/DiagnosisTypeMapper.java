package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.DiagnosisTypeDto;
import com.otapp.hmis.masterdata.domain.DiagnosisType;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link DiagnosisType} (ADR-0014 §3). Package-private.
 */
@Mapper
interface DiagnosisTypeMapper {

    DiagnosisTypeDto toDto(DiagnosisType entity);

    List<DiagnosisTypeDto> toDtoList(List<DiagnosisType> entities);
}
