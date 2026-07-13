package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.build.BuildAction;
import com.editora.build.BuildActionsProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FX-harness tests for {@link BuildActionsTree}: the IntelliJ-style tasks tree renders a provider's sections
 * as tree rows, running a selected {@link BuildAction.Task} fires the run callback with the task's args plus
 * the active toggles' extra argv, and flipping a {@link BuildAction.Toggle} re-queries the provider (so a
 * checked toggle can reveal extra rows and contribute {@code toggleArgs}). A {@code null} provider clears the
 * tree to a placeholder.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildActionsTreeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A tiny provider: a "Lifecycle" section with one task + one toggle; the toggle reveals an extra task
     *  and contributes {@code -Pdist} (mirrors Maven's profile behavior). */
    private static final class FakeProvider implements BuildActionsProvider {
        @Override
        public List<BuildAction.Section> sections(Set<String> active) {
            List<BuildAction.Row> rows = new ArrayList<>();
            rows.add(new BuildAction.Task("package", List.of("package")));
            rows.add(new BuildAction.Toggle("dist", "dist"));
            if (active.contains("dist")) {
                rows.add(new BuildAction.Task("dist:deploy", List.of("deploy")));
            }
            return List.of(new BuildAction.Section("Lifecycle", rows));
        }

        @Override
        public List<String> toggleArgs(Set<String> active) {
            return active.contains("dist") ? List.of("-Pdist") : List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> treeOf(BuildActionsTree panel) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    @Test
    void rendersSectionsAndRunsSelectedTaskWithToggleArgs() throws Exception {
        AtomicReference<List<String>> ranTask = new AtomicReference<>();
        AtomicReference<List<String>> ranToggle = new AtomicReference<>();

        BuildActionsTree panel = FxTestSupport.callOnFx(() -> {
            BuildActionsTree p = new BuildActionsTree();
            p.setOnRun((taskArgs, toggleArgs) -> {
                ranTask.set(taskArgs);
                ranToggle.set(toggleArgs);
            });
            p.setProvider(new FakeProvider());
            return p;
        });

        // One "Lifecycle" section with two rows (a task + a toggle).
        int[] counts = FxTestSupport.callOnFx(() -> {
            TreeView<Object> tree = treeOf(panel);
            TreeItem<Object> section = tree.getRoot().getChildren().get(0);
            return new int[] {
                tree.getRoot().getChildren().size(), section.getChildren().size()
            };
        });
        assertEquals(1, counts[0], "one section");
        assertEquals(2, counts[1], "task + toggle rows");

        // Selecting the task row and running it fires the callback with the task args + (empty) toggle args.
        FxTestSupport.runOnFx(() -> {
            TreeView<Object> tree = treeOf(panel);
            TreeItem<Object> task =
                    tree.getRoot().getChildren().get(0).getChildren().get(0);
            tree.getSelectionModel().select(task);
        });
        FxTestSupport.invoke(panel, "runSelected");
        assertEquals(List.of("package"), ranTask.get());
        assertEquals(List.of(), ranToggle.get(), "no toggle active yet");

        // Activating the "dist" toggle re-queries the provider: a third row appears and toggleArgs kicks in.
        FxTestSupport.call(
                panel,
                "toggle",
                new Class<?>[] {BuildAction.Toggle.class, boolean.class},
                new BuildAction.Toggle("dist", "dist"),
                true);
        int revealed = FxTestSupport.callOnFx(
                () -> treeOf(panel).getRoot().getChildren().get(0).getChildren().size());
        assertEquals(3, revealed, "checking the toggle revealed the extra task");

        FxTestSupport.runOnFx(() -> {
            TreeView<Object> tree = treeOf(panel);
            TreeItem<Object> task =
                    tree.getRoot().getChildren().get(0).getChildren().get(0);
            tree.getSelectionModel().select(task);
        });
        FxTestSupport.invoke(panel, "runSelected");
        assertEquals(List.of("-Pdist"), ranToggle.get(), "the active toggle now contributes its argv");
    }

    @Test
    void nullProviderClearsToAPlaceholder() throws Exception {
        Object root = FxTestSupport.callOnFx(() -> {
            BuildActionsTree p = new BuildActionsTree();
            p.setProvider(new FakeProvider());
            p.setProvider(null);
            return treeOf(p).getRoot();
        });
        assertNull(root, "a null provider empties the tree");
    }
}
