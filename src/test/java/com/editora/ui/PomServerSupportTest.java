package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

    @Test
    void savingALeftOutPomWellFormedRestartsItsServer() {
        Path pom = Path.of("broken/pom.xml");
        assertTrue(PomServerSupport.wellFormedAgain(true, pom, "<project><artifactId>a</artifactId></project>"));
        assertFalse(PomServerSupport.wellFormedAgain(true, pom, "<project><artifactId>a</project>"), "still broken");
        assertFalse(PomServerSupport.wellFormedAgain(false, pom, "<project/>"), "never left out");
    }

    @Test
    void anHonestEmptyAnswerChangesNothing() {
        Path a = Path.of("/w/a/pom.xml");
        assertEquals(
                PomServerSupport.Act.DELIVER,
                PomServerSupport.decide(false, true, List.of(), Set.of(), List.of()),
                "markup only, nothing malformed on disk, no failure logged: a real no-match (E21)");
        assertEquals(
                PomServerSupport.Act.DELIVER,
                PomServerSupport.decide(false, true, List.of(a), Set.of(a), List.of()),
                "the malformed pom is already out: nothing to claim");
    }

    @Test
    void aProvenDegradationLeavesTheMalformedPomsOut() {
        Path a = Path.of("/w/a/pom.xml");
        Path b = Path.of("/w/b/pom.xml");
        assertEquals(
                PomServerSupport.Act.LEAVE_OUT, PomServerSupport.decide(true, true, List.of(a), Set.of(), List.of()));
        assertEquals(
                PomServerSupport.Act.LEAVE_OUT,
                PomServerSupport.decide(false, true, List.of(a, b), Set.of(a), List.of()),
                "markup only with a pom malformed on disk: it breaks the server at the next completion anyway");
        assertEquals(
                PomServerSupport.Act.DELIVER,
                PomServerSupport.decide(false, false, List.of(a), Set.of(), List.of()),
                "a real answer: nothing is left out");
    }

    @Test
    void aFailureWithNothingMalformedOnDiskRestartsTheRoot() {
        Path a = Path.of("/w/a/pom.xml");
        assertEquals(
                PomServerSupport.Act.RESTART_STALE,
                PomServerSupport.decide(true, true, List.of(), Set.of(), List.of()),
                "fixed after the server cached its failure (E21)");
        assertEquals(
                PomServerSupport.Act.RESTART_STALE,
                PomServerSupport.decide(true, true, List.of(a), Set.of(a), List.of()));
    }

    @Test
    void aLeftOutPomWellFormedAgainIsTakenBack() {
        Path a = Path.of("/w/a/pom.xml");
        assertEquals(
                PomServerSupport.Act.TAKE_BACK,
                PomServerSupport.decide(false, false, List.of(), Set.of(a), List.of(a)),
                "fixed outside Editora (E24)");
    }

    @Test
    void leftOutPomsAreComparedAsNormalizedPaths() {
        Path a = Path.of("/w/a/pom.xml").toAbsolutePath();
        assertTrue(
                PomServerSupport.holds(List.of(a), Path.of("/w/x/../a/pom.xml").toAbsolutePath()));
        assertFalse(PomServerSupport.holds(List.of(a), Path.of("/w/b/pom.xml").toAbsolutePath()));
    }

    @Test
    void theLeftOutStatusPluralizes() {
        com.editora.i18n.Messages.init("en");
        String one = com.editora.i18n.Messages.tr("status.maven.completionLeftOutMore", "a/pom.xml", "3", 1);
        String two = com.editora.i18n.Messages.tr("status.maven.completionLeftOutMore", "a/pom.xml", "3", 2);
        assertTrue(one.startsWith("Left out a/pom.xml"), one);
        assertTrue(one.contains("one more pom:"), one);
        assertTrue(two.contains("2 more poms:"), two);
    }
}
