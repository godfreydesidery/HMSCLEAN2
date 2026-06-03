package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.LabTestTypeDto;
import com.otapp.hmis.masterdata.domain.LabTestType;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link LabTestType} (ADR-0014 §3). Package-private.
 */
@Mapper
interface LabTestTypeMapper {

    LabTestTypeDto toDto(LabTestType entity);

    List<LabTestTypeDto> toDtoList(List<LabTestType> entities);
}
