package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.WardDto;
import com.otapp.hmis.masterdata.domain.Ward;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link Ward} (ADR-0014 §3). Package-private; no business logic.
 *
 * <p>FK entities are flattened to their {@code uid} strings in the DTO so no nested entity
 * leaks across the module boundary (build-spec §1.1).
 */
@Mapper
interface WardMapper {

    @Mapping(source = "wardCategory.uid", target = "wardCategoryUid")
    @Mapping(source = "wardType.uid",     target = "wardTypeUid")
    WardDto toDto(Ward entity);

    List<WardDto> toDtoList(List<Ward> entities);
}
