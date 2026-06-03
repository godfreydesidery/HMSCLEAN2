package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ItemMedicineCoefficientDto;
import com.otapp.hmis.masterdata.domain.ItemMedicineCoefficient;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link ItemMedicineCoefficient} (ADR-0014 §3). Package-private.
 * FK entities are flattened to their {@code uid} strings in the DTO.
 */
@Mapper
interface ItemMedicineCoefficientMapper {

    @Mapping(source = "item.uid",     target = "itemUid")
    @Mapping(source = "medicine.uid", target = "medicineUid")
    ItemMedicineCoefficientDto toDto(ItemMedicineCoefficient entity);

    List<ItemMedicineCoefficientDto> toDtoList(List<ItemMedicineCoefficient> entities);
}
