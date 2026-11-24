package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sessions strip keeps its buttons when a step changes only a tab's state words (perf review D14). */
@Tag("fx")
class DebugSessionsViewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final DebugSessionsView.Actions NONE = new DebugSessionsView.Actions() {
        @Override
        public void show(DebugSession session) {}

        @Override
        public void close(DebugSession session) {}

        @Override
        public void stopAll() {}
    };

    private static List<ToggleButton> buttons(DebugSessionsView view) {
        HBox strip = (HBox) view.getTop();
        return strip.getChildren().stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .toList();
    }

    @Test
    void aStepSetsTheTabsWordsWithoutBuildingTheStripAgain() throws Exception {
        DebugSession server = new DebugSession(new DapManager(null));
        DebugSession client = new DebugSession(new DapManager(null));
        String running = tr("debug.state.running");
        String suspended = tr("debug.state.suspended");
        List<DebugSessionsView.Tab> before = List.of(
                new DebugSessionsView.Tab(server, "server.rb", running, true, true),
                new DebugSessionsView.Tab(client, "client.rb", running, true, false));
        List<DebugSessionsView.Tab> stopped = List.of(
                new DebugSessionsView.Tab(server, "server.rb", suspended, true, true),
                new DebugSessionsView.Tab(client, "client.rb", running, true, false));
        assertTrue(DebugSessionsView.onlyStatesDiffer(before, stopped));
        assertFalse(DebugSessionsView.onlyStatesDiffer(before, stopped.subList(0, 1)), "a session gone");
        assertFalse(
                DebugSessionsView.onlyStatesDiffer(
                        before,
                        List.of(
                                new DebugSessionsView.Tab(server, "server.rb", running, true, false),
                                new DebugSessionsView.Tab(client, "client.rb", running, true, true))),
                "the other one shown");
        assertFalse(
                DebugSessionsView.onlyStatesDiffer(
                        before,
                        List.of(
                                new DebugSessionsView.Tab(server, "server.rb", running, true, true),
                                new DebugSessionsView.Tab(client, "client.rb", "Ended", false, false))),
                "one ended: its × comes");

        FxTestSupport.runOnFx(() -> {
            DebugSessionsView view = new DebugSessionsView(NONE);
            view.setTabs(before);
            List<ToggleButton> first = buttons(view);
            assertEquals(2, first.size());
            List<Node> children = List.copyOf(((HBox) view.getTop()).getChildren());

            view.setTabs(stopped);
            assertEquals(children, ((HBox) view.getTop()).getChildren(), "the same buttons");
            assertSame(first.get(0), buttons(view).get(0));
            assertEquals(
                    tr("debug.sessions.tab", "server.rb", suspended),
                    first.get(0).getText());
            assertEquals(
                    tr("debug.sessions.tab", "client.rb", running), first.get(1).getText());
            assertEquals(stopped, view.tabs());

            view.setTabs(List.of(
                    new DebugSessionsView.Tab(server, "server.rb", suspended, true, false),
                    new DebugSessionsView.Tab(client, "client.rb", running, true, true)));
            assertNotSame(first.get(1), buttons(view).get(1), "another one shown: drawn again");
            assertTrue(buttons(view).get(1).isSelected());
        });
    }
}
