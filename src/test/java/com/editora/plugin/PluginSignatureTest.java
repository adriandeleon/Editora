package com.editora.plugin;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The key shipped in the app is what "signed registry" means for a user. It has to load — a build without
     * it would silently treat every registry as unsigned — and it has to be the one in the resource file.
     */
    @Test
    void theBundledRegistryKeyLoadsAndIsTheOneInTheResourceFile() throws Exception {
        PublicKey bundled = PluginSignature.bundledPublicKey();

        assertNotNull(bundled, "editora-registry.pub is missing or unreadable");
        assertTrue(PluginSignature.hasBundledKey());
        assertTrue(List.of("Ed25519", "EdDSA").contains(bundled.getAlgorithm()), bundled.getAlgorithm());
        assertSame(bundled, PluginSignature.bundledPublicKey(), "loaded once");
        String resource;
        try (var in = PluginSignature.class.getResourceAsStream("/com/editora/plugin/editora-registry.pub")) {
            resource = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        assertArrayEquals(Base64.getDecoder().decode(resource), bundled.getEncoded());
    }

    /** Nobody but the registry owner can produce a signature the bundled key accepts. */
    @Test
    void theBundledKeyRejectsASignatureMadeWithAnyOtherKey() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] index = "{\"plugins\":[]}".getBytes(StandardCharsets.UTF_8);
        String sig = Base64.getEncoder().encodeToString(sign(attacker.getPrivate(), index));

        assertTrue(PluginSignature.verify(index, sig, attacker.getPublic()), "the signature itself is well-formed");
        assertFalse(PluginSignature.verify(index, sig, PluginSignature.bundledPublicKey()));
    }

    @Test
    void aKeyThatIsNotAnEd25519KeyIsRejectedWhenParsed() {
        assertThrows(Exception.class, () -> PluginSignature.publicKeyFromBase64("bm90IGEga2V5"));
        assertThrows(Exception.class, () -> PluginSignature.publicKeyFromBase64("!!not base64!!"));
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
