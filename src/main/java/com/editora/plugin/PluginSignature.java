package com.editora.plugin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ed25519 signature verification for the plugin registry (Option 1: one pinned key). The registry's
 * {@code index.json} is signed with a private key held by the registry owner; the matching <b>public</b>
 * key is bundled in the app ({@code resources/com/editora/plugin/editora-registry.pub}, base64 X.509). The
 * registry serves a detached signature at {@code <index-url>.sig} (base64 Ed25519 over the exact index
 * bytes). A verified index + the per-entry SHA-256 then chain authenticity to each downloaded plugin.
 *
 * <p>Pure crypto over the JDK ({@code java.security}, Ed25519 since JDK 15) — no new dependency. All entry
 * points fail closed (return null / false) on any error.
 */
public final class PluginSignature {

    private static final Logger LOG = Logger.getLogger(PluginSignature.class.getName());
    private static final String RESOURCE = "/com/editora/plugin/editora-registry.pub";

    private static volatile PublicKey cached;
    private static volatile boolean loaded;

    private PluginSignature() {}

    /** The bundled registry public key, or {@code null} when absent/unreadable. Cached. */
    public static PublicKey bundledPublicKey() {
        if (loaded) {
            return cached;
        }
        synchronized (PluginSignature.class) {
            if (!loaded) {
                cached = load();
                loaded = true;
            }
        }
        return cached;
    }

    /** Whether a usable registry public key is bundled (signature checks are possible). */
    public static boolean hasBundledKey() {
        return bundledPublicKey() != null;
    }

    private static PublicKey load() {
        try (InputStream in = PluginSignature.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            String b64 = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return b64.isEmpty() ? null : publicKeyFromBase64(b64);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not load bundled registry public key", e);
            return null;
        }
    }

    /** Parses a base64 X.509 (SubjectPublicKeyInfo) Ed25519 public key. */
    public static PublicKey publicKeyFromBase64(String b64) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(b64.strip());
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * Verifies a base64 Ed25519 {@code signatureBase64} over {@code data} with {@code key}. Returns false on
     * any error (bad base64, wrong key, tampered data, missing inputs) — fail closed.
     */
    public static boolean verify(byte[] data, String signatureBase64, PublicKey key) {
        if (key == null || data == null || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            byte[] sig = Base64.getDecoder().decode(signatureBase64.strip());
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }
}
