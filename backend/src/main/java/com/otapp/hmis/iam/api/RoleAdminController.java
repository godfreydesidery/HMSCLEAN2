package com.otapp.hmis.iam.api;

import com.otapp.hmis.iam.application.RoleAdminService;
import com.otapp.hmis.iam.application.dto.CreateRoleRequest;
import com.otapp.hmis.iam.application.dto.ReplaceRolePrivilegesRequest;
import com.otapp.hmis.iam.application.dto.RoleResponse;
import com.otapp.hmis.iam.application.dto.UpdateRoleRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Role administration endpoints (build-spec §2 endpoints #6-#11).
 *
 * <p>No class-level {@code @PreAuthorize}. GETs are ungated (authenticated baseline).
 * Mutations and privilege-replacement require {@code ROLE-ALL} or {@code ADMIN-ACCESS}
 * (CR-15 hardening applied to privilege-replace — legacy gate was commented out).
 */
@RestController
@RequestMapping("/api/v1/iam/roles")
@RequiredArgsConstructor
public class RoleAdminController {

    private final RoleAdminService roleAdminService;

    // ------------------------------------------------------------------
    // POST /api/v1/iam/roles  — create role
    // ------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')")
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse created = roleAdminService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/uid/{uid}")
                .buildAndExpand(created.uid())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/iam/roles  — list roles
    // ------------------------------------------------------------------

    @GetMapping
    public List<RoleResponse> list() {
        return roleAdminService.listAll();
    }

    // ------------------------------------------------------------------
    // GET /api/v1/iam/roles/uid/{uid}  — get one role
    // ------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    public RoleResponse get(@PathVariable("uid") String uid) {
        return roleAdminService.getByUid(uid);
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/iam/roles/uid/{uid}  — update role name
    // ------------------------------------------------------------------

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')")
    public RoleResponse update(
            @PathVariable("uid") String uid,
            @Valid @RequestBody UpdateRoleRequest request) {
        return roleAdminService.update(uid, request);
    }

    // ------------------------------------------------------------------
    // DELETE /api/v1/iam/roles/uid/{uid}  — delete role
    // ------------------------------------------------------------------

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')")
    public ResponseEntity<Void> delete(@PathVariable("uid") String uid) {
        roleAdminService.delete(uid);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/iam/roles/uid/{uid}/privileges  — replace privileges (CR-15 hardened gate)
    // ------------------------------------------------------------------

    @PutMapping("/uid/{uid}/privileges")
    @PreAuthorize("hasAnyAuthority('ROLE-ALL','ADMIN-ACCESS')")
    public RoleResponse replacePrivileges(
            @PathVariable("uid") String uid,
            @Valid @RequestBody ReplaceRolePrivilegesRequest request) {
        return roleAdminService.replacePrivileges(uid, request);
    }
}
