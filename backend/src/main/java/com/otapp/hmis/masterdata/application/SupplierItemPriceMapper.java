package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.SupplierItemPriceDto;
import com.otapp.hmis.masterdata.domain.SupplierItemPrice;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link SupplierItemPrice} (ADR-0014 §3). Package-private.
 * FK entities flattened to uid strings.
 */
@Mapper
interface SupplierItemPriceMapper {

    @Mapping(source = "supplier.uid", target = "supplierUid")
    @Mapping(source = "item.uid",     target = "itemUid")
    SupplierItemPriceDto toDto(SupplierItemPrice entity);

    List<SupplierItemPriceDto> toDtoList(List<SupplierItemPrice> entities);
}
