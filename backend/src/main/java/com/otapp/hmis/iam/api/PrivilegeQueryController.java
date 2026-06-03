package com.otapp.hmis.iam.api;

import com.otapp.hmis.iam.application.PrivilegeQueryService;
import com.otapp.hmis.iam.application.dto.PrivilegeResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privilege catalogue endpoint (build-spec §2 endpoint #13).
 *
 * <p>No {@code @PreAuthorize} — legacy was ungated; authentication baseline via
 * {@code .anyRequest().authenticated()}. Optional {@code ?roleName=} filter mirrors legacy
 * {@code GET /privileges?role=} (UserResource.java:414).
 */
@RestController
@RequestMapping("/api/v1/iam/privileges")
@RequiredArgsConstructor
public class PrivilegeQueryController {

    private final PrivilegeQueryService privilegeQueryService;

    @GetMapping
    public List<PrivilegeResponse> list(
            @RequestParam(name = "roleName", required = false) String roleName) {
        return privilegeQueryService.list(roleName);
    }
}
