package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.MdDocumentTypeDto;
import com.otapp.hmis.masterdata.domain.MdDocumentType;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link MdDocumentType} (ADR-0014 §3). Package-private; no business logic.
 */
@Mapper
interface MdDocumentTypeMapper {

    MdDocumentTypeDto toDto(MdDocumentType entity);

    List<MdDocumentTypeDto> toDtoList(List<MdDocumentType> entities);
}
