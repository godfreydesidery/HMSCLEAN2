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
 *   <li>Create: rejects the 15 reserved role names (case-insensitive, legacy
 *       {@code equalsIgnoreCase}); sets {@code owner=ORGANIZATION}.
 *   <li>Update: rejects editing any reserved role (legacy {@code UserResource.java:295-316}
 *       guards the existing role's name); additionally rejects renaming TO a reserved name
 *       (hardening per §E). Both checks are case-insensitive. {@code owner} stays ORGANIZATION.
 *   <li>Delete: rejects role named {@code ROOT} (legacy: UserResource.java:323).
 *   <li>Replace privileges: full-replace semantics; every entry is an explicit privilege code
 *       resolved via {@link PrivilegeRepository#findByCode} (CR-22: "ALL" shortcut dropped).
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
     * Comparison is case-INSENSITIVE (legacy uses {@code equalsIgnoreCase} — see
     * UserResource.java:217,239,296,302). Stored in uppercase for normalised matching.
     */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "ROOT", "ADMIN", "RECEPTION", "CASHIER", "HUMAN-RESOURCE",
            "PROCUREMENT", "MANAGER", "ACCOUNTANT", "STORE-PERSON",
            "CLINICIAN", "NURSE", "PHARMACIST", "LABORATORIST",
            "RADIOGRAPHER", "RADIOLOGIST");

    /** Returns true when {@code name} matches any reserved name case-insensitively. */
    private static boolean isReserved(String name) {
        return RESERVED_NAMES.stream().anyMatch(r -> r.equalsIgnoreCase(name));
    }

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
        if (isReserved(request.name())) {
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
        // Legacy guard (UserResource.java:295-316): cannot edit a reserved/system role at all.
        if (isReserved(role.getName())) {
            throw new BusinessRuleException(
                    "Role '" + role.getName() + "' is a system role and cannot be modified");
        }
        // Hardening (§E): additionally reject renaming TO a reserved name.
        if (isReserved(request.name())) {
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
    // Replace privileges (full-replace, decision #4 + CR-15 + CR-22)
    // -----------------------------------------------------------------------

    /**
     * Full-replace the privilege set for a role.
     *
     * <p>CR-22: the {@code "ALL"} shortcut has been dropped. Every entry in
     * {@code privilegeCodes} must be an explicit, known privilege code; unknown codes are
     * rejected with a validation error. This avoids the unverified over-grant semantics of the
     * legacy per-object {@code "ALL"} matrix (which was not reproduced — the modern API uses a
     * flat 35-code list). Documented modern simplification (07-DECISIONS-RATIFIED §E).
     */
    @Transactional
    public RoleResponse replacePrivileges(String uid, ReplaceRolePrivilegesRequest request) {
        Role role = requireRole(uid);

        Set<Privilege> newPrivileges = new HashSet<>();
        for (String code : request.privilegeCodes()) {
            // `code` is effectively final inside the enhanced-for body — safe for lambda capture.
            Privilege p = privilegeRepository.findByCode(code)
                    .orElseThrow(() -> new ValidationException("Unknown privilege code: " + code));
            newPrivileges.add(p);
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

    private static class ValidationException extends HmisException {
        ValidationException(String detail) {
            super(ErrorCode.VALIDATION, detail);
        }
    }
}
