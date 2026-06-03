package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.dto.PrivilegeResponse;
import com.otapp.hmis.iam.domain.Privilege;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper: {@link Privilege} → {@link PrivilegeResponse}.
 * Package-private; no repository injection.
 */
@Mapper(componentModel = "spring")
abstract class PrivilegeMapper {

    abstract PrivilegeResponse toResponse(Privilege privilege);
}
