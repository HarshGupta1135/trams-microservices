package com.trams.messaging;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Owns the lifetime of this service's single NATS connection.
 *
 * <p>The client library handles reconnection and buffers outbound messages while
 * disconnected, so this class does not reimplement any of that. What it adds is the part
 * that matters operationally: connection state is logged as structured events (a silent
 * reconnect loop is the hardest broker problem to diagnose), and shutdown *drains* rather
 * than drops, so a rolling deploy does not abandon in-flight acknowledgements.
 */
public class NatsConnectionHolder implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NatsConnectionHolder.class);

    /** Bounded so shutdown cannot exceed the orchestrator's grace period. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final Connection connection;

    public NatsConnectionHolder(NatsProperties properties) {
        this.connection = connect(properties);
    }

    private static Connection connect(NatsProperties properties) {
        Options.Builder options =
                Options.builder()
                        .server(properties.url())
                        .connectionName(properties.connectionName())
                        .userInfo(properties.username(), properties.password())
                        .connectionTimeout(properties.connectionTimeout())
                        .reconnectWait(properties.reconnectWait())
                        .maxReconnects(properties.maxReconnects())
                        .pingInterval(properties.pingInterval())
                        .reconnectBufferSize(properties.reconnectBufferSize().toBytes())
                        .connectionListener(NatsConnectionHolder::onConnectionEvent)
                        .errorListener(new LoggingErrorListener());

        if (properties.tls().enabled()) {
            String caFile = properties.tls().caFile();
            if (caFile == null || caFile.isBlank()) {
                throw new IllegalStateException(
                        "trams.nats.tls.enabled is true but trams.nats.tls.ca-file is not set.");
            }
            options.sslContext(NatsTlsSupport.trustingCa(Path.of(caFile)));
        } else {
            log.warn(
                    "NATS TLS is DISABLED. Credentials and event payloads will cross the network in plaintext; this is only acceptable for local experimentation.");
        }

        try {
            Connection connection = Nats.connect(options.build());
            log.info(
                    "Connected to NATS at {} as '{}' (TLS: {}, server: {})",
                    properties.url(),
                    properties.username(),
                    properties.tls().enabled(),
                    connection.getServerInfo().getVersion());
            return connection;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not establish the initial connection to NATS at " + properties.url(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting to NATS", e);
        }
    }

    private static void onConnectionEvent(Connection conn, ConnectionListener.Events event) {
        switch (event) {
            case CONNECTED, RECONNECTED, RESUBSCRIBED ->
                    log.info("NATS connection event: {} (server: {})", event, conn.getConnectedUrl());
            case DISCONNECTED, LAME_DUCK ->
                    log.warn("NATS connection event: {}; the client will keep retrying", event);
            case CLOSED -> log.info("NATS connection closed");
            default -> log.debug("NATS connection event: {}", event);
        }
    }

    public Connection connection() {
        return connection;
    }

    public JetStream jetStream() {
        try {
            return connection.jetStream();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to obtain a JetStream context", e);
        }
    }

    /** Round-trip time to the broker, used by the readiness probe. */
    public Duration roundTripTime() throws IOException {
        return connection.RTT();
    }

    @Override
    public void destroy() {
        if (connection.getStatus() == Connection.Status.CLOSED) {
            return;
        }

        try {
            log.info("Draining the NATS connection before shutdown");
            // Waits for in-flight publishes to be acknowledged and for subscriptions to
            // finish processing what they have already received.
            connection.drain(DRAIN_TIMEOUT).get();
            log.info("NATS connection drained cleanly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while draining NATS; closing immediately");
            closeQuietly();
        } catch (Exception e) {
            log.warn("Draining NATS failed ({}); closing immediately", e.getMessage());
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            connection.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Routes client-library problems into the service log instead of stderr. */
    private static final class LoggingErrorListener implements ErrorListener {

        @Override
        public void errorOccurred(Connection conn, String error) {
            log.error("NATS reported an error: {}", error);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exception) {
            log.error("NATS client exception", exception);
        }

        @Override
        public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
            // Back-pressure signal: this replica cannot keep up with its subscription.
            log.warn(
                    "NATS slow consumer detected (dropped: {}). Consider raising consumer concurrency or lowering max_ack_pending.",
                    consumer.getDroppedCount());
        }
    }
}
