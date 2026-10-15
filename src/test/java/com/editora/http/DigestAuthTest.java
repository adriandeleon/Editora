package com.editora.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    // RFC 7616 §3.9.1: the same request answered with SHA-256 and with MD5.
    private static final String RFC7616 = "realm=\"http-auth@example.org\", qop=\"auth, auth-int\", "
            + "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v\", "
            + "opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\"";
    private static final String CNONCE = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ";

    @Test
    void computesTheRfc7616Sha256Vector() {
        Map<String, String> challenge = DigestAuth.strongest(
                List.of("Digest " + RFC7616 + ", algorithm=MD5", "Digest " + RFC7616 + ", algorithm=SHA-256"));
        assertEquals("SHA-256", challenge.get("algorithm"), "the stronger of the two challenges is answered");
        String header = DigestAuth.authorization(
                new DigestAuth.Credentials("Mufasa", "Circle of Life"),
                "GET",
                "/dir/index.html",
                challenge,
                CNONCE,
                "00000001");
        assertTrue(
                header.contains("response=\"753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1\""),
                header);
        assertTrue(header.contains("algorithm=SHA-256"), header);
        assertTrue(header.contains("qop=auth,"), header);
    }

    @Test
    void computesTheRfc7616Md5Vector() {
        String header = DigestAuth.authorization(
                new DigestAuth.Credentials("Mufasa", "Circle of Life"),
                "GET",
                "/dir/index.html",
                DigestAuth.parseChallenge("Digest " + RFC7616 + ", algorithm=MD5"),
                CNONCE,
                "00000001");
        assertTrue(header.contains("response=\"8ca523f5e9506fed4657c9700eebdbec\""), header);
        assertTrue(header.contains("algorithm=MD5"), header);
    }

    @Test
    void severalChallengesInOneHeaderAreSplitAndUnsupportedOnesSkipped() {
        Map<String, String> c = DigestAuth.strongest(List.of("Basic realm=\"x\", Digest realm=\"a\", nonce=\"n1\", "
                + "algorithm=SHA-512-256, qop=\"auth\", Digest realm=\"a\", nonce=\"n2\", algorithm=UNKNOWN-9"));
        assertEquals("n1", c.get("nonce"));
        assertEquals("SHA-512-256", c.get("algorithm"));
        assertNull(DigestAuth.strongest(List.of("Digest realm=\"a\", nonce=\"n\", algorithm=SHA-1024")));
        assertNull(DigestAuth.strongest(List.of("Digest realm=\"a\", nonce=\"n\", qop=\"token\"")));
        assertTrue(DigestAuth.offersDigest(List.of("Digest realm=\"a\", nonce=\"n\", algorithm=SHA-1024")));
        assertFalse(DigestAuth.offersDigest(List.of("Basic realm=\"a\"")));
    }

    @Test
    void authIntOnlyHashesTheBodyAndSessHashesTheNonceIn() {
        Map<String, String> challenge =
                DigestAuth.parseChallenge("Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\", algorithm=MD5-sess");
        DigestAuth.Credentials creds = new DigestAuth.Credentials("u", "p");
        String a = DigestAuth.authorization(creds, "POST", "/x", challenge, "c", "00000001", "body-a".getBytes());
        String b = DigestAuth.authorization(creds, "POST", "/x", challenge, "c", "00000001", "body-b".getBytes());
        assertTrue(a.contains("qop=auth-int,"), a);
        assertTrue(a.contains("algorithm=MD5-sess"), a);
        assertFalse(a.equals(b), "the body is part of an auth-int answer");
        // HA1 = MD5(MD5(u:r:p):n:c), HA2 = MD5(POST:/x:MD5(body-a)) — computed independently here
        String ha1 = md5(md5("u:r:p") + ":n:c");
        String ha2 = md5("POST:/x:" + md5("body-a"));
        assertTrue(a.contains("response=\"" + md5(ha1 + ":n:00000001:c:auth-int:" + ha2) + "\""), a);
    }

    @Test
    void aShorthandPasswordMayContainSpacesAndQuotesInTheUserAreEscaped() {
        DigestAuth.Credentials c = DigestAuth.shorthand("Digest ada my secret pass");
        assertEquals("ada", c.user());
        assertEquals("my secret pass", c.pass());
        assertNull(DigestAuth.shorthand("Digest username=\"ada\", realm=\"r\""), "a computed header is not shorthand");
        String header = DigestAuth.authorization(
                new DigestAuth.Credentials("a\"b", "p"), "GET", "/", Map.of("nonce", "n"), "c", "00000001");
        assertTrue(header.contains("username=\"a\\\"b\""), header);
    }

    private static String md5(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
