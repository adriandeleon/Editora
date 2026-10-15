package com.editora.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which requests read secrets from outside the .http file, also through variables. */
class SecretReadsTest {

    @Test
    void aDirectReadIsFound() {
        assertEquals(
                List.of("$processEnv.AWS_SECRET_ACCESS_KEY"),
                SecretReads.find("GET https://x/?k={{ $processEnv.AWS_SECRET_ACCESS_KEY }}", Map.of()));
        assertEquals(List.of("$dotenv.TOKEN"), SecretReads.find("Authorization: Bearer {{$dotenv.TOKEN}}", Map.of()));
    }

    @Test
    void aReadBehindVariablesIsFoundAndCyclesEnd() {
        Map<String, List<String>> defs = Map.of(
                "token", List.of("{{inner}}"),
                "inner", List.of("x{{$processEnv.GITHUB_TOKEN}}"),
                "a", List.of("{{b}}"),
                "b", List.of("{{a}}"));
        assertEquals(
                List.of("$processEnv.GITHUB_TOKEN"),
                SecretReads.find("GET https://x/\nAuthorization: Bearer {{token}}\n\n{{a}}", defs));
    }

    @Test
    void anUnusedSecretDefinitionAndOtherBuiltinsAreNotReads() {
        Map<String, List<String>> defs = Map.of("unused", List.of("{{$processEnv.HOME}}"));
        assertEquals(List.of(), SecretReads.find("GET https://x/{{$uuid}}?t={{$timestamp}}&h={{host}}", defs));
    }

    @Test
    void everyDefinitionOfANameCounts() {
        Map<String, List<String>> defs = Map.of("key", List.of("plain", "{{$dotenv.KEY}}"));
        assertEquals(List.of("$dotenv.KEY"), SecretReads.find("{{key}}", defs));
    }
}
