package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import atlantafx.base.controls.Breadcrumbs;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small layout defects from the UI review that only show on a laid-out scene. */
@Tag("fx")
class PolishFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Scene styled(javafx.scene.Parent root, double w, double h) {
        Scene scene = new Scene(root, w, h);
        scene.getStylesheets()
                .add(PolishFxTest.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        return scene;
    }

    @Test
    void commandPaletteListShowsAWholeNumberOfRows() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        for (int i = 0; i < 30; i++) {
            reg.register(Command.of("edit.cmd" + i, "Command " + i, () -> {}));
        }
        FxTestSupport.runOnFx(() -> {
            CommandPalette palette = new CommandPalette(reg, new KeymapManager(), c -> true);
            javafx.scene.layout.VBox card = FxTestSupport.field(palette, "content");
            Stage stage = new Stage();
            stage.setScene(styled(new javafx.scene.layout.StackPane(card), 640, 520));
            stage.show();
            try {
                FxTestSupport.call(palette, "filter", new Class<?>[] {String.class}, "");
                card.getParent().applyCss();
                card.getParent().layout();
                ListView<Command> list = FxTestSupport.field(palette, "list");
                double rowArea = list.getHeight()
                        - list.getInsets().getTop()
                        - list.getInsets().getBottom();
                assertEquals(CommandPalette.ROW_HEIGHT, list.getFixedCellSize());
                assertEquals(
                        CommandPalette.VISIBLE_ROWS,
                        rowArea / CommandPalette.ROW_HEIGHT,
                        0.02,
                        "the list ends on a row boundary (280px cut the last row in half)");
                assertEquals(CommandPalette.listHeight(4), CommandPalette.listHeight(0) + 4);
            } finally {
                stage.hide();
            }
        });
    }

    private static final GitPanel.Actions NOOP = (GitPanel.Actions) java.lang.reflect.Proxy.newProxyInstance(
            PolishFxTest.class.getClassLoader(), new Class<?>[] {GitPanel.Actions.class}, (proxy, method, args) -> {
                Class<?> r = method.getReturnType();
                return r == boolean.class ? Boolean.FALSE : r == int.class ? Integer.valueOf(0) : null;
            });

    @Test
    void commitWindowElidesLongPathsAndKeepsTheBranchReadable() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitPanel panel = new GitPanel(NOOP);
            Stage stage = new Stage();
            stage.setScene(styled(panel, 250, 400)); // a narrow right dock
            stage.show();
            try {
                panel.setStatus(new GitStatus(
                        true,
                        "feature/review-ui-accessibility-and-layout",
                        null,
                        0,
                        0,
                        List.of(new FileEntry(
                                "src/main/java/com/editora/ui/a/very/deep/package/of/things/SettingsWindow.java",
                                '.',
                                'M',
                                null))));
                panel.applyCss();
                panel.layout();
                TreeView<?> tree = FxTestSupport.field(panel, "tree");
                for (Node bar : tree.lookupAll(".scroll-bar")) {
                    if (bar instanceof ScrollBar sb && sb.getOrientation() == Orientation.HORIZONTAL) {
                        assertFalse(sb.isVisible(), "a long path must not bring a horizontal scrollbar");
                    }
                }
                TreeCell<?> fileCell = null;
                for (Node n : tree.lookupAll(".tree-cell")) {
                    TreeCell<?> cell = (TreeCell<?>) n;
                    if (cell.getText() != null && cell.getText().endsWith("SettingsWindow.java")) {
                        fileCell = cell;
                    }
                }
                assertNotNull(fileCell, "file row rendered");
                assertEquals(OverrunStyle.LEADING_ELLIPSIS, fileCell.getTextOverrun(), "the file name end stays");
                assertTrue(fileCell.getWidth() <= tree.getWidth() + 0.5, "the row is no wider than the tree");
                assertNotNull(fileCell.getTooltip());
                assertTrue(fileCell.getTooltip().getText().endsWith("SettingsWindow.java"), "full path in the tooltip");

                Label branch = FxTestSupport.field(panel, "branchLabel");
                assertTrue(branch.getWidth() >= GitPanel.BRANCH_MIN_WIDTH - 0.5, "branch was " + branch.getWidth());
                assertTrue(branch.getTooltip().getText().contains("feature/review-ui-accessibility-and-layout"));
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void breadcrumbStartsAtTheProjectRootForFilesInsideIt(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("workspace/demo"));
        Path inside = Files.createDirectories(project.resolve("src/main")).resolve("App.java");
        Path outside = Files.createDirectories(dir.resolve("elsewhere")).resolve("notes.txt");
        Files.writeString(inside, "class App {}");
        Files.writeString(outside, "x");
        FxTestSupport.runOnFx(() -> {
            FileBreadcrumb bar = new FileBreadcrumb(p -> {}, () -> project);
            bar.setEnabled(true);
            bar.setActiveFile(inside);
            assertEquals(List.of("demo", "src", "main", "App.java"), labels(bar));
            bar.setActiveFile(outside);
            List<String> full = labels(bar);
            assertEquals("notes.txt", full.get(full.size() - 1));
            assertTrue(full.size() > 2 && !full.contains("demo"), "outside the project: the full trail " + full);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(FileBreadcrumb bar) {
        Breadcrumbs<Path> crumbs = FxTestSupport.field(bar, "breadcrumbs");
        List<String> out = new ArrayList<>();
        for (TreeItem<Path> item = crumbs.getSelectedCrumb(); item != null; item = item.getParent()) {
            out.add(0, (String) FxTestSupport.call(bar, "crumbLabel", new Class<?>[] {Path.class}, item.getValue()));
        }
        return out;
    }

    @Test
    void splitViewKeepsTheModeToggleOffTheCodePaneAndNamesThePreviewButtons() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(Path.of("notes.md"));
            buffer.setContent("# Title\n\nSome prose.\n");
            Stage stage = new Stage();
            stage.setScene(styled(new javafx.scene.layout.StackPane(buffer.getNode()), 900, 500));
            stage.show();
            try {
                assertTrue(buffer.hasPreview(), "a Markdown buffer has a preview");
                MarkdownViewToggle toggle = new MarkdownViewToggle(buffer);
                buffer.setViewModeControl(toggle);
                Region codePane = FxTestSupport.field(buffer, "root");
                assertSame(codePane, toggle.getParent(), "Editor mode: top-right of the code pane");

                buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
                assertNotSame(codePane, toggle.getParent(), "Split: not over the half-width code pane's first line");
                Node previewHost = FxTestSupport.field(buffer, "previewHost");
                assertSame(previewHost, toggle.getParent(), "Split: it rides the preview side");

                buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
                assertSame(previewHost, toggle.getParent());
                buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR);
                assertSame(codePane, toggle.getParent(), "back in Editor mode it returns to the code pane");

                buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
                List<Button> zoom = new ArrayList<>();
                for (Node n : previewHost.lookupAll(".md-zoom-button")) {
                    zoom.add((Button) n);
                }
                assertEquals(3, zoom.size());
                for (Button b : zoom) {
                    assertTrue(
                            b.getAccessibleText() != null
                                    && b.getAccessibleText().length() > 3,
                            "'" + b.getText() + "' has no accessible name");
                    assertEquals(b.getAccessibleText(), b.getTooltip().getText());
                }
            } finally {
                stage.hide();
            }
        });
    }
}
