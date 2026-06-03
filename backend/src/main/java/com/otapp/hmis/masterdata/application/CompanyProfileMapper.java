package com.otapp.hmis.masterdata.application;

import com.otapp.hmis.masterdata.application.dto.CompanyProfileDto;
import com.otapp.hmis.masterdata.application.dto.CompanyProfileRequest;
import com.otapp.hmis.masterdata.domain.CompanyProfile;
import com.otapp.hmis.masterdata.domain.CompanyProfileData;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link CompanyProfile} (ADR-0014 §3). Package-private; no business logic.
 *
 * <p>Maps the public {@code uid} but never the internal {@code id} (ADR-0014 §1).
 * Logo bytes are excluded from the DTO (served separately).
 *
 * <p>{@link #toData(CompanyProfileRequest)} assembles the domain value object from an inbound
 * request; the service passes this to {@link CompanyProfile#update} or
 * {@link CompanyProfile#create}.
 */
@Mapper
interface CompanyProfileMapper {

    /** Entity → wire DTO. Logo field is intentionally not in the DTO record. */
    @Mapping(target = "uid", source = "uid")
    CompanyProfileDto toDto(CompanyProfile entity);

    /**
     * Request → domain value object. Logo is omitted (no binary in JSON request body —
     * logo upload is a separate multipart concern, not implemented in inc-02).
     */
    @Mapping(target = "logo", ignore = true)
    CompanyProfileData toData(CompanyProfileRequest request);
}
