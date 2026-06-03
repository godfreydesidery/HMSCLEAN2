package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.IamEvents.RoleCreatedEvent;
import com.otapp.hmis.iam.application.IamEvents.RoleDeletedEvent;
import com.otapp.hmis.iam.application.IamEvents.RolePrivilegesReplacedEvent;
import com.otapp.hmis.iam.application.IamEvents.RoleUpdatedEvent;
import com.otapp.hmis.iam.application.dto.CreateRoleRequest;
import com.otapp.hmis.iam.application.dto.ReplaceRolePrivilegesRequest;
import com.otapp.hmis.iam.application.dto.RoleResponse;
import com.otapp.hmis.iam.application.dto.UpdateRoleRequest;
import com.otapp.hmis.iam.domain.Privilege;
import com.otapp.hmis.iam.domain.PrivilegeRepository;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role administration service (build-spec §9 task 6, §2 endpoints #6-#11).
 *
 * <p>Reproduces the legacy {@code UserServiceImpl.saveRole} and {@code UserResource.java}
 * role-management behaviour:
 * <ul>
 *   <li>Create: rejects the 15 reserved role names; sets {@code owner=ORGANIZATION}.
 *   <li>Update: same reserved-name guard; {@code owner} stays ORGANIZATION (cannot be changed).
 *   <li>Delete: rejects role named {@code ROOT} (legacy: UserResource.java:323).
 *   <li>Replace privileges: full-replace semantics; {@code "ALL"} shortcut grants all 35 codes
 *       (legacy: UserResource.java:444-445).
 * </ul>
 *
 * <p>CR-14: the reserved-name guard reproduces the legacy 15-name list verbatim (does NOT include
 * {@code MANAGEMENT} — the legacy gap — flagged for BA review).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    /**
     * Reserved role names (15). Verbatim from legacy {@code UserResource.java:228/285}.
     * Comparison is case-sensitive string equality (legacy behaviour — no trim/uppercase).
     */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "ROOT", "ADMIN", "RECEPTION", "CASHIER", "HUMAN-RESOURCE",
            "PROCUREMENT", "MANAGER", "ACCOUNTANT", "STORE-PERSON",
            "CLINICIAN", "NURSE", "PHARMACIST", "LABORATORIST",
            "RADIOGRAPHER", "RADIOLOGIST");

    private static final String ROOT_ROLE = "ROOT";

    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final RoleMapper roleMapper;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    @Transactional
    public RoleResponse create(CreateRoleRequest request) {
        if (RESERVED_NAMES.contains(request.name())) {
            throw new BusinessRuleException("Role name '" + request.name() + "' is reserved");
        }
        if (roleRepository.findByName(request.name()).isPresent()) {
            throw new ConflictException("Role '" + request.name() + "' already exists");
        }
        Role role = new Role(request.name(), "ORGANIZATION");
        role = roleRepository.save(role);

        String actor = currentActor();
        auditRecorder.record("iam.Role", role.getUid(), AuditAction.CREATE, actor);
        eventPublisher.publishEvent(new RoleCreatedEvent(role.getUid(), actor));

        return roleMapper.toResponse(role);
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RoleResponse> listAll() {
        return roleRepository.findAllByOrderByNameAsc().stream()
                .map(roleMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse getByUid(String uid) {
        return roleMapper.toResponse(requireRole(uid));
    }

    // -----------------------------------------------------------------------
    // Update
    // -----------------------------------------------------------------------

    @Transactional
    public RoleResponse update(String uid, UpdateRoleRequest request) {
        Role role = requireRole(uid);
        if (RESERVED_NAMES.contains(request.name())) {
            throw new BusinessRuleException("Role name '" + request.name() + "' is reserved");
        }
        // If name changed, check uniqueness
        if (!role.getName().equals(request.name()) &&
                roleRepository.findByName(request.name()).isPresent()) {
            throw new ConflictException("Role '" + request.name() + "' already exists");
        }
        role.rename(request.name());
        role = roleRepository.save(role);

        String actor = currentActor();
        auditRecorder.record("iam.Role", role.getUid(), AuditAction.UPDATE, actor);
        eventPublisher.publishEvent(new RoleUpdatedEvent(role.getUid(), actor));

        return roleMapper.toResponse(role);
    }

    // -----------------------------------------------------------------------
    // Replace privileges (full-replace, decision #4 + CR-15)
    // -----------------------------------------------------------------------

    @Transactional
    public RoleResponse replacePrivileges(String uid, ReplaceRolePrivilegesRequest request) {
        Role role = requireRole(uid);

        Set<Privilege> newPrivileges;
        if (request.privilegeCodes().contains("ALL")) {
            // "ALL" shortcut: grant every seeded privilege (legacy UserResource.java:444-445)
            newPrivileges = new HashSet<>(privilegeRepository.findAll());
        } else {
            newPrivileges = new HashSet<>();
            for (String code : request.privilegeCodes()) {
                Privilege p = privilegeRepository.findByCode(code)
                        .orElseThrow(() -> new NotFoundException("Privilege not found: " + code));
                newPrivileges.add(p);
            }
        }
        role.replacePrivileges(newPrivileges);
        role = roleRepository.save(role);

        String actor = currentActor();
        List<String> codes = newPrivileges.stream()
                .map(Privilege::getCode).sorted().toList();
        auditRecorder.record("iam.Role", role.getUid(), AuditAction.UPDATE, actor);
        eventPublisher.publishEvent(new RolePrivilegesReplacedEvent(role.getUid(), codes, actor));

        return roleMapper.toResponse(role);
    }

    // -----------------------------------------------------------------------
    // Delete (decision #3 — real delete with ROOT guard)
    // -----------------------------------------------------------------------

    @Transactional
    public void delete(String uid) {
        Role role = requireRole(uid);
        if (ROOT_ROLE.equals(role.getName())) {
            throw new BusinessRuleException("The ROOT role cannot be deleted");
        }
        String actor = currentActor();
        String roleUid = role.getUid();
        roleRepository.delete(role);
        auditRecorder.record("iam.Role", roleUid, AuditAction.DELETE, actor);
        eventPublisher.publishEvent(new RoleDeletedEvent(roleUid, actor));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Role requireRole(String uid) {
        return roleRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Role not found: " + uid));
    }

    private static String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "SYSTEM";
        }
        return auth.getName();
    }

    private static class BusinessRuleException extends HmisException {
        BusinessRuleException(String detail) {
            super(ErrorCode.BUSINESS_RULE, detail);
        }
    }

    private static class ConflictException extends HmisException {
        ConflictException(String detail) {
            super(ErrorCode.CONFLICT, detail);
        }
    }
}
