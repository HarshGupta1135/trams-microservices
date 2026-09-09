package com.trams.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds an {@link SSLContext} that trusts the private CA which signed the NATS server
 * certificate.
 *
 * <p>The CA is read straight from its PEM file and loaded into an in-memory trust store.
 * The alternative — running {@code keytool} to produce a JKS/PKCS12 file and shipping it
 * with a password — adds a build step, a binary artefact and another secret, all to hold
 * a single public certificate. Reading the PEM keeps the deployment to one mounted file.
 */
public final class NatsTlsSupport {

    private NatsTlsSupport() {}

    /**
     * @param caFile path to the PEM-encoded CA certificate
     * @return a context trusting only that CA, with the platform's default protocols
     * @throws IllegalStateException if the certificate cannot be read or parsed
     */
    public static SSLContext trustingCa(Path caFile) {
        if (!Files.isReadable(caFile)) {
            throw new IllegalStateException(
                    "NATS CA certificate is not readable at '%s'. Run scripts/generate-tls.sh, or set trams.nats.tls.enabled=false for a plaintext broker."
                            .formatted(caFile.toAbsolutePath()));
        }

        try (InputStream in = Files.newInputStream(caFile)) {
            X509Certificate authority =
                    (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);

            // An empty in-memory keystore holding just this CA. Passing null to load()
            // creates it without touching the filesystem or the JDK's cacerts.
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("trams-nats-ca", authority);

            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);

            // "TLS" negotiates the highest mutually supported version (1.3 with a
            // current NATS build) rather than pinning a version that will age badly.
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), new SecureRandom());

            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Failed to build an SSL context from the NATS CA certificate at " + caFile, e);
        }
    }
}
