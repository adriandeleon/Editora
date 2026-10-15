package com.editora.http;

import java.net.http.HttpClient;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The protocol a request goes out with: JetBrains defaults, and what the request line asks for. */
class HttpVersionsTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void withoutAVersionPlainHttpIsHttp11AndHttpsNegotiatesHttp2() {
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                HttpVersions.choose(null, "http://h/").version());
        assertEquals(
                HttpClient.Version.HTTP_2,
                HttpVersions.choose(null, "https://h/").version());
        assertEquals(
                HttpClient.Version.HTTP_2, HttpVersions.choose("", "HTTPS://h/").version());
        assertNull(HttpVersions.choose(null, "http://h/").warning());
    }

    @Test
    void theRequestLineIsHonoured() {
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                HttpVersions.choose("HTTP/1.1", "https://h/").version());
        assertEquals(
                HttpClient.Version.HTTP_2,
                HttpVersions.choose("HTTP/2", "http://h/").version());
        assertNull(HttpVersions.choose("http/2", "http://h/").warning());
    }

    @Test
    void whatTheJdkCannotDoIsWarnedAndFallsBack() {
        HttpVersions.Choice prior = HttpVersions.choose("HTTP/2 (Prior Knowledge)", "http://h/");
        assertEquals(HttpClient.Version.HTTP_2, prior.version());
        assertNotNull(prior.warning());
        assertEquals(
                HttpClient.Version.HTTP_1_1,
                HttpVersions.choose("HTTP/1.0", "http://h/").version());
        assertNotNull(HttpVersions.choose("HTTP/1.0", "http://h/").warning());
        assertNotNull(HttpVersions.choose("HTTP/3", "https://h/").warning());
    }

    @Test
    void labels() {
        assertEquals("HTTP/2", HttpVersions.label(HttpClient.Version.HTTP_2));
        assertEquals("HTTP/1.1", HttpVersions.label(HttpClient.Version.HTTP_1_1));
    }
}
