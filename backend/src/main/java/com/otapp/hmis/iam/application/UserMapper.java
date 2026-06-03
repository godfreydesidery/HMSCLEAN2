package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.dto.UserResponse;
import com.otapp.hmis.iam.application.dto.UserSummaryResponse;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.User;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper: {@link User} → response DTOs (ADR-0014 §3, DIRECTIVE 1).
 *
 * <p>Package-private component (Spring-managed). Does NOT inject any repository — entity
 * construction, password encoding, and userNo generation are service concerns. The password hash
 * is NEVER mapped into any DTO.
 */
@Mapper(componentModel = "spring")
abstract class UserMapper {

    /**
     * Map a {@link User} to a full {@link UserResponse}.
     * {@code roleNames} is derived from the eager-loaded {@code roles} set.
     */
    @Mapping(target = "roleNames", source = "roles", qualifiedByName = "rolesToNames")
    abstract UserResponse toResponse(User user);

    /**
     * Map a {@link User} to a lightweight {@link UserSummaryResponse}.
     * {@code displayName} = firstName + " " + lastName; falls back to nickname if both are null.
     */
    @Mapping(target = "displayName", expression = "java(displayName(user))")
    @Mapping(target = "roleNames", source = "roles", qualifiedByName = "rolesToNames")
    abstract UserSummaryResponse toSummary(User user);

    @Named("rolesToNames")
    static List<String> rolesToNames(Set<Role> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(Role::getName)
                .sorted()
                .toList();
    }

    static String displayName(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && last != null) {
            return first.strip() + " " + last.strip();
        }
        if (first != null) {
            return first.strip();
        }
        if (last != null) {
            return last.strip();
        }
        return user.getNickname() != null ? user.getNickname() : user.getUsername();
    }
}
