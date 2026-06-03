package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.application.IamEvents.UserActivatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserCreatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserDeactivatedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserDeletedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserPasswordChangedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserRolesAssignedEvent;
import com.otapp.hmis.iam.application.IamEvents.UserUpdatedEvent;
import com.otapp.hmis.iam.application.dto.AssignRolesRequest;
import com.otapp.hmis.iam.application.dto.CreateUserRequest;
import com.otapp.hmis.iam.application.dto.UpdateUserRequest;
import com.otapp.hmis.iam.application.dto.UserResponse;
import com.otapp.hmis.iam.application.dto.UserSummaryResponse;
import com.otapp.hmis.iam.domain.Cashier;
import com.otapp.hmis.iam.domain.CashierRepository;
import com.otapp.hmis.iam.domain.Clinician;
import com.otapp.hmis.iam.domain.ClinicianRepository;
import com.otapp.hmis.iam.domain.Management;
import com.otapp.hmis.iam.domain.ManagementRepository;
import com.otapp.hmis.iam.domain.Nurse;
import com.otapp.hmis.iam.domain.NurseRepository;
import com.otapp.hmis.iam.domain.Pharmacist;
import com.otapp.hmis.iam.domain.PharmacistRepository;
import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.RoleRepository;
import com.otapp.hmis.iam.domain.StorePerson;
import com.otapp.hmis.iam.domain.StorePersonRepository;
import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.shared.audit.AuditAction;
import com.otapp.hmis.shared.audit.AuditRecorder;
import com.otapp.hmis.shared.error.ErrorCode;
import com.otapp.hmis.shared.error.HmisException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration service (build-spec §9 task 6, §2 endpoints #1-#5, #12).
 *
 * <p>Reproduces the legacy {@code UserServiceImpl.saveUser} lifecycle (lines 104-360):
 * <ul>
 *   <li>Create: generate userNo via sequence, BCrypt-encode password, persist user, create/
 *       reactivate matching personnel extensions for assigned roles.
 *   <li>Update: names + roles update; blank password keeps hash; non-blank re-encodes.
 *       Deactivate extensions for removed roles (AMB-5: all six, symmetrically).
 *   <li>Enable/disable: real toggle (decision #3 — no no-op stub).
 *   <li>Delete: real delete with root guard (decision #3).
 * </ul>
 *
 * <p>AMB-2: Management extension triggers on role name {@code "MANAGER"} (not {@code "MANAGEMENT"}
 * — the legacy dead-trigger bug — flagged for BA review).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    /** Reserved username — cannot be created or deleted via API. */
    private static final String ROOT_USERNAME = "root";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    // Personnel extension repositories
    private final ClinicianRepository clinicianRepository;
    private final NurseRepository nurseRepository;
    private final PharmacistRepository pharmacistRepository;
    private final CashierRepository cashierRepository;
    private final StorePersonRepository storePersonRepository;
    private final ManagementRepository managementRepository;

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (ROOT_USERNAME.equalsIgnoreCase(request.username())) {
            throw new BusinessRuleException("Username 'root' is reserved and cannot be created");
        }
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ConflictException("Username '" + request.username() + "' is already taken");
        }

        // Validate update-mode password constraint is not needed here — create uses 4..50 from DTO

        User user = new User(request.username(), passwordEncoder.encode(request.password()));

        // Generate userNo from DB sequence (CR-06)
        Long seqVal = userRepository.nextUserNo();
        user.assignUserNo(UserNoFormatter.format(seqVal));

        // Names
        user.rename(request.firstName(), request.middleName(), request.lastName(), request.nickname());

        // Assign roles
        Set<Role> roles = resolveRoles(request.roleNames());
        roles.forEach(user::assign);

        user = userRepository.save(user);

        // Personnel extensions lifecycle
        syncExtensions(user, roles);

        String actor = currentActor();
        auditRecorder.record("iam.User", user.getUid(), AuditAction.CREATE, actor);
        eventPublisher.publishEvent(new UserCreatedEvent(user.getUid(), actor));

        return userMapper.toResponse(user);
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> listAll() {
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(userMapper::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getByUid(String uid) {
        User user = requireUser(uid);
        return userMapper.toResponse(user);
    }

    // -----------------------------------------------------------------------
    // Update
    // -----------------------------------------------------------------------

    @Transactional
    public UserResponse update(String uid, UpdateUserRequest request, String callerUsername) {
        User user = requireUser(uid);

        // Self-toggle guard: if enabled field changes and caller is the same user, reject
        if (user.getUsername().equals(callerUsername) && user.isEnabled() != request.enabled()) {
            throw new BusinessRuleException("Users cannot activate or deactivate their own account");
        }

        // Update names (always — mirrors legacy saveUser)
        user.rename(request.firstName(), request.middleName(), request.lastName(), request.nickname());

        // Password: blank keeps hash, non-blank validates 6..50 and re-encodes
        String actor = currentActor();
        boolean passwordChanged = false;
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 6 || request.password().length() > 50) {
                throw new BusinessRuleException("Password length should be more than 5 and less than 51");
            }
            user.changePasswordHash(passwordEncoder.encode(request.password()));
            passwordChanged = true;
        }

        // Enabled toggle (real — decision #3)
        if (user.isEnabled() != request.enabled()) {
            user.setActiveStatus(request.enabled());
            if (request.enabled()) {
                eventPublisher.publishEvent(new UserActivatedEvent(user.getUid(), actor));
            } else {
                eventPublisher.publishEvent(new UserDeactivatedEvent(user.getUid(), actor));
            }
        }

        // Roles: full replace then sync extensions (AMB-5: deactivate all removed)
        Set<Role> newRoles = resolveRoles(request.roleNames());
        user.replaceRoles(newRoles);
        syncExtensions(user, newRoles);

        user = userRepository.save(user);

        auditRecorder.record("iam.User", user.getUid(), AuditAction.UPDATE, actor);
        eventPublisher.publishEvent(new UserUpdatedEvent(user.getUid(), actor));
        if (passwordChanged) {
            eventPublisher.publishEvent(new UserPasswordChangedEvent(user.getUid(), actor));
        }

        return userMapper.toResponse(user);
    }

    // -----------------------------------------------------------------------
    // Assign roles (idempotent add — endpoint #12)
    // -----------------------------------------------------------------------

    @Transactional
    public UserResponse assignRoles(String uid, AssignRolesRequest request) {
        User user = requireUser(uid);
        Set<Role> rolesToAdd = resolveRoles(request.roleNames());
        rolesToAdd.forEach(user::assign);

        // Re-sync extensions with the full updated role set
        syncExtensions(user, user.getRoles());

        user = userRepository.save(user);

        String actor = currentActor();
        auditRecorder.record("iam.User", user.getUid(), AuditAction.UPDATE, actor);
        eventPublisher.publishEvent(new UserRolesAssignedEvent(user.getUid(),
                request.roleNames(), actor));

        return userMapper.toResponse(user);
    }

    // -----------------------------------------------------------------------
    // Delete (decision #3 — real delete with root guard)
    // -----------------------------------------------------------------------

    @Transactional
    public void delete(String uid) {
        User user = requireUser(uid);
        if (ROOT_USERNAME.equalsIgnoreCase(user.getUsername())) {
            throw new BusinessRuleException("The root user cannot be deleted");
        }
        String actor = currentActor();
        String userUid = user.getUid();
        userRepository.delete(user);
        auditRecorder.record("iam.User", userUid, AuditAction.DELETE, actor);
        eventPublisher.publishEvent(new UserDeletedEvent(userUid, actor));
    }

    // -----------------------------------------------------------------------
    // Personnel extensions lifecycle (legacy saveUser lines 138-357, AMB-5)
    // -----------------------------------------------------------------------

    /**
     * For each extension type: if the triggering role is present → create-or-reactivate;
     * if absent → deactivate if exists. All six extensions are handled symmetrically (AMB-5).
     */
    private void syncExtensions(User user, Iterable<Role> currentRoles) {
        Set<String> roleNames = new HashSet<>();
        currentRoles.forEach(r -> roleNames.add(r.getName()));

        syncClinician(user, roleNames.contains("CLINICIAN"));
        syncNurse(user, roleNames.contains("NURSE"));
        syncPharmacist(user, roleNames.contains("PHARMACIST"));
        syncCashier(user, roleNames.contains("CASHIER"));
        syncStorePerson(user, roleNames.contains("STORE-PERSON"));
        // AMB-2: trigger on MANAGER not MANAGEMENT
        syncManagement(user, roleNames.contains("MANAGER"));
    }

    private void syncClinician(User user, boolean shouldBeActive) {
        Optional<Clinician> existing = clinicianRepository.findByUser(user);
        if (shouldBeActive) {
            if (existing.isEmpty()) {
                Clinician c = Clinician.forUser(user);
                c.activate();
                clinicianRepository.save(c);
            } else {
                // Reactivate: refresh code+names from current user (07-DECISIONS-RATIFIED §C).
                existing.get().copyFrom(user);
                existing.get().activate();
                clinicianRepository.save(existing.get());
            }
        } else {
            existing.ifPresent(c -> {
                c.deactivate();
                clinicianRepository.save(c);
            });
        }
    }

    private void syncNurse(User user, boolean shouldBeActive) {
        Optional<Nurse> existing = nurseRepository.findByUser(user);
        if (shouldBeActive) {
            if (existing.isEmpty()) {
                Nurse n = Nurse.forUser(user);
                n.activate();
                nurseRepository.save(n);
            } else {
                existing.get().copyFrom(user);
                existing.get().activate();
                nurseRepository.save(existing.get());
            }
        } else {
            existing.ifPresent(n -> {
                n.deactivate();
                nurseRepository.save(n);
            });
        }
    }

    private void syncPharmacist(User user, boolean shouldBeActive) {
        Optional<Pharmacist> existing = pharmacistRepository.findByUser(user);
        if (shouldBeActive) {
            if (existing.isEmpty()) {
                Pharmacist p = Pharmacist.forUser(user);
                p.activate();
                pharmacistRepository.save(p);
            } else {
                existing.get().copyFrom(user);
                existing.get().activate();
                pharmacistRepository.save(existing.get());
            }
        } else {
            existing.ifPresent(p -> {
                p.deactivate();
                pharmacistRepository.save(p);
            });
        }
    }

    private void syncCashier(User user, boolean shouldBeActive) {
        Optional<Cashier> existing = cashierRepository.findByUser(user);
        if (shouldBeActive) {
            if (existing.isEmpty()) {
                Cashier c = Cashier.forUser(user);
                c.activate();
                cashierRepository.save(c);
            } else {
                existing.get().copyFrom(user);
                existing.get().activate();
                cashierRepository.save(existing.get());
            }
        } else {
            existing.ifPresent(c -> {
                c.deactivate();
                cashierRepository.save(c);
            });
        }
    }

    private void syncStorePerson(User user, boolean shouldBeActive) {
        Optional<StorePerson> existing = storePersonRepository.findByUser(user);
        if (shouldBeActive) {
            if (existing.isEmpty()) {
                StorePerson sp = StorePerson.forUser(user);
                sp.activate();
                storePersonRepository.save(sp);
            } else {
                existing.get().copyFrom(user);
                existing.get().activate();
                storePersonRepository.save(existing.get());
            }
        } else {
            existing.ifPresent(sp -> {
                sp.deactivate();
                storePersonRepository.save(sp);
            });
        }
    }

    private void syncManagement(User user, boolean shouldBeActive) {
        Optional<Management> existing = managementRepository.findByUser(user);
        if (shouldBeActive) {
            if (existing.isEmpty()) {
                Management m = Management.forUser(user);
                m.activate();
                managementRepository.save(m);
            } else {
                existing.get().copyFrom(user);
                existing.get().activate();
                managementRepository.save(existing.get());
            }
        } else {
            existing.ifPresent(m -> {
                m.deactivate();
                managementRepository.save(m);
            });
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private User requireUser(String uid) {
        return userRepository.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("User not found: " + uid));
    }

    private Set<Role> resolveRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return new HashSet<>();
        }
        Set<Role> roles = new HashSet<>();
        for (String name : roleNames) {
            Role role = roleRepository.findByName(name)
                    .orElseThrow(() -> new NotFoundException("Role not found: " + name));
            roles.add(role);
        }
        return roles;
    }

    private static String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "SYSTEM";
        }
        return auth.getName();
    }

    // -----------------------------------------------------------------------
    // Local exception helpers (use existing ErrorCodes)
    // -----------------------------------------------------------------------

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
