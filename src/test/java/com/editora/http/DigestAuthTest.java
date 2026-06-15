package com.editora.http;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for Digest auth — the RFC 2617 §3.5 worked example pins the computation. */
class DigestAuthTest {

    @Test
    void shorthandParsesUserPass() {
        DigestAuth.Credentials c = DigestAuth.shorthand("Digest Mufasa Circle");
        assertEquals("Mufasa", c.user());
        assertEquals("Circle", c.pass());
        assertNull(DigestAuth.shorthand("Basic u p"));
        assertNull(DigestAuth.shorthand("Digest already-encoded"));
    }

    @Test
    void parseChallengeReadsParameters() {
        Map<String, String> c = DigestAuth.parseChallenge(
                "Digest realm=\"testrealm@host.com\", qop=\"auth\", nonce=\"abc\", opaque=\"xyz\"");
        assertEquals("testrealm@host.com", c.get("realm"));
        assertEquals("auth", c.get("qop"));
        assertEquals("abc", c.get("nonce"));
        assertEquals("xyz", c.get("opaque"));
    }

    @Test
    void computesRfc2617ResponseVector() {
        // RFC 2617 §3.5: Mufasa / "Circle Of Life", GET /dir/index.html.
        Map<String, String> challenge = DigestAuth.parseChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth\", "
                + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
        String header = DigestAuth.authorization(
                new DigestAuth.Credentials("Mufasa", "Circle Of Life"),
                "GET",
                "/dir/index.html",
                challenge,
                "0a4f113b",
                "00000001");
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""), header);
        assertTrue(header.contains("username=\"Mufasa\""), header);
        assertTrue(header.contains("qop=auth"), header);
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""), header);
    }
}
