package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.StoreDto;
import com.otapp.hmis.masterdata.domain.Store;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Store} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface StoreMapper {

    StoreDto toDto(Store entity);

    List<StoreDto> toDtoList(List<Store> entities);
}
