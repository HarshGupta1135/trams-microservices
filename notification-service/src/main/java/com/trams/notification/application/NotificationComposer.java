package com.trams.notification.application;

import com.trams.contracts.UserEventPayload;
import com.trams.notification.config.NotificationProperties;
import com.trams.notification.domain.NotificationContent;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders an event into a subject line and an HTML body.
 *
 * <p>Composition is deliberately separate from delivery: this class is pure (event in,
 * text out), so every message can be unit-tested without a mail server, and the same
 * content can later be delivered over a different channel without re-rendering.
 *
 * <p>The {@code switch} below is exhaustive over the sealed {@link UserEventPayload}
 * hierarchy and has no {@code default} branch. That is the point of sealing the contract:
 * adding an event type to {@code common-contracts} makes this file fail to compile until
 * the new event is given a message, so a new event can never silently produce no
 * notification.
 */
@Service
public class NotificationComposer {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'");

    private final TemplateEngine templateEngine;
    private final NotificationProperties properties;

    public NotificationComposer(TemplateEngine templateEngine, NotificationProperties properties) {
        this.templateEngine = templateEngine;
        this.properties = properties;
    }

    public NotificationContent compose(UserEventPayload payload) {
        return switch (payload) {
            case UserEventPayload.UserRegistered event -> welcome(event);
            case UserEventPayload.UserProfileUpdated event -> profileUpdated(event);
            case UserEventPayload.UserPasswordChanged event -> passwordChanged(event);
            case UserEventPayload.UserDeleted event -> accountDeleted(event);
        };
    }

    private NotificationContent welcome(UserEventPayload.UserRegistered event) {
        return render(
                "notifications/welcome",
                "Welcome to %s".formatted(properties.applicationName()),
                Map.of(
                        "name", event.fullName(),
                        "email", event.email(),
                        "registeredAt", format(event.registeredAt())));
    }

    private NotificationContent profileUpdated(UserEventPayload.UserProfileUpdated event) {
        // Human-readable field names, so the message reads as prose rather than as a
        // dump of internal identifiers.
        List<String> readableFields = event.changedFields().stream().map(NotificationComposer::humanise).toList();

        return render(
                "notifications/profile-updated",
                "Your %s profile was updated".formatted(properties.applicationName()),
                Map.of(
                        "name", event.fullName(),
                        "changedFields", readableFields,
                        "updatedAt", format(event.updatedAt())));
    }

    private NotificationContent passwordChanged(UserEventPayload.UserPasswordChanged event) {
        return render(
                "notifications/password-changed",
                "Your %s password was changed".formatted(properties.applicationName()),
                Map.of(
                        "name", event.fullName(),
                        "changedAt", format(event.changedAt()),
                        // Shown so a recipient can recognise a change they did not make.
                        "requestIp", event.requestIp() == null ? "an unknown address" : event.requestIp()));
    }

    private NotificationContent accountDeleted(UserEventPayload.UserDeleted event) {
        return render(
                "notifications/account-deleted",
                "Your %s account has been deleted".formatted(properties.applicationName()),
                Map.of(
                        "name", event.fullName(),
                        "deletedAt", format(event.deletedAt())));
    }

    private NotificationContent render(String template, String subject, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        context.setVariable("applicationName", properties.applicationName());
        context.setVariable("appUrl", properties.appUrl());
        context.setVariable("subject", subject);

        // Thymeleaf escapes interpolated values by default, so a name containing markup
        // cannot inject HTML into the message body.
        return new NotificationContent(subject, templateEngine.process(template, context));
    }

    private static String format(java.time.Instant instant) {
        return TIMESTAMP.format(instant.atZone(ZoneOffset.UTC));
    }

    /** {@code fullName} -> {@code full name}. */
    private static String humanise(String fieldName) {
        return fieldName.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }
}
