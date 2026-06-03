package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.InsurancePlanDto;
import com.otapp.hmis.masterdata.domain.InsurancePlan;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link InsurancePlan} (ADR-0014 §3). Package-private; no business
 * logic. The provider is flattened to its uid — no nested DTO (ADR-0014 §1).
 */
@Mapper
interface InsurancePlanMapper {

    @Mapping(target = "insuranceProviderUid", source = "insuranceProvider.uid")
    InsurancePlanDto toDto(InsurancePlan entity);

    List<InsurancePlanDto> toDtoList(List<InsurancePlan> entities);
}
