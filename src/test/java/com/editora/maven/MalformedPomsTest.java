package com.editora.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link MalformedPoms}: the pom that stops lemminx-maven's coordinate completion (review 2, E3). */
class MalformedPomsTest {

    @TempDir
    Path root;

    private static final String GOOD =
            "<project><modelVersion>4.0.0</modelVersion><artifactId>a</artifactId></project>";

    private Path pom(String dir, String text) throws IOException {
        return Files.writeString(Files.createDirectories(root.resolve(dir)).resolve("pom.xml"), text);
    }

    @Test
    void theMalformedPomIsFoundWithItsLine() throws IOException {
        pom(".", GOOD);
        pom("core", GOOD);
        Path broken = pom("broken", "<project>\n  <dependencies>\n    <groupId>x\n  </dependencies>\n</project>\n");

        MalformedPoms.Problem p = MalformedPoms.firstIn(root, f -> null);

        assertNotNull(p);
        assertEquals(broken, p.file());
        assertEquals(4, p.line());
    }

    @Test
    void anInvalidModelOrADoctypeIsNotMalformed() throws IOException {
        pom(".", GOOD);
        pom("nogav", "<project><dependencies><dependency/></dependencies></project>"); // well-formed: harmless
        pom("dtd", "<?xml version=\"1.0\"?>\n<!DOCTYPE project>\n<project/>");
        assertNull(MalformedPoms.firstIn(root, f -> null));
    }

    @Test
    void buildOutputAndHiddenFoldersAreNotSearchedAndOpenTextWins() throws IOException {
        pom(".", GOOD);
        pom("target/classes/META-INF", "<project>");
        pom(".git/x", "<project>");
        assertNull(MalformedPoms.firstIn(root, f -> null));

        Path core = pom("core", GOOD);
        Map<Path, String> open = Map.of(core, "<project><unclosed></project>");
        MalformedPoms.Problem p = MalformedPoms.firstIn(root, open::get);
        assertNotNull(p, "the unsaved text is what the server sees");
        assertEquals(core, p.file());
        assertNull(MalformedPoms.firstIn(root.resolve("missing"), f -> null));
    }
}
