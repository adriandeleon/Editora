package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.editora.doctor.DoctorCheck;
import com.editora.lsp.FormatFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTML review's install fixes that are pure decisions in the window layer (A5, A8, A10, C15). */
class HtmlInstallUiTest {

    /** A5: a failed format is not "Already formatted". */
    @Test
    void aFailedFormatSaysHowItFailed() {
        assertEquals(
                tr("status.lsp.formatFailedNoAnswer"),
                FormatOutcomes.noChange(
                        new FormatFailure(FormatFailure.Kind.NO_ANSWER, null), "html", () -> true, null));
        assertEquals(
                tr("status.lsp.formatFailedLost"),
                FormatOutcomes.noChange(new FormatFailure(FormatFailure.Kind.LOST, null), "html", () -> true, null));
        assertEquals(
                tr("status.lsp.formatFailedRefused", "Unexpected token"),
                FormatOutcomes.noChange(
                        new FormatFailure(FormatFailure.Kind.REFUSED, "Unexpected token"), "html", () -> true, null));
        assertEquals(tr("status.lsp.formatNoChange"), FormatOutcomes.noChange(null, "html", () -> true, null));
        assertEquals(
                BashLspUi.formatNoChange("bash", () -> false, null),
                FormatOutcomes.noChange(null, "bash", () -> false, null),
                "Bash's own no-change words are kept");
    }

    /** A10: the crash tip names lemminx only for XML, and the user's own command gets no Install advice. */
    @Test
    void theCrashTipFitsTheServerAndWhoChoseItsCommand() {
        assertEquals(
                "doctor.tip.serverCrashing", DoctorCoordinator.troubleTip("html", true, DoctorCheck.Install.SERVER));
        assertEquals(
                "doctor.tip.serverCrashingXml", DoctorCoordinator.troubleTip("xml", true, DoctorCheck.Install.SERVER));
        assertEquals(
                "doctor.tip.serverFailing", DoctorCoordinator.troubleTip("html", false, DoctorCheck.Install.SERVER));
        assertEquals(
                "doctor.tip.serverTroubleOwn", DoctorCoordinator.troubleTip("html", true, DoctorCheck.Install.NONE));
        assertFalse(tr("doctor.tip.serverCrashing").contains("lemminx"), "no XML aside on another server");
        assertTrue(tr("doctor.tip.serverCrashingXml").contains("lemminx"));
        DoctorCheck own = DoctorCheck.checking("lsp.html", "lsp", "HTML", "node x.js")
                .warn("exited", "doctor.tip.serverTroubleOwn");
        assertTrue(own.restartable(), "Restart Language Servers is still offered");
        assertFalse(own.reinstallable(), "no Install that would change nothing");
    }

    /** A8: the npm servers count as needing Node.js; the one Settings names stands in for one on PATH. */
    @Test
    void theNpmServersNeedNodeAndTheChosenOneCounts(@TempDir Path dir) throws Exception {
        assertEquals(
                tr("install.lang.css") + ", " + tr("install.lang.html"),
                DoctorCoordinator.nodeServers(Set.of("html", "css", "go", "typescript")));
        assertEquals("", DoctorCoordinator.nodeServers(Set.of("typescript", "go")), "TypeScript has its own words");
        assertFalse(DoctorCoordinator.nodeChosen(""));
        assertFalse(DoctorCoordinator.nodeChosen("node"), "a bare name is PATH's");
        Path node =
                Files.writeString(Files.createDirectories(dir.resolve("bin")).resolve("node"), "");
        assertTrue(DoctorCoordinator.nodeChosen(node.toString()));
        assertTrue(com.editora.lsp.LspServerRegistry.isSupported("html"));
    }

    /** C15: the first HTML or CSS file asks to turn code intelligence on, in words that name Node.js. */
    @Test
    void htmlAndCssAskToTurnCodeIntelligenceOn() {
        assertTrue(InstallPromptCoordinator.TURN_ON_SERVERS.containsAll(Set.of("html", "css", "xml", "bash")));
        assertEquals("install.banner.enableHtml", InstallPromptCoordinator.turnOnMessageKey("html"));
        assertEquals("install.banner.enableCss", InstallPromptCoordinator.turnOnMessageKey("css"));
        assertTrue(tr("install.banner.enableHtml").contains("Node.js"));
        assertTrue(tr("install.banner.webServer", "HTML", "4.10.0").contains("vscode-langservers-extracted 4.10.0"));
    }
}
