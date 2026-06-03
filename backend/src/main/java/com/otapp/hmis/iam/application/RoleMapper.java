package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.dto.RoleResponse;
import com.otapp.hmis.iam.domain.Privilege;
import com.otapp.hmis.iam.domain.Role;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper: {@link Role} → {@link RoleResponse}.
 * Package-private; no repository injection.
 */
@Mapper(componentModel = "spring")
abstract class RoleMapper {

    @Mapping(target = "privilegeCodes", source = "privileges", qualifiedByName = "privilegesToCodes")
    abstract RoleResponse toResponse(Role role);

    @Named("privilegesToCodes")
    static List<String> privilegesToCodes(Set<Privilege> privileges) {
        if (privileges == null) {
            return List.of();
        }
        return privileges.stream()
                .map(Privilege::getCode)
                .sorted()
                .toList();
    }
}
