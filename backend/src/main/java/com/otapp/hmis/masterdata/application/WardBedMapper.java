package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardBedDto;
import com.otapp.hmis.masterdata.domain.WardBed;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link WardBed} (ADR-0014 §3). Package-private; no business logic.
 *
 * <p>The {@code ward} FK entity is flattened to {@code wardUid} string in the DTO.
 */
@Mapper
interface WardBedMapper {

    @Mapping(source = "ward.uid", target = "wardUid")
    WardBedDto toDto(WardBed entity);

    List<WardBedDto> toDtoList(List<WardBed> entities);
}
