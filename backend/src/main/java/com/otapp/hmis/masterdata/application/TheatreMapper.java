package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.TheatreDto;
import com.otapp.hmis.masterdata.domain.Theatre;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Theatre} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface TheatreMapper {

    TheatreDto toDto(Theatre entity);

    List<TheatreDto> toDtoList(List<Theatre> entities);
}
