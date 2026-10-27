package com.editora.lsp;

import java.util.List;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lemminx-maven quirks the client smooths over (Maven review C18–C20). */
class MavenPomServerTest {

    private static List<String> types(List<MavenPomServer.ReadyGate.Status> out) {
        return out.stream().map(MavenPomServer.ReadyGate.Status::type).toList();
    }

    @Test
    void theReadyStatusWaitsForTheMavenInitializationProgressEnd() {
        var gate = new MavenPomServer.ReadyGate();
        assertEquals(List.of(), gate.onStatus("ServiceReady", null), "held: the extension is still loading");
        assertTrue(gate.holding());
        assertEquals(List.of("Progress"), types(gate.onStatus("Progress", "Loading Maven components…")));
        assertEquals(
                List.of("ProgressEnd"),
                types(gate.onStatus("ProgressEnd", "Finished loading local artifacts from ''/home/x/.m2''.")),
                "an unrelated progress end does not release it");
        var release = gate.onStatus("ProgressEnd", "Maven initialization done");
        assertEquals(List.of("ProgressEnd", "ServiceReady"), types(release));
        assertNull(release.get(1).message());
        assertFalse(gate.holding());
        // Afterwards nothing is held or duplicated.
        assertEquals(List.of("ProgressEnd"), types(gate.onStatus("ProgressEnd", "Maven initialization done")));
    }

    @Test
    void anErrorOrRelaunchDropsAHeldReady() {
        var gate = new MavenPomServer.ReadyGate();
        gate.onStatus("ServiceReady", null);
        assertEquals(List.of("Error"), types(gate.onStatus("Error", "boom")));
        assertFalse(gate.holding());
        gate.onStatus("ServiceReady", null);
        assertEquals(List.of("Launching"), types(gate.onStatus("Launching", null)));
        assertFalse(gate.holding());
        assertEquals(List.of("ProgressEnd"), types(gate.onStatus("ProgressEnd", "Maven initialization done")));
    }

    @Test
    void aReadyWithAMessagePassesThrough() {
        var gate = new MavenPomServer.ReadyGate();
        assertEquals(List.of("Ready"), types(gate.onStatus("Ready", "Ready")));
        assertFalse(gate.holding());
    }

    private static LspDiagnostic diag(String source, LspDiagnostic.Severity severity, String message) {
        return new LspDiagnostic(0, 0, 0, 1, severity, message, null, source, null, false, false);
    }

    @Test
    void aNullSourceDefaultsToTheServerNameAndTheSchemaDownloadNoticeIsInformation() {
        LspDiagnostic maven = diag(null, LspDiagnostic.Severity.ERROR, "'dependencies.dependency.version' is missing.");
        LspDiagnostic xml = diag("xml", LspDiagnostic.Severity.ERROR, "Could not find artifact org.x:y:jar:1");
        LspDiagnostic download = diag(
                "xml",
                LspDiagnostic.Severity.ERROR,
                "The resource 'http://maven.apache.org/xsd/maven-4.0.0.xsd' is downloading in the cache path '/x'.");
        List<LspDiagnostic> out = MavenPomServer.adjustDiagnostics("lemminx-maven", List.of(maven, xml, download));
        assertEquals("lemminx-maven", out.get(0).source());
        assertEquals(LspDiagnostic.Severity.ERROR, out.get(0).severity());
        assertSame(xml, out.get(1), "an entry with nothing to change is the same object");
        assertEquals(LspDiagnostic.Severity.INFO, out.get(2).severity());
        assertEquals("xml", out.get(2).source());
        assertTrue(MavenPomServer.adjustDiagnostics("x", null).isEmpty());
    }

    @Test
    void propertyReferencesAreDroppedUnlessTheTypedPrefixStartsWithADollar() {
        List<String> items = List.of("commons-io", "${env.HOME}", "${sun.boot.library.path}", "commons-lang3");
        assertEquals(
                List.of("commons-io", "commons-lang3"), MavenPomServer.filterCompletion("commons-", items, s -> s));
        assertSame(items, MavenPomServer.filterCompletion("${en", items, s -> s), "a $ prefix wants them");
        assertSame(items, MavenPomServer.filterCompletion("", items, s -> s), "no prefix known: keep everything");
        assertTrue(
                MavenPomServer.filterCompletion("x", List.<String>of(), s -> s).isEmpty());
    }

    @Test
    void theElementPrefixIsTheTextAfterTheLastTagBeforeTheCaret() {
        String line = "      <artifactId>commons-</artifactId>";
        assertEquals("commons-", MavenPomServer.elementPrefix(line, line.indexOf("</")));
        assertEquals("", MavenPomServer.elementPrefix(line, line.indexOf("commons")));
        assertEquals("$", MavenPomServer.elementPrefix("<version>$", 10));
        assertEquals("abc", MavenPomServer.elementPrefix("abc", 99));
        assertEquals("", MavenPomServer.elementPrefix(null, 3));
    }

    /** Review 2 / Maven C20: a source that is the pom's URI reads as the server's name. */
    @Test
    void aDiagnosticSourceThatIsAUriBecomesTheServerName() {
        LspDiagnostic d = new LspDiagnostic(
                1,
                0,
                1,
                4,
                LspDiagnostic.Severity.WARNING,
                "Duplicating managed version 2.0.17 for slf4j-api",
                null,
                "file:///home/u/proj/core/pom.xml",
                null,
                false,
                false);
        assertEquals(
                "lemminx-maven",
                MavenPomServer.adjustDiagnostics("lemminx-maven", List.of(d))
                        .get(0)
                        .source());
        LspDiagnostic named =
                new LspDiagnostic(1, 0, 1, 4, LspDiagnostic.Severity.WARNING, "m", null, "xml", null, false, false);
        assertSame(
                named,
                MavenPomServer.adjustDiagnostics("lemminx-maven", List.of(named))
                        .get(0));
    }

    /** Review 2, E6: a plugin's "managed in" link carries the project folder and the parent's URI; the last wins. */
    @Test
    void aDoubledFileUriInAHoverLinkIsCutToTheLastOne() {
        String doubled = "**The artifact is managed in [g:a:1](file:/home/u/proj/file:/home/u/proj/reactor/pom.xml)**";
        assertEquals(
                "**The artifact is managed in [g:a:1](file:/home/u/proj/reactor/pom.xml)**",
                MavenPomServer.fixHoverLinks(doubled));
        String fine = "[g:a:1](file:/home/u/proj/reactor/pom.xml#L28,22-L28,22) and [web](https://example.com/a)";
        assertEquals(fine, MavenPomServer.fixHoverLinks(fine));
        assertEquals("", MavenPomServer.fixHoverLinks(null));
    }
}
