package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.SupplierDto;
import com.otapp.hmis.masterdata.domain.Supplier;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Supplier} (ADR-0014 §3). Package-private.
 */
@Mapper
interface SupplierMapper {

    SupplierDto toDto(Supplier entity);

    List<SupplierDto> toDtoList(List<Supplier> entities);
}
