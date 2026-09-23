package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitOutputLinksTest {

    @Test
    void findsFilesInStatusAndDiffLines() {
        assertEquals(
                List.of("src/Main.java"),
                GitOutputLinks.find("\tmodified:   src/Main.java").stream()
                        .map(GitOutputLinks.Link::file)
                        .toList());
        assertEquals(
                List.of("old name.txt", "new name.txt"),
                GitOutputLinks.find("diff --git a/old name.txt b/new name.txt").stream()
                        .map(GitOutputLinks.Link::file)
                        .toList());
        assertEquals(
                List.of("src/Main.java"),
                GitOutputLinks.find("+++ b/src/Main.java").stream()
                        .map(GitOutputLinks.Link::file)
                        .toList());
        assertEquals(
                List.of("old name.txt", "new name.txt"),
                GitOutputLinks.find("\trenamed:    old name.txt -> new name.txt").stream()
                        .map(GitOutputLinks.Link::file)
                        .toList());
        assertEquals(
                List.of("src/New.java"),
                GitOutputLinks.find(" create mode 100644 src/New.java").stream()
                        .map(GitOutputLinks.Link::file)
                        .toList());
    }

    @Test
    void atReturnsOnlyTheClickedPath() {
        String text = "modified:   src/Main.java\n";
        int path = text.indexOf("src/Main.java");
        assertEquals("src/Main.java", GitOutputLinks.at(text, path).file());
        assertNull(GitOutputLinks.at(text, 0));
    }

    @Test
    void ignoresNonFileAndDevNullLines() {
        assertEquals(List.of(), GitOutputLinks.find("On branch master"));
        assertEquals(List.of(), GitOutputLinks.find("+++ /dev/null"));
    }
}
