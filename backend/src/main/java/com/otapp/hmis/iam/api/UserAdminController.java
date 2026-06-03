package com.otapp.hmis.iam.api;

import com.otapp.hmis.iam.application.UserAdminService;
import com.otapp.hmis.iam.application.dto.AssignRolesRequest;
import com.otapp.hmis.iam.application.dto.CreateUserRequest;
import com.otapp.hmis.iam.application.dto.UpdateUserRequest;
import com.otapp.hmis.iam.application.dto.UserResponse;
import com.otapp.hmis.iam.application.dto.UserSummaryResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
 * User administration endpoints (build-spec §2 endpoints #1-#5, #12).
 *
 * <p>No class-level {@code @PreAuthorize} (exact-process directive — legacy gates per-method).
 * GETs carry no gate (legacy: ungated; authentication baseline via {@code .anyRequest().authenticated()}).
 * Mutations require {@code USER-ALL} or {@code ADMIN-ACCESS} (verified legacy gates).
 * Resources are addressed by uid ({@code /uid/{uid}}) — no numeric id in any URL (ADR-0014 §1).
 */
@RestController
@RequestMapping("/api/v1/iam/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    // ------------------------------------------------------------------
    // POST /api/v1/iam/users  — create user
    // ------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAnyAuthority('USER-ALL','ADMIN-ACCESS')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userAdminService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/uid/{uid}")
                .buildAndExpand(created.uid())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/iam/users  — list users (ungated; authenticated baseline)
    // ------------------------------------------------------------------

    @GetMapping
    public List<UserSummaryResponse> list() {
        return userAdminService.listAll();
    }

    // ------------------------------------------------------------------
    // GET /api/v1/iam/users/uid/{uid}  — get one user
    // ------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    public UserResponse get(@PathVariable("uid") String uid) {
        return userAdminService.getByUid(uid);
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/iam/users/uid/{uid}  — update user
    // ------------------------------------------------------------------

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('USER-ALL','ADMIN-ACCESS')")
    public UserResponse update(
            @PathVariable("uid") String uid,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        return userAdminService.update(uid, request, authentication.getName());
    }

    // ------------------------------------------------------------------
    // DELETE /api/v1/iam/users/uid/{uid}  — delete user
    // ------------------------------------------------------------------

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("hasAnyAuthority('USER-ALL','ADMIN-ACCESS')")
    public ResponseEntity<Void> delete(@PathVariable("uid") String uid) {
        userAdminService.delete(uid);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/iam/users/uid/{uid}/roles  — assign roles
    // ------------------------------------------------------------------

    @PutMapping("/uid/{uid}/roles")
    @PreAuthorize("hasAnyAuthority('USER-ALL','USER-UPDATE','ROLE-ALL','ADMIN-ACCESS')")
    public UserResponse assignRoles(
            @PathVariable("uid") String uid,
            @Valid @RequestBody AssignRolesRequest request) {
        return userAdminService.assignRoles(uid, request);
    }
}
