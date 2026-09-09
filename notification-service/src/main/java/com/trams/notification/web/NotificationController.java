package com.trams.notification.web;

import com.trams.notification.application.NotificationQueryService;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationStatus;
import com.trams.notification.web.dto.NotificationDetailResponse;
import com.trams.notification.web.dto.NotificationSummaryResponse;
import com.trams.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only notification history. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Notification history for the authenticated user")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationQueryService notifications;

    public NotificationController(NotificationQueryService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/me")
    @Operation(
            summary = "List the authenticated user's notifications",
            description =
                    """
                    Returns notifications newest first, optionally filtered by delivery status.
                    Bodies are omitted from the list; fetch a single notification to read one.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A page of notifications"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid access token", content = {})
    })
    public PageResponse<NotificationSummaryResponse> myNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Optional delivery-status filter")
                    @RequestParam(required = false)
                    NotificationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<Notification> page = notifications.forRecipient(subjectOf(jwt), status, pageable);

        return PageResponse.from(page, NotificationSummaryResponse::from);
    }

    @GetMapping("/me/{notificationId}")
    @Operation(
            summary = "Fetch one of the authenticated user's notifications",
            description =
                    """
                    Includes the rendered body. A notification belonging to another user reports
                    404, identically to one that does not exist, so ids cannot be probed.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The notification"),
        @ApiResponse(responseCode = "404", description = "No such notification for this user", content = {})
    })
    public NotificationDetailResponse myNotification(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId) {

        return NotificationDetailResponse.from(
                notifications.requireOwned(notificationId, subjectOf(jwt)));
    }

    /** The caller's id, from the token's verified sub claim. */
    private static UUID subjectOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
