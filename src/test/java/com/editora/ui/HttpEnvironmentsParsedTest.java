package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.editora.http.HttpEnv;
import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Env files are parsed where they are read; what the FX thread asks later parses nothing (PERF-01, Pauta 6.0.1). */
class HttpEnvironmentsParsedTest {

    private static final String PUBLIC =
            "{\"$shared\": {\"base\": \"https://x\"}, \"dev\": {\"user\": \"ann\", \"n\": {\"a\": 1}}, \"prod\": {}}";
    private static final String PRIVATE = "{\"dev\": {\"token\": \"t\"}, \"staging\": {\"user\": \"s\"}}";

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void whatIsKeptAnswersAsAParseWould(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(HttpEnv.PUBLIC_FILE), PUBLIC);
        Files.writeString(dir.resolve(HttpEnv.PRIVATE_FILE), PRIVATE);
        HttpEnvironments.EnvFiles files = HttpEnvironments.read(dir);

        List<String> heard = Collections.synchronizedList(new ArrayList<>());
        BlockingSites.listenForTest((site, thread) -> heard.add(site));
        for (String env : List.of("dev", "prod", "staging", "nowhere", "")) {
            assertEquals(HttpEnv.merged(PUBLIC, PRIVATE, env), files.variables(env), env);
        }
        assertEquals(HttpEnv.environmentNames(PUBLIC, PRIVATE), files.names());
        assertEquals(List.of(), files.problems());
        assertFalse(heard.contains("HttpEnvironments.parse"), "parsed when read, not when asked: " + heard);
    }

    @Test
    void aChangedFileIsParsedAgainAndABrokenOneIsReported(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(HttpEnv.PUBLIC_FILE), PUBLIC);
        assertEquals("ann", HttpEnvironments.read(dir).variables("dev").get("user"));
        Files.writeString(dir.resolve(HttpEnv.PUBLIC_FILE), "{\"dev\": {\"user\": \"bob\"}}");
        assertEquals("bob", HttpEnvironments.read(dir).variables("dev").get("user"), "a change on disk is seen");
        Files.writeString(dir.resolve(HttpEnv.PUBLIC_FILE), "{\"dev\": ");
        assertTrue(!HttpEnvironments.read(dir).problems().isEmpty(), "a broken file says why");
    }

    @Test
    void callersMayChangeWhatTheyAreGiven(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(HttpEnv.PUBLIC_FILE), PUBLIC);
        HttpEnvironments.EnvFiles files = HttpEnvironments.read(dir);
        files.variables("dev").put("user", "changed");
        files.names().clear();
        assertEquals("ann", files.variables("dev").get("user"), "the kept answer is not changed by a caller");
        assertFalse(files.names().isEmpty());
    }
}
