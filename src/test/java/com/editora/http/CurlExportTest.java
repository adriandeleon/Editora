package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the "Copy as cURL" export. */
class CurlExportTest {

    @Test
    void getOmitsMethodFlag() {
        String curl = CurlExport.toCurl("GET", "https://api.test/x", List.of(), null);
        assertTrue(curl.startsWith("curl 'https://api.test/x'"), curl);
        assertFalse(curl.contains("-X"), curl);
    }

    @Test
    void postIncludesMethodHeadersAndBody() {
        String curl = CurlExport.toCurl(
                "POST",
                "https://api.test/x",
                List.<String[]>of(new String[] {"Content-Type", "application/json"}),
                "{\"k\":\"v\"}");
        assertTrue(curl.contains("-X POST"), curl);
        assertTrue(curl.contains("-H 'Content-Type: application/json'"), curl);
        assertTrue(curl.contains("--data '{\"k\":\"v\"}'"), curl);
    }

    @Test
    void singleQuotesAreEscaped() {
        String curl = CurlExport.toCurl("POST", "https://api.test/x", List.of(), "it's");
        assertTrue(curl.contains("it'\\''s"), curl);
    }
}
