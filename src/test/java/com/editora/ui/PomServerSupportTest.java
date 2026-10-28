package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PomServerSupport}'s pure parts (review 2, A5 and E3). */
class PomServerSupportTest {

    @Test
    void theCoordinateElementIsTheOneTheCaretIsIn() {
        assertEquals("artifactId", PomServerSupport.coordinateElement("      <artifactId>commons-", 26));
        assertEquals("version", PomServerSupport.coordinateElement("<version></version>", 9));
        assertEquals("groupId", PomServerSupport.coordinateElement("<groupId>org.", 13));
        assertNull(PomServerSupport.coordinateElement("<scope>te", 9), "not a coordinate");
        assertNull(PomServerSupport.coordinateElement("<artifactId>a</artifactId>", 26), "after the element");
        assertNull(PomServerSupport.coordinateElement("    ", 2));
        assertNull(PomServerSupport.coordinateElement(null, 0));
    }

    @Test
    void anAnswerOfOnlyMarkupIsTheDegradedOne() {
        assertTrue(PomServerSupport.onlyMarkup(List.of("<!--", "<![CDATA[")));
        assertTrue(PomServerSupport.onlyMarkup(List.of()));
        assertFalse(PomServerSupport.onlyMarkup(List.of("commons-lang3", "<!--")));
    }

    @Test
    void theFileIsNamedRelativeToTheProject() {
        Path root = Path.of("/w/reactor").toAbsolutePath();
        assertEquals(
                Path.of("broken", "pom.xml").toString(),
                PomServerSupport.displayPath(root, root.resolve("broken/pom.xml")));
        Path outside = Path.of("/elsewhere/pom.xml").toAbsolutePath();
        assertEquals(outside.toString(), PomServerSupport.displayPath(root, outside));
    }

    @Test
    void aLocalFileMayAlwaysUseThePomServer() {
        assertTrue(PomServerSupport.pomServerRunsFor(Path.of("/w/pom.xml"), "java -cp \"/cfg/plugins/lsp/maven/*\" X"));
        assertTrue(PomServerSupport.pomServerRunsFor(null, ""));
    }
}
