package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which env files a request file uses, which names it offers, which choice it keeps (A9 wiring, C6). */
class HttpEnvFilesTest {

    private static final Path ROOT = Path.of("/work/project").toAbsolutePath();

    @Test
    void theNearestFolderWithAnEnvFileWinsSearchingUpToTheProjectRoot() {
        Path api = ROOT.resolve("api/v2");
        Set<Path> files = Set.of(ROOT.resolve("http-client.env.json"));
        assertEquals(ROOT, HttpEnvFiles.envDir(api, ROOT, files::contains), "found two folders up");
        Set<Path> privateOnly = Set.of(ROOT.resolve("api/http-client.private.env.json"));
        assertEquals(ROOT.resolve("api"), HttpEnvFiles.envDir(api, ROOT, privateOnly::contains));
        Set<Path> beside = Set.of(api.resolve("http-client.env.json"), ROOT.resolve("http-client.env.json"));
        assertEquals(api, HttpEnvFiles.envDir(api, ROOT, beside::contains), "the file's own folder first");
    }

    @Test
    void theSearchStopsAtTheProjectRootAndFallsBackToTheFilesFolder() {
        Path api = ROOT.resolve("api");
        Set<Path> above = Set.of(ROOT.getParent().resolve("http-client.env.json"));
        assertEquals(api, HttpEnvFiles.envDir(api, ROOT, above::contains), "nothing above the project is used");
        assertEquals(ROOT.getParent(), HttpEnvFiles.envDir(api, null, above::contains), "no project: keep going");
    }

    @Test
    void theNamesOfBothFilesAreOffered() {
        assertEquals(
                List.of("dev", "prod", "local"),
                HttpEnvFiles.mergedNames(List.of("dev", "prod"), List.of("dev", "local")));
        assertEquals(List.of("local"), HttpEnvFiles.mergedNames(null, List.of("local")));
    }

    @Test
    void aRememberedNameTheFileDoesNotDefineFallsBackToNone() {
        assertEquals("dev", HttpEnvFiles.choose("dev", List.of("", "dev")));
        assertEquals("", HttpEnvFiles.choose("dev", List.of("", "staging")));
        assertEquals("", HttpEnvFiles.choose(null, List.of("", "dev")));
    }
}
