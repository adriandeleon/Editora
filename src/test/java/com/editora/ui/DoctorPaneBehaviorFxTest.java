package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the Doctor page behaves as results arrive (the Doctor review's C1, C3, A4, A5, C2): the keyboard focus
 * stays on the control it was on, a new run keeps the last results, problems come first, sections that are all
 * OK fold, the summary counts in words, and the copied report names every row.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorPaneBehaviorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final DoctorPane.Actions NO_ACTIONS = new DoctorPane.Actions() {
        @Override
        public void refresh() {}

        @Override
        public void install(DoctorCheck check) {}

        @Override
        public void openSettings(String settingsKey) {}
    };

    private static DoctorCheck gitOk() {
        return DoctorCheck.checking("git", "vcs", "Git", "git")
                .withSettings("git")
                .ok("git version 2.47.3");
    }

    private static DoctorCheck xmlMissing() {
        return DoctorCheck.checking("lsp.xml", "lsp", "XML", "lemminx")
                .withSettings("lsp")
                .withInstall(DoctorCheck.Install.SERVER, "xml")
                .missing("doctor.tip.missingInstallable", "lemminx");
    }

    private static DoctorCheck optional(String id, String label) {
        return DoctorCheck.checking("lsp." + id, "lsp", label, id)
                .withInstall(DoctorCheck.Install.SERVER, id)
                .optional("doctor.tip.serverOptional");
    }

    private record Shown(DoctorPane pane, Stage stage) {}

    private static Shown show(List<DoctorCheck> rows) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            DoctorPane pane = new DoctorPane(NO_ACTIONS);
            pane.setChecks(rows);
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1200, 900));
            stage.show();
            pane.applyCss();
            pane.layout();
            return new Shown(pane, stage);
        });
    }

    private static VBox body(DoctorPane pane) {
        return FxTestSupport.field(pane, "body");
    }

    private static Node row(DoctorPane pane, String id) {
        for (Node n : body(pane).getChildren()) {
            if (id.equals(n.getProperties().get("doctor.id"))) {
                return n;
            }
        }
        return null;
    }

    private static Button button(Node row, String role) {
        return (Button) row.lookupAll(".doctor-action").stream()
                .filter(n -> role.equals(n.getProperties().get("doctor.role")))
                .findFirst()
                .orElse(null);
    }

    @Test
    void focusStaysOnTheSameControlWhenItsRowUpdatesOrMovesGroup() throws Exception {
        Shown s = show(List.of(gitOk(), xmlMissing(), optional("lua", "Lua")));
        try {
            Node xml = FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.xml"));
            assertNotNull(xml, "a problem is shown even though its section has OK rows folded");
            FxTestSupport.runOnFx(() -> button(xml, "install").requestFocus());

            // A result for another row, then for this one (in place), then this row leaving "Needs attention".
            FxTestSupport.runOnFx(() -> s.pane().updateCheck(gitOk()));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> s.pane().updateCheck(xmlMissing()));
            FxTestSupport.drainFx();
            Node focused = FxTestSupport.callOnFx(() -> s.stage().getScene().getFocusOwner());
            assertEquals("install", focused.getProperties().get("doctor.role"));
            assertSame(xml, FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.xml")), "the row node is reused");

            // XML becomes the section's second not-installed tool, so its row folds away: the fold's Show link
            // takes the focus rather than a button that is no longer on the page.
            FxTestSupport.runOnFx(() -> s.pane().updateCheck(optional("xml", "XML")));
            FxTestSupport.drainFx();
            Node after = FxTestSupport.callOnFx(() -> s.stage().getScene().getFocusOwner());
            assertTrue(isInside(after, s.pane()), "focus left the Doctor page: " + after);
            assertEquals("fold:opt:lsp", after.getProperties().get("doctor.role"));
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    /**
     * An install older than its pin keeps its install action, labelled Update; a server that crashes or fails its
     * requests offers the Install… its tip names (XML/pom review 2, A2); any other warning gets none.
     */
    @Test
    void anOutdatedInstallOffersUpdateAFailingOneInstallAndAPlainWarningNeither() throws Exception {
        DoctorCheck outdated = DoctorCheck.checking("lsp.groovy", "lsp", "Groovy", "gls")
                .withInstall(DoctorCheck.Install.SERVER, "groovy")
                .outdated("0.4.1 — /x/gls", "doctor.tip.pinOutdated", "0.4.1", "0.4.8");
        DoctorCheck failing = DoctorCheck.checking("lsp.xml", "lsp", "XML", "lemminx")
                .withInstall(DoctorCheck.Install.SERVER, "xml")
                .warn("lemminx", "doctor.tip.serverFailing");
        DoctorCheck crashing = DoctorCheck.checking("lsp.yaml", "lsp", "YAML", "yaml-language-server")
                .withInstall(DoctorCheck.Install.SERVER, "yaml")
                .warn("GLIBC_2.34 not found", "doctor.tip.serverCrashing");
        DoctorCheck plain = DoctorCheck.checking("lsp.json", "lsp", "JSON", "vscode-json-language-server")
                .withInstall(DoctorCheck.Install.SERVER, "json")
                .warn("", "doctor.tip.pinUnknown", "1.0");
        Shown s = show(List.of(outdated, failing, crashing, plain));
        try {
            Button update = FxTestSupport.callOnFx(() -> button(row(s.pane(), "lsp.groovy"), "install"));
            assertNotNull(update, "Update is offered");
            assertEquals(com.editora.i18n.Messages.tr("doctor.action.update"), update.getText());
            for (String id : List.of("lsp.xml", "lsp.yaml")) {
                Button install = FxTestSupport.callOnFx(() -> button(row(s.pane(), id), "install"));
                assertNotNull(install, id + ": the Install… its tip names is there");
                assertEquals(com.editora.i18n.Messages.tr("doctor.action.install"), install.getText());
            }
            assertNull(
                    FxTestSupport.callOnFx(() -> button(row(s.pane(), "lsp.json"), "install")),
                    "another warning is not reinstalled by its button");

            FxTestSupport.runOnFx(() -> s.pane().installStarted("lsp.groovy"));
            FxTestSupport.drainFx();
            Button busy = FxTestSupport.callOnFx(() -> button(row(s.pane(), "lsp.groovy"), "install"));
            assertEquals(com.editora.i18n.Messages.tr("doctor.action.updating"), busy.getText());
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    /**
     * Bash review A7: a server row this window gave up on offers Restart Language Servers beside Install…, and the
     * button runs it.
     */
    @Test
    void aGivenUpServerRowOffersRestart() throws Exception {
        DoctorCheck loop = DoctorCheck.checking("lsp.bash", "lsp", "Bash / Shell", "bash-language-server start")
                .withInstall(DoctorCheck.Install.SERVER, "bash")
                .warn("/x/bash-language-server", "doctor.tip.serverCrashLoop");
        int[] restarts = {0};
        Shown s = FxTestSupport.callOnFx(() -> {
            DoctorPane pane = new DoctorPane(new DoctorPane.Actions() {
                @Override
                public void refresh() {}

                @Override
                public void install(DoctorCheck check) {}

                @Override
                public void openSettings(String settingsKey) {}

                @Override
                public void restartServers() {
                    restarts[0]++;
                }
            });
            pane.setChecks(List.of(loop, gitOk()));
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1200, 900));
            stage.show();
            pane.applyCss();
            pane.layout();
            return new Shown(pane, stage);
        });
        try {
            Button restart = FxTestSupport.callOnFx(() -> button(row(s.pane(), "lsp.bash"), "restart"));
            assertNotNull(restart, "Restart is offered");
            assertEquals(tr("doctor.action.restartServers"), restart.getText());
            assertNotNull(FxTestSupport.callOnFx(() -> button(row(s.pane(), "lsp.bash"), "install")), "Install… too");
            FxTestSupport.runOnFx(restart::fire);
            assertEquals(1, restarts[0]);
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    /**
     * A working server with a newer release moves out of its folded all-OK section into "Updates available", above
     * the sections, with its tip and an Update button; the summary counts it.
     */
    @Test
    void aNewerReleaseIsShownUnderUpdatesAvailable() throws Exception {
        DoctorCheck newer = DoctorCheck.checking("lsp.python", "lsp", "Python", "pyright-langserver")
                .withInstall(DoctorCheck.Install.LANG, "PYTHON")
                .newer("pyright 1.1.411 — /x/pyright-langserver", "1.1.411", "1.1.414");
        DoctorCheck json = DoctorCheck.checking("lsp.json", "lsp", "JSON", "vscode-json-language-server")
                .withInstall(DoctorCheck.Install.SERVER, "json")
                .ok("/x/vscode-json-language-server");
        Shown s = show(List.of(gitOk(), json, newer));
        try {
            List<String> texts = FxTestSupport.callOnFx(() -> body(s.pane()).getChildren().stream()
                    .filter(n -> n instanceof javafx.scene.control.Label)
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .toList());
            assertEquals(com.editora.i18n.Messages.tr("doctor.section.updates"), texts.get(0), String.valueOf(texts));
            Node row = FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.python"));
            assertNotNull(row, "shown although its section has only OK rows (folded)");
            Button update = FxTestSupport.callOnFx(() -> button(row, "install"));
            assertEquals(com.editora.i18n.Messages.tr("doctor.action.update"), update.getText());
            String tip = com.editora.i18n.Messages.tr("doctor.tip.newerAvailable", "1.1.411", "1.1.414");
            assertTrue(
                    FxTestSupport.callOnFx(() -> row.lookupAll(".doctor-tip").stream()
                            .anyMatch(n -> tip.equals(((javafx.scene.control.Label) n).getText()))),
                    "the tip says which version is newer");
            assertTrue(
                    FxTestSupport.callOnFx(
                            () -> s.pane().lookupAll(".doctor-summary-updates").size() == 1),
                    "the summary counts it");
            assertNull(FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.json")), "the plain OK row stays folded");
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    @Test
    void aNewRunKeepsTheLastResultsUntilTheirProbesReturn() throws Exception {
        Shown s = show(List.of(gitOk(), xmlMissing()));
        try {
            FxTestSupport.runOnFx(() -> s.pane()
                    .beginRun(List.of(
                            DoctorCheck.checking("git", "vcs", "Git", "git"),
                            DoctorCheck.checking("lsp.xml", "lsp", "XML", "lemminx"),
                            DoctorCheck.checking("typst", "preview", "Typst CLI", "typst"))));
            List<DoctorCheck> during = FxTestSupport.callOnFx(s.pane()::currentChecks);
            assertEquals(DoctorStatus.OK, during.get(0).status(), "Git keeps its result while re-checked");
            assertEquals("git version 2.47.3", during.get(0).detail());
            assertEquals(DoctorStatus.CHECKING, during.get(2).status(), "a new row starts as Checking");
            assertTrue(FxTestSupport.callOnFx(s.pane()::isRunning));
            Node git = FxTestSupport.callOnFx(() -> {
                s.pane().toggleExpandedForTest("vcs");
                return row(s.pane(), "git");
            });
            assertTrue(git.getStyleClass().contains("doctor-stale"), "an old result is dimmed");
            String summary = FxTestSupport.callOnFx(() -> summaryText(s.pane()));
            assertTrue(summary.contains(tr("doctor.summary.checking", 0, 3)), summary);

            FxTestSupport.runOnFx(() -> {
                s.pane().updateCheck(gitOk());
                s.pane().endRun();
            });
            FxTestSupport.drainFx();
            assertFalse(FxTestSupport.callOnFx(s.pane()::isRunning));
            assertFalse(FxTestSupport.callOnFx(
                    () -> row(s.pane(), "git").getStyleClass().contains("doctor-stale")));
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    /**
     * A cache row stays right after its server's row (XML/pom review 2, A9): with XML in "Needs attention" the XML
     * schema cache used to sit among the healthy rows, between two other servers.
     */
    @Test
    void aCacheRowFollowsItsServerIntoNeedsAttention() throws Exception {
        DoctorCheck python =
                DoctorCheck.checking("lsp.python", "lsp", "Python", "pyright").ok("pyright");
        DoctorCheck cache = DoctorCheck.checking(DoctorCoordinator.XML_CACHE_ID, "lsp", "XML schema cache", "")
                .ok("2.0 kB — /tmp/x");
        DoctorCheck json =
                DoctorCheck.checking("lsp.json", "lsp", "JSON", "json").ok("json");
        Shown s = show(List.of(python, xmlMissing(), cache, json));
        try {
            List<Object> ids = FxTestSupport.callOnFx(() -> body(s.pane()).getChildren().stream()
                    .map(n -> n.getProperties().get("doctor.id"))
                    .filter(java.util.Objects::nonNull)
                    .toList());
            int xml = ids.indexOf("lsp.xml");
            assertEquals(xml + 1, ids.indexOf(DoctorCoordinator.XML_CACHE_ID), ids.toString());
            assertEquals(0, xml, "both in Needs attention, first: " + ids);
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    @Test
    void problemsComeFirstAllOkSectionsFoldAndNotInstalledToolsFoldTogether() throws Exception {
        Shown s = show(List.of(gitOk(), xmlMissing(), optional("lua", "Lua"), optional("sql", "SQL")));
        try {
            List<String> texts = FxTestSupport.callOnFx(() -> body(s.pane()).getChildren().stream()
                    .map(DoctorPaneBehaviorFxTest::firstText)
                    .toList());
            assertEquals(tr("doctor.section.attention"), texts.get(0), texts.toString());
            assertNotNull(FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.xml")));
            assertEquals(null, FxTestSupport.callOnFx(() -> row(s.pane(), "git")), "an all-OK section is folded");
            assertEquals(null, FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.lua")), "two not-installed rows fold");
            assertTrue(texts.stream().anyMatch(t -> t.equals(tr("doctor.status.optional"))), texts.toString());

            FxTestSupport.runOnFx(() -> {
                s.pane().toggleExpandedForTest("vcs");
                s.pane().toggleExpandedForTest("opt:lsp");
            });
            assertNotNull(FxTestSupport.callOnFx(() -> row(s.pane(), "git")));
            assertNotNull(FxTestSupport.callOnFx(() -> row(s.pane(), "lsp.lua")));
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    @Test
    void theSummaryCountsInWordsAndTheReportNamesEveryRow() throws Exception {
        Shown s = show(List.of(gitOk(), xmlMissing(), optional("lua", "Lua")));
        try {
            String summary = FxTestSupport.callOnFx(() -> summaryText(s.pane()));
            assertTrue(summary.contains(tr("doctor.summary.problems", 1)), summary);
            assertFalse(summary.contains("problems"), "one problem, not \"1 problems\": " + summary);
            assertTrue(summary.contains(tr("doctor.summary.optional", 1)), summary);

            String report = FxTestSupport.callOnFx(s.pane()::reportText);
            assertTrue(report.startsWith("# " + tr("doctor.title")), report);
            assertTrue(report.contains("[" + tr("doctor.status.ok") + "] Git (git): git version 2.47.3"), report);
            assertTrue(report.contains("[" + tr("doctor.status.missing") + "] XML (lemminx)"), report);
            assertTrue(report.contains(tr("doctor.tip.missingInstallable", "lemminx")), report);
            assertTrue(report.contains("[" + tr("doctor.status.optional") + "] Lua"), report);
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    @Test
    void aRemoteProjectIsNamedInTheHeader() throws Exception {
        Shown s = show(List.of(gitOk()));
        try {
            FxTestSupport.runOnFx(() -> s.pane().setMachine("ssh://build-box"));
            Label note = FxTestSupport.callOnFx(() -> (Label) s.pane().lookup(".doctor-remote-note"));
            assertTrue(FxTestSupport.callOnFx(note::isVisible));
            assertEquals(tr("doctor.remoteNote", "ssh://build-box"), FxTestSupport.callOnFx(note::getText));
            FxTestSupport.runOnFx(() -> s.pane().setMachine(null));
            assertFalse(FxTestSupport.callOnFx(note::isVisible));
        } finally {
            FxTestSupport.runOnFx(s.stage()::hide);
        }
    }

    private static String summaryText(DoctorPane pane) {
        javafx.scene.layout.HBox summary = FxTestSupport.field(pane, "summary");
        StringBuilder sb = new StringBuilder();
        for (Node n : summary.getChildren()) {
            sb.append(((javafx.scene.control.Labeled) n).getText()).append(" | ");
        }
        return sb.toString();
    }

    private static String firstText(Node n) {
        if (n instanceof javafx.scene.control.Labeled l) {
            return l.getText();
        }
        if (n instanceof javafx.scene.Parent p) {
            for (Node ch : p.getChildrenUnmodifiable()) {
                String t = firstText(ch);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static boolean isInside(Node n, Node root) {
        for (Node p = n; p != null; p = p.getParent()) {
            if (p == root) {
                return true;
            }
        }
        return false;
    }
}
