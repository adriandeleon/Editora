package com.editora.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain-language messages for requests that got no response. */
class HttpErrorsTest {

    private static final Duration T = Duration.ofSeconds(60);
    private static final Duration C = Duration.ofSeconds(15);

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static String msg(Throwable t, String url) {
        return HttpErrors.message(t, url, T, C, false);
    }

    @Test
    void refusedAndUnknownHostAreTold() {
        assertEquals(
                "Connection refused — is a server listening on 127.0.0.1:8080?",
                msg(new ConnectException(), "http://127.0.0.1:8080/x"));
        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());
        assertEquals(
                "Host not found: no-such.example — check the name and your network",
                msg(new IOException(unresolved), "http://no-such.example/x"));
        assertEquals(
                "Connection refused — is a server listening on h.test:443?",
                msg(new ConnectException(), "https://h.test/"));
    }

    @Test
    void timeoutsNameTheirLimit() {
        assertEquals(
                "No response within 2 s — the request timed out (@timeout)",
                HttpErrors.message(new HttpTimeoutException("x"), "http://h/", Duration.ofSeconds(2), C, true));
        assertEquals(
                "Could not connect to h:80 within 15 s — is the host reachable?",
                msg(new HttpConnectTimeoutException("x"), "http://h/"));
        assertEquals(
                "Could not connect to h:443 over TLS within 15 s — if the server speaks plain HTTP, use http://",
                msg(new HttpConnectTimeoutException("x"), "https://h/"));
    }

    @Test
    void certificateProblemsAreTold() {
        SSLHandshakeException pkix = new SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path to requested target");
        assertEquals(
                "The certificate of self.test is not trusted (self-signed, or issued by an unknown authority)",
                msg(pkix, "https://self.test/"));
        assertEquals(
                "The certificate of other.test is for a different host name",
                msg(
                        new SSLHandshakeException("No subject alternative DNS name matching other.test found."),
                        "https://other.test/"));
    }

    @Test
    void schemesAndBadUrlsAndOtherFailures() {
        assertEquals(
                "Only http:// and https:// URLs can be sent, not \"file:\"",
                msg(new IllegalArgumentException("invalid URI scheme file"), "file:///etc/hostname"));
        assertEquals(
                "The URL is not valid: Illegal character in scheme name at index 4",
                msg(new IllegalArgumentException("Illegal character in scheme name at index 4"), "post http://x"));
        assertEquals(
                "Not enough memory to handle this response — clear the response history or save large downloads with \">> file\"",
                msg(new OutOfMemoryError(), "http://h/"));
        assertEquals("The request failed: IllegalStateException", msg(new IllegalStateException(), "http://h/"));
    }

    @Test
    void jdkInternalsAreTranslated() {
        assertEquals(
                "The request body changed size while it was being sent: a file it uploads was modified. Run the request again",
                msg(
                        new IOException(
                                "SocketTube(6) [HttpClient-6-Worker-1] Too many bytes in request body. Expected: 3, got: 5"),
                        "http://h/"));
        assertEquals(
                "The request body changed size while it was being sent: a file it uploads was modified. Run the request again",
                msg(new IOException("Too few bytes returned by the publisher (1000/2000000)"), "http://h/"));
        assertEquals(
                "h:22 did not answer with HTTP — is this the right port?",
                msg(new IOException("Invalid status line: \"SSH-2.0-OpenSSH_9.6\""), "http://h:22/"));
        assertEquals(
                "The URL is not valid: port out of range:99999",
                msg(new IllegalArgumentException("port out of range:99999"), "http://h:99999/"));
    }

    @Test
    void aPlainRequestThatIsHungUpOnSuggestsHttps() {
        assertEquals(
                "h:8443 closed the connection without answering — if the server expects https://, use https://",
                msg(new java.io.EOFException("EOF reached while reading"), "http://h:8443/"));
        assertEquals(
                "h:443 closed the connection without answering",
                msg(new java.io.EOFException("EOF reached while reading"), "https://h/"));
    }

    @Test
    void aUrlWithoutASchemeSaysSoInsteadOfJavasWording() {
        // X13: an undefined {{host}} leaves "/xml", and Java's "URI with undefined scheme" told the user nothing.
        assertEquals(
                "The URL \"/xml\" does not start with http:// or https:// — is a variable in it not defined?",
                msg(new IllegalArgumentException("URI with undefined scheme"), "/xml"));
    }
}
