package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardCategoryDto;
import com.otapp.hmis.masterdata.domain.WardCategory;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link WardCategory} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface WardCategoryMapper {

    WardCategoryDto toDto(WardCategory entity);

    List<WardCategoryDto> toDtoList(List<WardCategory> entities);
}
