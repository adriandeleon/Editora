package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map's {@code @name} search end to end (Project Map explore, position 19): the real {@link IndexCoordinator}
 * on a small project, shared with the panel as the editor window shares it ({@link ProjectPanel#shareIndexes}), is
 * built by the first query and lands the search on the declaration's row in its file's Structure column. The
 * review-3 test used a fake source.
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class ProjectMapSymbolIndexFxTest {

    private static final String APP = String.join(
            "\n",
            "package demo;",
            "",
            "public class App {",
            "    int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "}",
            "");

    @TempDir
    Path temp;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    /** Position 19: the real symbol index, built on the first {@code @name} query, lands on the declaration. */
    @Test
    void anAtNameQueryLandsOnItsDeclarationThroughTheRealSymbolIndex() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path app = Files.writeString(src.resolve("App.java"), APP);
        Files.writeString(
                src.resolve("Caller.java"), "package demo;\n\nclass Caller {\n    int addAll() { return 0; }\n}\n");
        Files.writeString(root.resolve("README.md"), "add things\n");
        com.editora.config.Settings settings = new com.editora.config.Settings();
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public com.editora.config.Settings settings() {
                return settings;
            }

            @Override
            public void setStatus(String message) {}
        };
        IndexCoordinator index = FxTestSupport.callOnFx(() -> new IndexCoordinator(host, new IndexCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return root;
            }

            @Override
            public void openAndGoto(Path file, int line, int column) {}

            @Override
            public boolean respectGitignore() {
                return false;
            }
        }));
        try {
            ProjectPanel panel = openPanel();
            FxTestSupport.runOnFx(() -> panel.shareIndexes(index, null));
            ProjectMapView view = FxTestSupport.field(panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");

            search(panel, "@add");
            waitFor(() -> selectedRowNamed(surface, app, "add"), "the declaration row of App.add is selected");
            FxTestSupport.runOnFx(() -> {
                assertTrue(index.isBuilt(), "the first @ query built the index");
                ProjectMapSearchResult.Match first = view.searchResult().first();
                assertNotNull(first);
                assertEquals(ProjectMapSearchResult.Kind.SYMBOL, first.kind());
                assertEquals(app, first.path());
                assertEquals(3, first.line(), "the index's line of the declaration");
            });
        } finally {
            FxTestSupport.runOnFx(index::dispose);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p =
                    DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !rows(surface).isEmpty(), "the first column");
        return panel;
    }

    private static void search(ProjectPanel panel, String query) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText(query);
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    private static boolean selectedRowNamed(Region surface, Path file, String name) {
        Path selected = FxTestSupport.field(surface, "selected");
        return selected != null
                && selected.startsWith(file)
                && rows(surface).stream()
                        .anyMatch(row -> row.isSymbol()
                                && row.path().equals(selected)
                                && ProjectMapView.searchLabelNames(row.name(), name));
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out: " + message);
    }
}
