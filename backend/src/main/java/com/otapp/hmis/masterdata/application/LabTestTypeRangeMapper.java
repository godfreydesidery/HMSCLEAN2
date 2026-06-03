package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.LabTestTypeRangeDto;
import com.otapp.hmis.masterdata.domain.LabTestTypeRange;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link LabTestTypeRange} (ADR-0014 §3). Package-private.
 * FK entity flattened to {@code labTestTypeUid} string.
 */
@Mapper
interface LabTestTypeRangeMapper {

    @Mapping(source = "labTestType.uid", target = "labTestTypeUid")
    LabTestTypeRangeDto toDto(LabTestTypeRange entity);

    List<LabTestTypeRangeDto> toDtoList(List<LabTestTypeRange> entities);
}
