package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.AdministrationRouteDto;
import com.otapp.hmis.masterdata.domain.AdministrationRoute;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link AdministrationRoute} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface AdministrationRouteMapper {

    AdministrationRouteDto toDto(AdministrationRoute entity);

    List<AdministrationRouteDto> toDtoList(List<AdministrationRoute> entities);
}
