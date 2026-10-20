package com.editora.ui;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Same-named open files get the nearest distinguishing folder in their tab title (Maven review C8). */
class TabTitlesTest {

    private static final Path ROOT = Path.of("/x").toAbsolutePath().normalize(); // Windows reads /x as relative

    private static Path p(String rel) {
        return ROOT.resolve(rel);
    }

    @Test
    void aUniqueNameStaysBare() {
        assertEquals(
                "pom.xml", TabTitles.titleFor(p("reactor/pom.xml"), List.of(p("reactor/pom.xml"), p("Main.java"))));
        assertEquals("pom.xml", TabTitles.titleFor(p("reactor/pom.xml"), List.of()));
    }

    @Test
    void sameNamedFilesGetTheirNearestFolder() {
        List<Path> open = List.of(p("reactor/pom.xml"), p("reactor/core/pom.xml"), p("reactor/app/pom.xml"));
        assertEquals("pom.xml · reactor", TabTitles.titleFor(open.get(0), open));
        assertEquals("pom.xml · core", TabTitles.titleFor(open.get(1), open));
        assertEquals("pom.xml · app", TabTitles.titleFor(open.get(2), open));
    }

    @Test
    void anAmbiguousNearestFolderWidensToTheNextOne() {
        List<Path> open = List.of(p("a/core/pom.xml"), p("b/core/pom.xml"));
        assertEquals("pom.xml · a/core", TabTitles.titleFor(open.get(0), open));
        assertEquals("pom.xml · b/core", TabTitles.titleFor(open.get(1), open));
    }

    @Test
    void untitledAndRemoteLikeEntriesAreIgnored() {
        List<Path> open = Arrays.asList(p("reactor/pom.xml"), null, p("other/Main.java"));
        assertEquals("pom.xml", TabTitles.titleFor(p("reactor/pom.xml"), open));
        assertEquals("untitled", TabTitles.titleFor(null, open));
    }

    @Test
    void theSameFileListedTwiceIsNotItsOwnRival() {
        assertEquals("pom.xml", TabTitles.titleFor(p("r/pom.xml"), List.of(p("r/pom.xml"), p("r/./pom.xml"))));
    }

    @Test
    void foldersAreCappedSoATitleStaysShort() {
        List<Path> open = List.of(p("1/2/3/4/core/pom.xml"), p("9/2/3/4/core/pom.xml"));
        assertEquals("pom.xml · 3/4/core", TabTitles.titleFor(open.get(0), open));
    }
}
