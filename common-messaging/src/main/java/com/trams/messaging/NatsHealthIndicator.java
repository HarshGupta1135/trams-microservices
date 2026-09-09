package com.trams.messaging;

import io.nats.client.Connection;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports broker connectivity to the readiness probe.
 *
 * <p>This contributes to {@code /actuator/health/readiness} but deliberately not to
 * liveness. A service whose broker is briefly unreachable should stop receiving traffic,
 * not be killed and restarted — restarting cannot fix someone else's outage, and a
 * restart loop across every replica turns a recoverable blip into an outage of its own.
 */
public class NatsHealthIndicator implements HealthIndicator {

    private final NatsConnectionHolder holder;

    public NatsHealthIndicator(NatsConnectionHolder holder) {
        this.holder = holder;
    }

    @Override
    public Health health() {
        Connection connection = holder.connection();
        Connection.Status status = connection.getStatus();

        if (status != Connection.Status.CONNECTED) {
            return Health.down()
                    .withDetail("status", status.name())
                    .withDetail("servers", connection.getServers())
                    .build();
        }

        try {
            Duration rtt = holder.roundTripTime();
            return Health.up()
                    .withDetail("status", status.name())
                    .withDetail("server", connection.getConnectedUrl())
                    .withDetail("roundTripMillis", rtt.toMillis())
                    .build();
        } catch (IOException e) {
            // Connected according to the client, but the broker did not answer a ping.
            return Health.down(e).withDetail("status", status.name()).build();
        }
    }
}
