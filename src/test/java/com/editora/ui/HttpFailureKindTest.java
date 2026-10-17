package com.editora.ui;

import java.util.List;

import com.editora.http.HttpClientService;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The error card's title: which kind of failure a request that got no response met (U9). */
class HttpFailureKindTest {

    private static String before;

    @BeforeAll
    static void english() {
        before = com.editora.i18n.Messages.current();
        com.editora.i18n.Messages.init("en");
    }

    @AfterAll
    static void restore() {
        com.editora.i18n.Messages.init(before);
    }

    /** {@code key}'s message with {@code args}, and the URL the engine appends. */
    private static HttpResult failed(String key, Object... args) {
        return HttpResult.failure(tr(key, args) + " (https://api.example.com/users)", List.of());
    }

    private static HttpFailureKind kind(String key, Object... args) {
        return HttpFailureKind.of(failed(key, args));
    }

    @Test
    void eachEngineMessageNamesItsKind() {
        assertEquals(HttpFailureKind.REFUSED, kind("status.http.error.refused", "127.0.0.1:18799"));
        assertEquals(HttpFailureKind.UNKNOWN_HOST, kind("status.http.error.unknownHost", "nope.invalid"));
        assertEquals(HttpFailureKind.NO_ROUTE, kind("status.http.error.noRoute", "10.0.0.9:80"));
        assertEquals(HttpFailureKind.TIMEOUT, kind("status.http.error.connectTimeout", "a:1", "10 s"));
        assertEquals(HttpFailureKind.TIMEOUT, kind("status.http.error.connectTimeoutTls", "a:1", "10 s"));
        assertEquals(HttpFailureKind.TIMEOUT, kind("status.http.error.timeout", "30 s"));
        assertEquals(HttpFailureKind.TIMEOUT, kind("status.http.error.timeoutDirective", "5 s"));
        assertEquals(HttpFailureKind.CERTIFICATE, kind("status.http.error.untrustedCert", "self-signed.local"));
        assertEquals(HttpFailureKind.CERTIFICATE, kind("status.http.error.certName", "wrong.host"));
        assertEquals(HttpFailureKind.TLS, kind("status.http.error.tls", "a.example", "handshake_failure"));
        assertEquals(HttpFailureKind.CLOSED, kind("status.http.error.closed", "a:443"));
        assertEquals(HttpFailureKind.CLOSED, kind("status.http.error.closedHttp", "a:443"));
        assertEquals(HttpFailureKind.NOT_HTTP, kind("status.http.error.notHttp", "a:22"));
        assertEquals(HttpFailureKind.BAD_URL, kind("status.http.error.badUrl", "Illegal character"));
        assertEquals(HttpFailureKind.BAD_URL, kind("status.http.error.scheme", "ftp"));
        assertEquals(HttpFailureKind.BAD_URL, kind("status.http.error.noScheme", "/users"));
        assertEquals(HttpFailureKind.OTHER, kind("status.http.error.other", "boom"));
        assertEquals(HttpFailureKind.OTHER, kind("status.http.error.memory"));
    }

    @Test
    void unsentScriptsAndCancelsHaveTheirOwnKinds() {
        assertEquals(HttpFailureKind.NOT_SENT, kind("status.http.secretsRefused", "$processEnv.TOKEN"));
        assertEquals(HttpFailureKind.NOT_SENT, kind("httppanel.saveFirstToUse", "a body file"));
        assertEquals(
                HttpFailureKind.SCRIPT,
                kind("status.http.preScriptNotRun", tr("http.preScript.why.untrusted")),
                "a pre-request script that did not run kept the request unsent");
        HttpResult scriptError = HttpResult.failure(tr("status.http.preScriptNotRun", "x"), List.of())
                .withTests(new HttpTests(List.of(), List.of(), "ReferenceError: foo"), List.of());
        assertEquals(HttpFailureKind.SCRIPT, HttpFailureKind.of(scriptError));
        assertEquals(
                HttpFailureKind.CANCELLED,
                HttpFailureKind.of(HttpResult.failure(HttpClientService.CANCELLED, List.of())));
    }

    @Test
    void aResponseOrAnUnknownErrorIsOther() {
        assertEquals(HttpFailureKind.OTHER, HttpFailureKind.of(null));
        assertEquals(
                HttpFailureKind.OTHER,
                HttpFailureKind.of(HttpFixtures.text(200, List.of(), "ok", "text/plain", 1, 2, null)));
        assertEquals(HttpFailureKind.OTHER, HttpFailureKind.of(HttpResult.failure("Verbindung abgelehnt", List.of())));
    }

    @Test
    void theTitlesAndTheTlsKinds() {
        assertEquals("Connection refused", HttpFailureKind.REFUSED.title());
        assertEquals(tr("httppanel.notSent"), HttpFailureKind.NOT_SENT.title());
        assertEquals(tr("httppanel.requestFailed"), HttpFailureKind.OTHER.title());
        assertTrue(HttpFailureKind.CERTIFICATE.tls());
        assertTrue(HttpFailureKind.TLS.tls());
        assertFalse(HttpFailureKind.REFUSED.tls());
        for (HttpFailureKind k : HttpFailureKind.values()) {
            assertFalse(k.title().startsWith("httppanel."), "every title is in the catalog: " + k);
        }
    }

    @Test
    void itReadsTheUiLanguage() {
        try {
            com.editora.i18n.Messages.init("de");
            assertEquals(HttpFailureKind.REFUSED, kind("status.http.error.refused", "127.0.0.1:1"));
            assertEquals("Verbindung abgelehnt", HttpFailureKind.REFUSED.title());
        } finally {
            com.editora.i18n.Messages.init("en");
        }
    }
}
