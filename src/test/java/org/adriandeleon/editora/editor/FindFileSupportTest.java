package org.adriandeleon.editora.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindFileSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void ranksRecentFilesFirstWhenQueryIsEmpty() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path srcDir = Files.createDirectories(workspaceRoot.resolve("src"));
        Path appJava = Files.writeString(srcDir.resolve("App.java"), "class App {}\n");
        Path utilJava = Files.writeString(srcDir.resolve("Util.java"), "class Util {}\n");

        List<FindFileSupport.Match> matches = FindFileSupport.rankMatches(
                workspaceRoot,
                List.of(srcDir, appJava, utilJava),
                List.of(utilJava),
                List.of(appJava),
                "",
                10
        );

        assertEquals(utilJava, matches.getFirst().path());
        assertTrue(matches.getFirst().recent());
        assertTrue(matches.stream().anyMatch(match -> match.path().equals(srcDir) && match.directory()));
    }

    @Test
    void ranksPathAwarePrefixMatchesBeforeBroaderFuzzyMatches() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path srcDir = Files.createDirectories(workspaceRoot.resolve("src"));
        Path appTs = Files.writeString(srcDir.resolve("app.ts"), "export {};\n");
        Path apiDir = Files.createDirectories(srcDir.resolve("api"));
        Path apiFile = Files.writeString(apiDir.resolve("client.ts"), "export {};\n");

        List<FindFileSupport.Match> matches = FindFileSupport.rankMatches(
                workspaceRoot,
                List.of(srcDir, appTs, apiDir, apiFile),
                List.of(),
                List.of(),
                "src/ap",
                10
        );

        assertFalse(matches.isEmpty());
        assertEquals("src/api/", matches.getFirst().displayPath());
        assertEquals("src/app.ts", matches.get(1).displayPath());
    }

    @Test
    void completesCommonDirectoryPrefixAndAddsTrailingSlash() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path utilsDir = Files.createDirectories(workspaceRoot.resolve("src/utils"));
        Path fileOne = Files.writeString(utilsDir.resolve("file.ts"), "export {};\n");
        Path fileTwo = Files.writeString(utilsDir.resolve("path.ts"), "export {};\n");

        List<FindFileSupport.Match> matches = FindFileSupport.rankMatches(
                workspaceRoot,
                List.of(utilsDir, fileOne, fileTwo),
                List.of(),
                List.of(),
                "src/u",
                10
        );

        assertEquals("src/utils/", FindFileSupport.completeInput(workspaceRoot, "src/u", matches).orElseThrow());
    }

    @Test
    void deletesWholePathSegmentsForControlBackspaceStyleBehavior() {
        assertEquals("src/utils/", FindFileSupport.deleteTrailingPathSegment("src/utils/file.ts"));
        assertEquals("src/", FindFileSupport.deleteTrailingPathSegment("src/utils/"));
        assertEquals("~/", FindFileSupport.deleteTrailingPathSegment("~"));
        assertEquals("/", FindFileSupport.deleteTrailingPathSegment("/tmp"));
        assertEquals("", FindFileSupport.deleteTrailingPathSegment("README.md"));
    }

    @Test
    void resolvesWorkspaceRelativeAbsoluteAndHomeRelativePaths() {
        Path workspaceRoot = tempDir.resolve("workspace").toAbsolutePath().normalize();
        Path homeDirectory = tempDir.resolve("home").toAbsolutePath().normalize();

        assertEquals(workspaceRoot.resolve("src/Main.java"),
                FindFileSupport.resolvePath(workspaceRoot, "src/Main.java", homeDirectory).orElseThrow());
        assertEquals(homeDirectory.resolve("notes/todo.md"),
                FindFileSupport.resolvePath(workspaceRoot, "~/notes/todo.md", homeDirectory).orElseThrow());
        assertEquals(Path.of("/tmp/demo.txt").toAbsolutePath().normalize(),
                FindFileSupport.resolvePath(workspaceRoot, "/tmp/demo.txt", homeDirectory).orElseThrow());
    }

    @Test
    void buildsDirectoryPreviewWithDescentHint() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path srcDir = Files.createDirectories(workspaceRoot.resolve("src"));

        FindFileSupport.Match match = new FindFileSupport.Match(srcDir, "src/", "workspace", true, false, false);
        FindFileSupport.Preview preview = FindFileSupport.buildPreview(workspaceRoot, match, 20, 500);

        assertEquals("src/", preview.title());
        assertTrue(preview.directory());
        assertTrue(preview.content().contains("Press Enter to descend"));
    }

    @Test
    void buildsTruncatedTextPreviewForHighlightedFile() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path file = Files.writeString(workspaceRoot.resolve("notes.txt"), String.join("\n",
                "line one",
                "line two",
                "line three",
                "line four",
                "line five"));

        FindFileSupport.Match match = new FindFileSupport.Match(file, "notes.txt", "workspace", false, false, false);
        FindFileSupport.Preview preview = FindFileSupport.buildPreview(workspaceRoot, match, 3, 80);

        assertEquals("notes.txt", preview.title());
        assertFalse(preview.directory());
        assertTrue(preview.truncated());
        assertTrue(preview.content().contains("line one"));
        assertTrue(preview.content().contains("… Preview truncated."));
    }
}
