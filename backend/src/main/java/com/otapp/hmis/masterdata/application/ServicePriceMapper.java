package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ServicePriceDto;
import com.otapp.hmis.masterdata.domain.ServicePrice;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link ServicePrice} (ADR-0014 §3). Package-private; no business
 * logic. Maps all public entity getters directly to the DTO record components.
 */
@Mapper
interface ServicePriceMapper {

    ServicePriceDto toDto(ServicePrice entity);

    List<ServicePriceDto> toDtoList(List<ServicePrice> entities);
}
