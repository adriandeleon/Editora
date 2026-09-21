package com.editora.ui;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.*;

import com.editora.agent.AcpJson;
import com.editora.agent.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in visible desktop review. Captures only isolated fixture windows, never the surrounding desktop. */
class AgentDesktopReviewTest {
    @Test
    void visibleAgentDialogsTranscriptAndDiff() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.desktop.review"));
        assertNotEquals(
                "Headless",
                System.getProperty("glass.platform"),
                "Run with -Dglass.platform=gtk -Djava.awt.headless=false");
        FxTestSupport.bootToolkit();
        Path shots = Path.of("target/agent-desktop-review");
        Files.createDirectories(shots);
        var stopped = new AtomicInteger();
        var panel = FxTestSupport.callOnFx(() ->
                new AgentPanel(stopped::incrementAndGet, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {}));
        var stage = FxTestSupport.callOnFx(() -> {
            var s = new Stage();
            s.setTitle("Editora native agent — desktop review fixture");
            var scene = new Scene(panel, 940, 700);
            scene.getStylesheets()
                    .add(AgentPanel.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            s.setScene(scene);
            s.show();
            panel.setModelLabel("Local model · discovered profile");
            panel.setModeLabel("Workspace");
            panel.appendLine("Resumed session · previous validation is stale; reread before editing.");
            panel.appendLine("❯ Fix the Java rename regression and validate the affected project.");
            panel.setPlan(List.of(
                    new AcpJson.PlanEntry("Trace document and LSP lifecycle", "completed"),
                    new AcpJson.PlanEntry("Apply revision-safe edits", "completed"),
                    new AcpJson.PlanEntry("Run tests and review diff", "in_progress")));
            panel.appendChunk(
                    "The changes preserve **unsaved user edits** and reject stale semantic proposals. The first test run failed; the agent repaired the implementation and reran validation.");
            panel.startTool("semantic_query");
            panel.appendToolResult(
                    "semantic_query", "References in JavaLanguageServer.java and its callers.", false, 38);
            panel.startTool("run_validation");
            panel.appendToolResult(
                    "run_validation",
                    "{\"passed\":false,\"operation\":\"TEST\",\"module\":\".\",\"isolation\":\"ISOLATED\",\"tests\":{\"tests\":12,\"failed\":1,\"failures\":[{\"class\":\"RenameTest\",\"test\":\"keepsUnsavedBuffer\",\"message\":\"expected current revision\"}]}}",
                    true,
                    1234);
            panel.appendToolResult(
                    "run_validation",
                    "{\"passed\":true,\"operation\":\"TEST\",\"tests\":{\"tests\":12,\"failed\":0},\"savedRevisionCount\":3,\"isolation\":\"ISOLATED\"}",
                    false,
                    1190);
            panel.appendLine("↻ Previous output was incomplete. Retrying with a bounded output allowance.");
            var requirements = new AgentTaskContract();
            requirements.user("Fix the Java rename regression, add regression tests and run validation.");
            var acceptance = requirements.toJson();
            var debt = acceptance.putArray("evidenceDebt");
            debt.addObject()
                    .put("requirement", "R1")
                    .put("progress", "TASK_INCOMPLETE")
                    .put("missingEvidence", "Add the requested regression test")
                    .put("reasonInvalidated", "");
            debt.addObject()
                    .put("requirement", "R3")
                    .put("progress", "EVIDENCE_INCOMPLETE")
                    .put("missingEvidence", "Run fresh targeted validation")
                    .put("reasonInvalidated", "Document edit: Rename.java");
            panel.setAcceptance(acceptance.toString());
            panel.setBusy(true);
            return s;
        });
        try {
            capture(stage, shots.resolve("transcript.png"));
            var acceptancePane = FxTestSupport.<TitledPane>field(panel, "acceptancePane");
            FxTestSupport.runOnFx(() -> {
                assertTrue(acceptancePane.isVisible());
                acceptancePane.setExpanded(true);
            });
            capture(stage, shots.resolve("acceptance.png"));
            FxTestSupport.runOnFx(() -> acceptancePane.setExpanded(false));
            FxTestSupport.runOnFx(() -> panel.lookupAll(".titled-pane").stream()
                    .filter(TitledPane.class::isInstance)
                    .map(TitledPane.class::cast)
                    .filter(p -> p != acceptancePane)
                    .findFirst()
                    .orElseThrow()
                    .setExpanded(true));
            capture(stage, shots.resolve("expanded-tool.png"));
            FxTestSupport.runOnFx(() -> panel.lookupAll(".titled-pane").stream()
                    .filter(TitledPane.class::isInstance)
                    .map(TitledPane.class::cast)
                    .forEach(p -> p.setExpanded(
                            p.getText().contains(com.editora.i18n.Messages.tr("agent.validation.counts", 12, 1, 0)))));
            capture(stage, shots.resolve("validation-failure.png"));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Button>field(panel, "stopButton").fire());
            assertEquals(1, stopped.get());
            FxTestSupport.runOnFx(() -> {
                panel.setBusy(false);
                panel.appendLine("Cancelled · completed edits remain available for review.");
            });
            capture(stage, shots.resolve("cancelled.png"));
            var settings = new com.editora.config.Settings();
            settings.setAiProvider("lmstudio");
            settings.setAiLmStudioModel("review-fixture-model");
            FxTestSupport.runOnFx(() -> Platform.runLater(() -> AgentModelSettings.show(stage, settings, () -> {})));
            Window dialog = awaitDialog(stage);
            capture(dialog, shots.resolve("model-profile.png"));
            FxTestSupport.runOnFx(dialog::hide);
            var host = new CoordinatorHostStub() {
                public Window window() {
                    return stage;
                }
            };
            var coordinator = new NativeAgentCoordinator(host, null, () -> panel);
            var spec = new AgentTool.Spec(
                    "run_validation",
                    "test",
                    new ObjectMapper().createObjectNode(),
                    null,
                    AgentTool.Effect.EXTERNAL,
                    java.time.Duration.ofSeconds(10),
                    true,
                    "editora");
            var token = new AgentCancellation();
            var approval = NativeAgentCoordinator.class.getDeclaredMethod(
                    "approve", AgentTool.Spec.class, String.class, AgentCancellation.class, long.class);
            approval.setAccessible(true);
            var answer = CompletableFuture.supplyAsync(() -> {
                try {
                    return (Boolean) approval.invoke(
                            coordinator,
                            spec,
                            "{\"type\":\"TEST\",\"module\":\".\",\"isolation\":\"ISOLATED\"}",
                            token,
                            0L);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            dialog = awaitDialog(stage);
            capture(dialog, shots.resolve("validation-approval.png"));
            token.cancel();
            assertFalse(answer.get(5, TimeUnit.SECONDS));
            FxTestSupport.runOnFx(coordinator::shutdown);
        } finally {
            FxTestSupport.runOnFx(() -> {
                panel.clearTranscript();
                stage.close();
            });
        }
        Path files = Files.createTempDirectory("agent-desktop-diff-");
        Path before = Files.writeString(
                files.resolve("before.java"),
                "class Rename {\n    void renamed() {\n        closeDocument();\n    }\n}\n");
        Path after = Files.writeString(
                files.resolve("after.java"),
                "class Rename {\n    void renamed() {\n        closeDocument();\n        openRenamedDocument();\n    }\n}\n");
        try (var window = FxWindowFixture.createDiff(files.resolve("config"), before, after, c -> {})) {
            var diff = FxTestSupport.<Stage>field(window.controller, "stage");
            FxTestSupport.runOnFx(diff::show);
            capture(diff, shots.resolve("diff-review.png"));
        }
    }

    private static Window awaitDialog(Window owner) throws Exception {
        for (int i = 0; i < 50; i++) {
            Window found = FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                    .filter(w -> w != owner && w.isShowing() && w instanceof Stage s && s.getOwner() == owner)
                    .findFirst()
                    .orElse(null));
            if (found != null) return found;
            Thread.sleep(100);
        }
        throw new AssertionError("Dialog did not appear");
    }

    private static void capture(Window window, Path path) throws Exception {
        Thread.sleep(600);
        var image = FxTestSupport.callOnFx(() -> {
            window.requestFocus();
            assertTrue(window.isShowing());
            return window.getScene().snapshot(null);
        });
        var bitmap = new java.awt.image.BufferedImage(
                (int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var pixels = image.getPixelReader();
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++) bitmap.setRGB(x, y, pixels.getArgb(x, y));
        javax.imageio.ImageIO.write(bitmap, "png", path.toFile());
    }
}
