package com.editora.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure HTTP Client environment-file name reader. */
class HttpEnvTest {

    @Test
    void readsEnvironmentNamesInOrder() {
        String json = """
                {
                  "dev":  { "host": "http://localhost:8080" },
                  "prod": { "host": "https://api.example.com" }
                }
                """;
        assertEquals(List.of("dev", "prod"), HttpEnv.environmentNames(json));
    }

    @Test
    void blankOrMalformedYieldsEmpty() {
        assertTrue(HttpEnv.environmentNames("").isEmpty());
        assertTrue(HttpEnv.environmentNames(null).isEmpty());
        assertTrue(HttpEnv.environmentNames("{ not json").isEmpty());
        assertTrue(HttpEnv.environmentNames("[1,2,3]").isEmpty()); // not an object
    }
}
