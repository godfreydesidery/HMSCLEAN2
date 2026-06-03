package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.InsuranceProviderDto;
import com.otapp.hmis.masterdata.domain.InsuranceProvider;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link InsuranceProvider} (ADR-0014 §3). Package-private; no business
 * logic. Maps {@code uid} but never the internal {@code id}.
 */
@Mapper
interface InsuranceProviderMapper {

    InsuranceProviderDto toDto(InsuranceProvider entity);

    List<InsuranceProviderDto> toDtoList(List<InsuranceProvider> entities);
}
