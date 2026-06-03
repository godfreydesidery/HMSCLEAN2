package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardTypeDto;
import com.otapp.hmis.masterdata.domain.WardType;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link WardType} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface WardTypeMapper {

    WardTypeDto toDto(WardType entity);

    List<WardTypeDto> toDtoList(List<WardType> entities);
}
