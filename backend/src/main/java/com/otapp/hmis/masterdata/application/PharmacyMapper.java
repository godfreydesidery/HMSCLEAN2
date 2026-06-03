package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.PharmacyDto;
import com.otapp.hmis.masterdata.domain.Pharmacy;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Pharmacy} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface PharmacyMapper {

    PharmacyDto toDto(Pharmacy entity);

    List<PharmacyDto> toDtoList(List<Pharmacy> entities);
}
