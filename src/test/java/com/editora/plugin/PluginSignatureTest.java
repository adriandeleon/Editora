package com.editora.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Unit tests for Ed25519 registry-signature verification (pure, no network). */
class PluginSignatureTest {

    private static byte[] sign(PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    @Test
    void verifiesValidSignatureAndRejectsTampering() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] data = "the exact index.json bytes".getBytes(StandardCharsets.UTF_8);
        String sig = Base64.getEncoder().encodeToString(sign(kp.getPrivate(), data));

        assertTrue(PluginSignature.verify(data, sig, kp.getPublic()), "valid signature");
        assertFalse(
                PluginSignature.verify("tampered".getBytes(StandardCharsets.UTF_8), sig, kp.getPublic()),
                "tampered data must fail");

        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertFalse(PluginSignature.verify(data, sig, other.getPublic()), "wrong key must fail");

        assertFalse(PluginSignature.verify(data, "!!not base64!!", kp.getPublic()), "bad base64 fails closed");
        assertFalse(PluginSignature.verify(data, sig, null), "null key fails closed");
        assertFalse(PluginSignature.verify(data, null, kp.getPublic()), "null sig fails closed");
    }

    @Test
    void publicKeyRoundTripsFromBase64() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()); // X.509
        PublicKey parsed = PluginSignature.publicKeyFromBase64(pubB64);

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String sig = Base64.getEncoder().encodeToString(sign(kp.getPrivate(), data));
        assertTrue(PluginSignature.verify(data, sig, parsed), "parsed bundled-style key verifies");
    }
}
