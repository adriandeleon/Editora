package screenshots;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertFalse;
import static lib.Assert.assertThrows;
import static lib.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lib.Json;
import lib.Repo;
import lib.TempDir;
import lib.Test;

class ShootTest implements AutoCloseable {

    private final TempDir dir = new TempDir();
    private final Path workspace = dir.resolve("ws");
    private final Path fixtures = dir.resolve("fixtures");

    ShootTest() throws IOException {
        Files.createDirectories(workspace.resolve("samples/debug/java"));
        Files.createDirectories(workspace.resolve("samples/debug/go"));
        Files.createDirectories(workspace.resolve("samples/build-tools/npm"));
        Files.createDirectories(fixtures);
    }

    @Override
    public void close() {
        dir.close();
    }

    @Test
    void everyShippedScriptsFixtureLinesParse() throws IOException {
        List<Path> scripts = Shoot.scriptsIn(Repo.scripts().resolve("screenshots"));
        assertTrue(scripts.size() >= 40, "scripts: " + scripts.size());
        int fixtureLines = 0;
        for (Path script : scripts) {
            try {
                fixtureLines += Shoot.directives(Files.readAllLines(script)).size();
            } catch (IllegalArgumentException e) {
                throw new AssertionError(script + ": " + e.getMessage(), e);
            }
            for (Shoot.Directive d : Shoot.directives(Files.readAllLines(script))) {
                if (d.name().equals("copy")) {
                    Path from = script.getParent().resolve(d.rest().split(" ")[0]);
                    assertTrue(Files.isRegularFile(from), script + " copies " + from + ", which is not there");
                }
            }
        }
        assertTrue(fixtureLines >= 20, "fixture lines across the scripts: " + fixtureLines);
    }

    @Test
    void anUnknownFixtureLineFailsWithItsNumber() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> Shoot.directives(List.of("start", "#@ wipe everything")));
        assertContains(e.getMessage(), "line 2");
        assertThrows(IllegalArgumentException.class, () -> Shoot.directives(List.of("#@ timeout soon")));
        assertThrows(IllegalArgumentException.class, () -> Shoot.directives(List.of("#@ replace a.txt ([ => b")));
        assertEquals(0, Shoot.directives(List.of("# a comment", "shot x.png")).size());
    }

    @Test
    void fixturesWriteInsertReplaceCopyAndLock() throws IOException {
        Path java = workspace.resolve("Shelf.java");
        Files.writeString(java, "class Shelf {\n    double total = 0;\n}\n");
        Files.writeString(fixtures.resolve("trail.json"), "{}");
        Files.writeString(workspace.resolve("fill.txt"), "fill");
        List<Shoot.Directive> ds = Shoot.directives(List.of(
                "#@ write notes/a.txt one\\r\\ntwo\\n",
                "#@ insert Shelf.java 2     // first in the class",
                "#@ replace Shelf.java (?m)^(    double total = 0;)$ => $1\\n    String label;",
                "#@ copy trail.json .editora/map-trails/t.json",
                "#@ readonly fill.txt"));
        for (Shoot.Directive d : ds) {
            Shoot.applyFixture(d, fixtures, workspace);
        }

        assertEquals("one\r\ntwo\n", Files.readString(workspace.resolve("notes/a.txt")));
        assertEquals(
                "class Shelf {\n    // first in the class\n    double total = 0;\n    String label;\n}\n",
                Files.readString(java));
        assertEquals("{}", Files.readString(workspace.resolve(".editora/map-trails/t.json")));
        assertFalse(Files.isWritable(workspace.resolve("fill.txt")), "readonly");
        workspace.resolve("fill.txt").toFile().setWritable(true);
    }

    @Test
    void aReplaceThatMatchesNothingFails() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "abc");
        Shoot.Directive d = Shoot.directives(List.of("#@ replace a.txt xyz => q")).getFirst();
        assertThrows(IllegalStateException.class, () -> Shoot.applyFixture(d, fixtures, workspace));
    }

    @Test
    void theWorkspaceAndEverySampleAreTrustedUnlessTheScriptSaysNone() throws IOException {
        Map<String, Object> file = Json.object(Json.parse(Shoot.trustedFolders(
                workspace, Shoot.directives(List.of("#@ trust docs")))));
        Map<String, Object> roots = Json.object(file.get("roots"));
        assertEquals(1L, ((Number) file.get("schemaVersion")).longValue());
        for (String p : List.of("", "samples/debug/java", "samples/debug/go", "samples/build-tools/npm", "docs")) {
            assertEquals(true, roots.get(workspace.resolve(p).toString()), p);
        }
        Map<String, Object> none = Json.object(Json.parse(Shoot.trustedFolders(
                workspace, Shoot.directives(List.of("#@ trust none")))));
        assertTrue(Json.object(none.get("roots")).isEmpty(), "trust none");
    }

    @Test
    void theVersionReadsAsARelease() throws IOException {
        Path info = dir.resolve("build-info.properties");
        Files.writeString(info, "build.version=0.29.0-SNAPSHOT\npauta.version=6.0.1\n");
        Shoot.writeReleaseBuildInfo(info, dir.resolve("override"));
        assertEquals(
                "build.version=0.29.0\npauta.version=6.0.1\n",
                Files.readString(dir.resolve("override/com/editora/build-info.properties")));
    }

    @Test
    void theDebugSampleCarriesTheFixedTime() throws IOException {
        Path shelf = workspace.resolve("samples/debug/java/src/main/java/com/example/shelf");
        Files.createDirectories(shelf);
        Files.writeString(shelf.resolve("Main.java"), "class Main {}");
        Shoot.stampSampleTimes(workspace);
        assertEquals(
                Shoot.SAMPLE_TIME.atZone(java.time.ZoneId.systemDefault()).toInstant(),
                Files.getLastModifiedTime(shelf.resolve("Main.java")).toInstant());
    }

    @Test
    void aScriptIsFoundByNameUnderTheScriptsFolder() throws IOException {
        Path scripts = Repo.scripts().resolve("screenshots");
        assertEquals(scripts.resolve("sample.txt").toAbsolutePath().normalize(), Shoot.resolve(scripts, "sample"));
        assertTrue(Shoot.scriptsIn(Shoot.resolve(scripts, "docs")).size() >= 5, "a folder is every script in it");
        assertThrows(IllegalArgumentException.class, () -> Shoot.resolve(scripts, "no-such-script"));
    }
}
