package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.CompanyProfileDto;
import com.otapp.hmis.masterdata.domain.CompanyProfile;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for {@link CompanyProfile} (ADR-0014 §3). Package-private, no business logic.
 * Maps the public {@code uid} but never the internal {@code id}.
 */
@Mapper
interface CompanyProfileMapper {

    CompanyProfileDto toDto(CompanyProfile entity);
}
