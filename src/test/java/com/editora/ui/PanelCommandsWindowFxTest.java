package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.input.Clipboard;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands of {@link PanelCommands} from a real window's registry, as the palette runs them: with nothing showing
 * that offers a row or button, each says so on the status line and does nothing; the ones that act on the caret's
 * line, the active file or the toolbar do that, or say why not (KBD-01, MENU-05 in Pauta 6.2.0).
 */
@Tag("fx")
class PanelCommandsWindowFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(() -> fx.controller.overlayHost.hide());
            fx.close();
            fx = null;
        }
    }

    private void window(Path dir) throws Exception {
        fx = FxWindowFixture.create(dir, shared -> shared.getSettings().setDebugSupport(true));
    }

    /** Runs {@code id} and answers the status line it left ("" for none). FX thread. */
    private String run(String id) {
        CommandRegistry registry = fx.controller.registry;
        assertTrue(registry.get(id).isPresent(), id + " is registered");
        StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
        MessageLog log = FxTestSupport.field(bar, "messageLog");
        log.clear();
        assertTrue(registry.run(id), id + " ran");
        return log.entries().isEmpty() ? "" : log.entries().get(0).text();
    }

    private static String notOffered(String id) {
        return tr("status.rows.notOffered", DebugPanelText.bareTitle(tr("command." + id)));
    }

    @Test
    void withNothingShowingThatOffersItEveryPanelCommandSaysSo(@TempDir Path dir) throws Exception {
        window(dir);
        FxTestSupport.runOnFx(() -> {
            for (ToolWindow open : fx.controller.toolWindows.getOpenToolWindows()) {
                fx.controller.toolWindows.close(open); // nothing showing that a row or button is in
            }
            fx.controller.toolWindows.open(fx.controller.problemsToolWindow); // open, with nothing selected in it
            List<String> wrong = new ArrayList<>();
            for (List<String> family : PanelCommands.FAMILIES) {
                for (String id : family) {
                    String said = run(id);
                    if (!notOffered(id).equals(said)) {
                        wrong.add(id + " said: " + said);
                    }
                }
            }
            assertEquals(List.of(), wrong, "a fresh window offers none of them");
            assertFalse(fx.controller.overlayHost.isShowing(), "and nothing was opened for them");
        });
    }

    @Test
    void theCaretLineAndActiveFileCommandsSayWhyWithNoFile(@TempDir Path dir) throws Exception {
        window(dir);
        FxTestSupport.runOnFx(() -> {
            String noFile = tr("status.bookmarks.noFile");
            assertEquals(noFile, run("debug.toggleStopOnLine"));
            assertEquals(noFile, run("file.revealFolderInFileManager"));
            assertEquals(noFile, run("file.openTerminalInFolder"));
            assertEquals(tr("status.debug.noMemberMarkHere"), run("debug.toggleMarkedMembersEnabled"));
            assertEquals(tr("status.debug.noMemberMarkHere"), run("debug.removeMarkedMembers"));
            assertFalse(fx.controller.overlayHost.isShowing());
        });
    }

    @Test
    void withAFileOpenTheCommandsActOnItsLineAndFolders(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Main.java"), "class Main {\n    Runnable r = () -> {};\n}\n");
        window(dir);
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        PulseChecks.untilOnFx( // the file is read off the FX thread
                () -> fx.controller.activeBuffer() != null
                        && fx.controller
                                        .activeBuffer()
                                        .getArea()
                                        .getParagraphs()
                                        .size()
                                >= 3,
                "the file's text",
                Duration.ofSeconds(5));
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("status.debug.noLambdaBreakpointHere", 1), run("debug.toggleStopOnLine"));
            com.editora.editor.EditorBuffer b = fx.controller.activeBuffer();
            b.getFocusedArea().moveTo(1, b.getArea().getParagraph(1).getText().indexOf("->"));
            run("debug.toggleLambdaBreakpoint"); // a breakpoint that stops in the lambda only
            var bp = b.getBreakpointManager().get(1);
            if (bp != null && bp.hasLambdas()) {
                boolean before = bp.stopsOnLine();
                run("debug.toggleStopOnLine");
                assertEquals(!before, b.getBreakpointManager().get(1).stopsOnLine(), "as the gutter's row turns it");
            }
            assertEquals("", run("file.openTerminalInFolder"), "a picker of the file's folders");
            assertTrue(fx.controller.overlayHost.isShowing());
            fx.controller.overlayHost.hide();
            assertEquals("", run("file.revealFolderInFileManager"), "a picker of the file's folders");
            assertTrue(fx.controller.overlayHost.isShowing());
            fx.controller.overlayHost.hide();
            assertEquals("", run("view.hideToolWindowIcon"), "a picker of the side bar's icons");
            assertTrue(fx.controller.overlayHost.isShowing());
            fx.controller.overlayHost.hide();
        });
    }

    @Test
    void aRowOfSeveralIsPickedFromAListOverTheWindow(@TempDir Path dir) throws Exception {
        window(dir);
        FxTestSupport.runOnFx(() -> {
            List<String> fired = new ArrayList<>();
            javafx.scene.control.MenuItem high = new javafx.scene.control.MenuItem("High");
            high.setOnAction(e -> fired.add("high"));
            PanelCommands.pick(
                    fx.controller, "todo.setSelectedPriority", List.of(high, new javafx.scene.control.MenuItem("Low")));
            assertTrue(fx.controller.overlayHost.isShowing(), "the rows' words, to choose from");
            fx.controller.overlayHost.hide();
            assertEquals(List.of(), fired, "nothing chosen, nothing fired");
        });
    }

    @Test
    void aPomsMavenRowsAreOfferedForTheSelectedProjectRow(@TempDir Path dir) throws Exception {
        window(dir);
        Path root = fx.useProject();
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TreeView<Path> tree = FxTestSupport.field(fx.controller.projectPanel(), "tree");
            tree.setRoot(new javafx.scene.control.TreeItem<>(root));
            tree.getSelectionModel().clearSelection();
            assertTrue(FocusCommands.findBelow(tree, "maven.updateVersions") == null, "no row selected");
            tree.getSelectionModel().select(tree.getRoot());
            assertTrue(
                    FocusCommands.findBelow(tree, "maven.updateVersions") != null,
                    "the project's folder has a pom: its Maven rows");
        });
    }

    @Test
    void withEveryIconHiddenThereIsNothingToHide(@TempDir Path dir) throws Exception {
        window(dir);
        FxTestSupport.runOnFx(() -> {
            for (ToolWindow tw : fx.controller.toolWindows.getStripeToolWindows()) {
                fx.controller.toolWindows.setVisible(tw, false);
            }
            assertEquals(
                    tr("status.projectMap.nothingToPick", CommandTips.title("view.hideToolWindowIcon")),
                    run("view.hideToolWindowIcon"));
            assertFalse(fx.controller.overlayHost.isShowing());
        });
    }

    @Test
    void theHeadingLevelAndQuickEvaluateSayWhyWhereTheyCannotRun(@TempDir Path dir) throws Exception {
        Path notes = Files.writeString(dir.resolve("notes.md"), "# Title\n\nsome text\n");
        window(dir);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("status.notMarkdown"), run("markdown.setHeadingLevel"));
            assertEquals(tr("palette.disabled.needsSuspended"), run("debug.quickEvaluate"), "nothing is paused");
            fx.controller.fileWorkflows.openPath(notes);
        });
        PulseChecks.untilOnFx(() -> fx.controller.activeBuffer() != null, "the file open", Duration.ofSeconds(5));
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    tr("palette.disabled.needsSuspended"), run("debug.quickEvaluate"), "a file open, nothing paused");
            assertEquals("", run("markdown.setHeadingLevel"), "Normal text and the six levels, to choose from");
            assertTrue(fx.controller.overlayHost.isShowing());
            fx.controller.overlayHost.hide();
        });
    }

    @Test
    void theToolbarsCustomizeAndTheAboutDetailsAreCommands(@TempDir Path dir) throws Exception {
        window(dir);
        FxTestSupport.runOnFx(() -> {
            ToolbarCoordinator toolbar = fx.controller.toolbarCoordinator;
            boolean shown = toolbar.toggleCustomizeIfShown(); // on, where the toolbar shows
            if (shown) {
                assertTrue((boolean) FxTestSupport.<Boolean>field(toolbar, "customizing"));
                assertEquals(tr("status.toolbar.customizeOff"), run("toolbar.toggleCustomize"));
            } else {
                assertEquals(tr("status.toolbar.hidden"), run("toolbar.toggleCustomize"));
            }
            assertFalse((boolean) FxTestSupport.<Boolean>field(toolbar, "customizing"));
            if (shown) {
                run("view.toggleToolbar"); // hidden: there is nothing to customize on it
                assertEquals(tr("status.toolbar.hidden"), run("toolbar.toggleCustomize"));
                run("view.toggleToolbar");
            }
            assertEquals(tr("about.copied"), run("help.copyAboutDetails"));
            assertTrue(Clipboard.getSystemClipboard().getString().contains(com.editora.AppInfo.VERSION));
        });
    }
}
