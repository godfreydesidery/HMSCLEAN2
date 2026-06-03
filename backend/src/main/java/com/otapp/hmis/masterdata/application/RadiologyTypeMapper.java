package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.RadiologyTypeDto;
import com.otapp.hmis.masterdata.domain.RadiologyType;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link RadiologyType} (ADR-0014 §3). Package-private.
 */
@Mapper
interface RadiologyTypeMapper {

    RadiologyTypeDto toDto(RadiologyType entity);

    List<RadiologyTypeDto> toDtoList(List<RadiologyType> entities);
}
