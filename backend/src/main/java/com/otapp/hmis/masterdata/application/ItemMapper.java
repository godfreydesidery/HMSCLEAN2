package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ItemDto;
import com.otapp.hmis.masterdata.domain.Item;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Item} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface ItemMapper {

    ItemDto toDto(Item entity);

    List<ItemDto> toDtoList(List<Item> entities);
}
