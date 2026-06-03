package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ItemSupplierDto;
import com.otapp.hmis.masterdata.domain.ItemSupplier;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link ItemSupplier} (ADR-0014 §3). Package-private.
 * FK entities flattened to uid strings.
 */
@Mapper
interface ItemSupplierMapper {

    @Mapping(source = "item.uid",     target = "itemUid")
    @Mapping(source = "supplier.uid", target = "supplierUid")
    ItemSupplierDto toDto(ItemSupplier entity);

    List<ItemSupplierDto> toDtoList(List<ItemSupplier> entities);
}
