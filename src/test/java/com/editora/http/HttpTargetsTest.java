package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The request target when a {@code Host:} header names the host. */
class HttpTargetsTest {

    private static List<String[]> headers(String... nameValues) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            out.add(new String[] {nameValues[i], nameValues[i + 1]});
        }
        return out;
    }

    private static List<String> names(List<String[]> headers) {
        return headers.stream().map(h -> h[0]).toList();
    }

    @Test
    void theOriginFormIsBuiltFromTheHostHeader() {
        HttpTargets.Target t = HttpTargets.resolve("/api/items?x=1", headers("Accept", "*/*", "Host", "example.com"));
        assertEquals("http://example.com/api/items?x=1", t.url());
        assertEquals(List.of("Accept"), names(t.headers()));
    }

    @Test
    void port443MeansHttps() {
        assertEquals(
                "https://example.com/a",
                HttpTargets.resolve("/a", headers("host", "example.com:443")).url());
        assertEquals(
                "http://example.com:8080/a",
                HttpTargets.resolve("/a", headers("Host", "example.com:8080")).url());
    }

    @Test
    void aHostThatRepeatsTheUrlIsDroppedAndADifferentOneKept() {
        HttpTargets.Target same = HttpTargets.resolve("http://example.com:8080/a", headers("Host", "EXAMPLE.com:8080"));
        assertEquals(List.of(), names(same.headers()));
        HttpTargets.Target vhost = HttpTargets.resolve("http://10.0.0.5/a", headers("Host", "api.example.com"));
        assertEquals("http://10.0.0.5/a", vhost.url());
        assertEquals(List.of("Host"), names(vhost.headers()));
    }

    @Test
    void withoutAHostNothingChanges() {
        List<String[]> h = headers("Accept", "*/*");
        HttpTargets.Target t = HttpTargets.resolve("/relative", h);
        assertEquals("/relative", t.url());
        assertEquals(h, t.headers());
    }
}
