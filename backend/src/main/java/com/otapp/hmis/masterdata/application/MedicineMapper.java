package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.MedicineDto;
import com.otapp.hmis.masterdata.domain.Medicine;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Medicine} (ADR-0014 §3). Package-private; no business logic.
 * Maps {@code uid} but never the internal {@code id}.
 */
@Mapper
interface MedicineMapper {

    MedicineDto toDto(Medicine entity);

    List<MedicineDto> toDtoList(List<Medicine> entities);
}
