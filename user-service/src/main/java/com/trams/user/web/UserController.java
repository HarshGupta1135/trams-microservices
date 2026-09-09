package com.trams.user.web;

import com.trams.user.application.UserProfileService;
import com.trams.user.domain.User;
import com.trams.user.web.dto.ChangePasswordRequest;
import com.trams.web.PageResponse;
import com.trams.user.web.dto.UpdateProfileRequest;
import com.trams.user.web.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated user endpoints. */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Profile management and administrative user access")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserProfileService profiles;

    public UserController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    @Operation(summary = "Fetch the authenticated user's own profile")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(profiles.requireById(subjectOf(jwt)));
    }

    @PatchMapping("/me")
    @Operation(
            summary = "Update the authenticated user's profile",
            description =
                    """
                    Partial update: omitted fields are left unchanged. Emits a
                    `user.profile_updated` event listing exactly which fields changed, and emits
                    nothing at all when the request changes nothing.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile updated"),
        @ApiResponse(responseCode = "409", description = "Email address already in use", content = {})
    })
    public UserResponse updateMe(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {

        User updated = profiles.updateProfile(subjectOf(jwt), request.fullName(), request.email());

        return UserResponse.from(updated);
    }

    @PostMapping("/me/change-password")
    @Operation(
            summary = "Change the authenticated user's password",
            description =
                    """
                    Requires the current password. On success every refresh token for the account is
                    revoked, so any other session - including an attacker's - is terminated.
                    Emits a `user.password_changed` security-alert event.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Password changed; all sessions revoked"),
        @ApiResponse(responseCode = "401", description = "Current password incorrect", content = {})
    })
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {

        profiles.changePassword(
                subjectOf(jwt),
                request.currentPassword(),
                request.newPassword(),
                ClientContextResolver.resolve(httpRequest).ipAddress());

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    @Operation(
            summary = "Delete the authenticated user's own account",
            description = "Emits a `user.deleted` event before removal, while the address is still known.")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal Jwt jwt) {
        profiles.delete(subjectOf(jwt));

        return ResponseEntity.noContent().build();
    }

    // Administrative routes Authorisation is enforced here with @PreAuthorize as well as at the
    // gateway.

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List users (admin)", description = "Always paged; there is no unbounded listing.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A page of users"),
        @ApiResponse(responseCode = "403", description = "Caller is not an administrator", content = {})
    })
    public PageResponse<UserResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        Page<User> page = profiles.list(pageable);

        return PageResponse.from(page, UserResponse::from);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch any user by id (admin)")
    public UserResponse byId(@PathVariable UUID userId) {
        return UserResponse.from(profiles.requireById(userId));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete any user by id (admin)")
    public ResponseEntity<Void> deleteById(@PathVariable UUID userId) {
        profiles.delete(userId);

        return ResponseEntity.noContent().build();
    }

    /** The authenticated user's id, taken from the token's sub claim. */
    private static UUID subjectOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
