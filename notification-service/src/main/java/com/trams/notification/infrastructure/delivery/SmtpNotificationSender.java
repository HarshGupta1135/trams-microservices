package com.trams.notification.infrastructure.delivery;

import com.trams.notification.config.NotificationProperties;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Delivers notifications over SMTP.
 *
 * <p>Locally this points at Mailpit, which captures every message and shows it in a web
 * UI; in production the same code talks to a real relay with credentials from the
 * environment.
 *
 * <p><strong>Why {@link ObjectProvider} rather than {@code @ConditionalOnBean}.</strong>
 * Spring Boot only creates a {@link JavaMailSender} when {@code spring.mail.host} is set,
 * so this sender's dependency is genuinely optional. The obvious approach —
 * {@code @ConditionalOnBean(JavaMailSender.class)} on this component — does not work:
 * {@code @ConditionalOnBean} is only reliable inside auto-configuration classes, which are
 * processed *after* component scanning. On a scanned {@code @Component} the condition is
 * evaluated before the mail auto-configuration has contributed its bean, so it always
 * evaluates false and the EMAIL channel silently disappears. Resolving the sender lazily
 * through a provider avoids that ordering trap entirely.
 */
@Component
public class SmtpNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpNotificationSender.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final NotificationProperties properties;

    public SmtpNotificationSender(
            ObjectProvider<JavaMailSender> mailSenderProvider, NotificationProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    /** True when a mail sender is actually configured; checked at startup. */
    @Override
    public boolean isAvailable() {
        return mailSenderProvider.getIfAvailable() != null;
    }

    @Override
    public void send(Notification notification) throws DeliveryException {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            throw new DeliveryException(
                    "No mail sender is configured. Set spring.mail.host, or select the LOG channel "
                            + "with trams.notification.channel=LOG.");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(properties.fromAddress(), properties.fromName());
            helper.setTo(notification.getRecipientEmail());
            helper.setSubject(notification.getSubject());
            // HTML body; the templates are written for email clients.
            helper.setText(notification.getBody(), true);

            mailSender.send(message);

            log.info(
                    "Sent '{}' to {} (notification {})",
                    notification.getSubject(),
                    notification.getRecipientEmail(),
                    notification.getId());

        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // Mail failures are overwhelmingly temporary - a relay restarting, a rate
            // limit, a network blip - so this is surfaced as retryable rather than
            // discarding the message.
            throw new DeliveryException(
                    "SMTP delivery to %s failed: %s".formatted(notification.getRecipientEmail(), e.getMessage()), e);
        }
    }
}
