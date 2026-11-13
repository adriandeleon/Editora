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
    void anAnswerWithoutALoggedFailureChangesNothing() {
        Path a = Path.of("/w/a/pom.xml");
        assertEquals(
                PomServerSupport.Act.DELIVER,
                PomServerSupport.decide(false, List.of(), Set.of(), List.of(), true),
                "no failure logged: a real no-match (E21)");
        assertEquals(
                PomServerSupport.Act.DELIVER,
                PomServerSupport.decide(false, List.of(a), Set.of(), List.of(), true),
                "a pom malformed on disk the server never registered is no proof (review 4, E33)");
    }

    @Test
    void aProvenDegradationLeavesTheMalformedPomsOut() {
        Path a = Path.of("/w/a/pom.xml");
        Path b = Path.of("/w/b/pom.xml");
        assertEquals(
                PomServerSupport.Act.LEAVE_OUT, PomServerSupport.decide(true, List.of(a), Set.of(), List.of(), false));
        assertEquals(
                PomServerSupport.Act.LEAVE_OUT,
                PomServerSupport.decide(true, List.of(a, b), Set.of(a), List.of(), false));
    }

    @Test
    void aFailureWithNothingToLeaveOutRestartsOnlyWhenAPomChanged() {
        Path a = Path.of("/w/a/pom.xml");
        assertEquals(
                PomServerSupport.Act.RESTART_STALE,
                PomServerSupport.decide(true, List.of(), Set.of(), List.of(), true),
                "fixed on disk after the server cached its failure (E21)");
        assertEquals(
                PomServerSupport.Act.COMPLETION_OFF,
                PomServerSupport.decide(true, List.of(a), Set.of(a), List.of(), false),
                "nothing changed: a restart builds the same null project (review 4, E31)");
    }

    @Test
    void aRestartIsWorthItOnlyForPomsChangedSinceTheServerStartedAndNeverTwice() {
        Path a = Path.of("/w/a/pom.xml");
        var changed = new com.editora.maven.MalformedPoms.Scan(List.of(), List.of(), java.util.Map.of(a, 2_000L));
        assertTrue(PomServerSupport.restartable(changed, 1_000, null));
        assertFalse(PomServerSupport.restartable(changed, 2_000, null), "built after that change");
        assertFalse(PomServerSupport.restartable(changed, -1, null), "no server");
        assertFalse(PomServerSupport.restartable(changed, 1_000, java.util.Map.of(a, 2_000L)), "the same files again");
        assertTrue(PomServerSupport.restartable(changed, 1_000, java.util.Map.of(a, 1_500L)));
        var named = new com.editora.maven.MalformedPoms.Scan(
                List.of(),
                List.of(new com.editora.maven.MalformedPoms.Unbuildable(
                        a, 3, com.editora.maven.MalformedPoms.Why.DUPLICATE, "dependencies", true)),
                java.util.Map.of(a, 2_000L));
        assertFalse(PomServerSupport.restartable(named, 1_000, null), "a pom it cannot build is still there");
    }

    @Test
    void theCompletionOffStatusNamesTheLikelyPom() {
        com.editora.i18n.Messages.init("en");
        Path root = Path.of("/w/reactor").toAbsolutePath();
        var unknown = new com.editora.maven.MalformedPoms.Unbuildable(
                root.resolve("odd/pom.xml"), 12, com.editora.maven.MalformedPoms.Why.UNKNOWN, "dependencie", false);
        var dup = new com.editora.maven.MalformedPoms.Unbuildable(
                root.resolve("dup/pom.xml"), 13, com.editora.maven.MalformedPoms.Why.DUPLICATE, "dependencies", true);
        var dt = new com.editora.maven.MalformedPoms.Unbuildable(
                root.resolve("dt/pom.xml"), 2, com.editora.maven.MalformedPoms.Why.ENTITIES, "DOCTYPE", false);
        assertEquals(dup, PomServerSupport.likelyCulprit(List.of(unknown, dup)), "a pom with a parent first");
        assertEquals(dt, PomServerSupport.likelyCulprit(List.of(unknown, dup, dt)));
        assertNull(PomServerSupport.likelyCulprit(List.of()));

        assertEquals(
                "Maven completion is off: lemminx-maven could not build a pom in reactor, probably "
                        + Path.of("dup", "pom.xml")
                        + " (<dependencies> appears twice, line 13). See the Application Log.",
                PomServerSupport.completionOffStatus(root, dup));
        assertEquals(
                "Maven completion is off: lemminx-maven could not build a pom in reactor (see the Application Log).",
                PomServerSupport.completionOffStatus(root, null));
        assertFalse(PomServerSupport.completionOffStatus(root, dt).contains("malformed"));
    }

    @Test
    void aLeftOutPomWellFormedAgainIsTakenBack() {
        Path a = Path.of("/w/a/pom.xml");
        assertEquals(
                PomServerSupport.Act.TAKE_BACK,
                PomServerSupport.decide(false, List.of(), Set.of(a), List.of(a), false),
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
        String one = com.editora.i18n.Messages.tr("status.maven.completionLeftOutMore", "a/pom.xml", 3, 1);
        String two = com.editora.i18n.Messages.tr("status.maven.completionLeftOutMore", "a/pom.xml", 3, 2);
        assertTrue(one.startsWith("Left out a/pom.xml"), one);
        assertTrue(one.contains("one more pom:"), one);
        assertTrue(two.contains("2 more poms:"), two);
    }
}
