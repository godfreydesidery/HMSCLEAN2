package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.dto.PrivilegeResponse;
import com.otapp.hmis.iam.domain.PrivilegeRepository;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only privilege catalogue service (build-spec §2 endpoint #13).
 *
 * <p>Reproduces the legacy {@code UserResource.java:414} {@code GET /privileges?role=} endpoint:
 * when {@code roleName} is provided, returns privileges for that role; otherwise returns the full
 * 35-code catalogue ordered by code.
 */
@Service
@RequiredArgsConstructor
public class PrivilegeQueryService {

    private final PrivilegeRepository privilegeRepository;
    private final RoleRepository roleRepository;
    private final PrivilegeMapper privilegeMapper;

    @Transactional(readOnly = true)
    public List<PrivilegeResponse> list(String roleName) {
        if (roleName != null && !roleName.isBlank()) {
            Role role = roleRepository.findByName(roleName)
                    .orElseThrow(() -> new NotFoundException("Role not found: " + roleName));
            return role.getPrivileges().stream()
                    .map(privilegeMapper::toResponse)
                    .sorted((a, b) -> a.code().compareTo(b.code()))
                    .toList();
        }
        return privilegeRepository.findAllByOrderByCodeAsc().stream()
                .map(privilegeMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PrivilegeResponse> listByCategory(String category) {
        return privilegeRepository.findByCategory(category).stream()
                .map(privilegeMapper::toResponse)
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();
    }
}
