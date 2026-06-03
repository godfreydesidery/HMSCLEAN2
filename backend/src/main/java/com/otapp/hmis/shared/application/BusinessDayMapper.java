package com.otapp.hmis.shared.application;

import com.otapp.hmis.shared.application.dto.BusinessDayDto;
import com.otapp.hmis.shared.domain.BusinessDay;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link BusinessDay} (ADR-0014 §3). Package-private; no business logic.
 *
 * <p>{@code status} is mapped from the enum's {@code name()} via the implicit
 * {@code toString()} conversion that MapStruct applies to {@code Enum → String}.
 */
@Mapper
interface BusinessDayMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    BusinessDayDto toDto(BusinessDay entity);
}
