package com.trams.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Reads RSA keys supplied as base64-encoded PEM.
 *
 * <p>The double encoding is deliberate. A PEM contains newlines, which cannot be
 * represented in a dotenv file or a Kubernetes environment variable without escaping, so
 * the PEM is base64-encoded once more for transport. Keeping keys in the environment
 * rather than on disk means the same image runs in every environment and no key is ever
 * baked into a build artefact.
 */
public final class RsaKeyLoader {

    private static final String PRIVATE_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PUBLIC_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_FOOTER = "-----END PUBLIC KEY-----";

    private RsaKeyLoader() {}

    /** @param base64Pem a base64-encoded PKCS#8 PEM ({@code BEGIN PRIVATE KEY}) */
    public static RSAPrivateKey loadPrivateKey(String base64Pem) {
        byte[] der = decodePem(base64Pem, PRIVATE_HEADER, PRIVATE_FOOTER, "private");

        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "JWT_PRIVATE_KEY_BASE64 is not a valid PKCS#8 RSA private key. Regenerate it with scripts/generate-secrets.sh.",
                    e);
        }
    }

    /** @param base64Pem a base64-encoded X.509/SPKI PEM ({@code BEGIN PUBLIC KEY}) */
    public static RSAPublicKey loadPublicKey(String base64Pem) {
        byte[] der = decodePem(base64Pem, PUBLIC_HEADER, PUBLIC_FOOTER, "public");

        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "JWT_PUBLIC_KEY_BASE64 is not a valid X.509 RSA public key. Regenerate it with scripts/generate-secrets.sh.",
                    e);
        }
    }

    private static byte[] decodePem(String base64Pem, String header, String footer, String kind) {
        if (base64Pem == null || base64Pem.isBlank()) {
            throw new IllegalStateException(
                    "The RSA %s key is not configured. Run scripts/generate-secrets.sh to create a key pair."
                            .formatted(kind));
        }

        String pem;
        try {
            pem = new String(Base64.getDecoder().decode(base64Pem.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "The RSA %s key is not valid base64. It must be a PEM file encoded with `openssl base64 -A`."
                            .formatted(kind),
                    e);
        }

        if (!pem.contains(header)) {
            throw new IllegalStateException(
                    "The RSA %s key does not look like a PEM containing '%s'.".formatted(kind, header));
        }

        // Strip the armour and every kind of whitespace, leaving the base64 DER body.
        String body =
                pem.replace(header, "")
                        .replace(footer, "")
                        .replaceAll("\\s+", "");

        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The RSA %s key body is not valid base64.".formatted(kind), e);
        }
    }
}
