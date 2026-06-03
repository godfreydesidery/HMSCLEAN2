package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ProcedureTypeDto;
import com.otapp.hmis.masterdata.domain.ProcedureType;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link ProcedureType} (ADR-0014 §3). Package-private.
 */
@Mapper
interface ProcedureTypeMapper {

    ProcedureTypeDto toDto(ProcedureType entity);

    List<ProcedureTypeDto> toDtoList(List<ProcedureType> entities);
}
