package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.ClinicDto;
import com.otapp.hmis.masterdata.domain.Clinic;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link Clinic} (ADR-0014 §3). Package-private; no business logic.
 * Maps {@code uid} but never the internal {@code id}.
 */
@Mapper
interface ClinicMapper {

    ClinicDto toDto(Clinic entity);

    List<ClinicDto> toDtoList(List<Clinic> entities);
}
