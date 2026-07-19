package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool window's stripe button gets the {@code tw-active} highlight (IntelliJ-style, stronger than the
 * merely-open tint) exactly while its panel holds keyboard focus, and loses it when focus moves elsewhere.
 * This is what makes the Debug icon read as "selected" while the Debug panel is active.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowActiveHighlightFxTest {

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("tw-active");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowManager manager, ToolWindow tw, Node content) {}

    private static Rig rig() throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-active");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        ToolWindowManager m = new ToolWindowManager(new BorderPane(), new Label("editor"), config, new KeymapManager());
        Region content = new Label("content");
        ToolWindow tw =
                new ToolWindow("probe", "Probe", ToolWindow.Side.RIGHT, () -> new Label("i"), content, "tool.probe");
        m.register(tw);
        m.open(tw);
        return new Rig(m, tw, content);
    }

    private static Button stripeButton(ToolWindowManager m, ToolWindow tw) {
        Map<ToolWindow, Button> buttons = FxTestSupport.field(m, "stripeButtons");
        return buttons.get(tw);
    }

    @Test
    void theButtonIsActiveOnlyWhileFocusIsWithinItsPanel() throws Exception {
        boolean[] r = FxTestSupport.callOnFx(() -> {
            Rig rig = rig();
            Button button = stripeButton(rig.manager(), rig.tw());
            boolean beforeFocus = button.getPseudoClassStates().contains(ACTIVE);
            // Focus lands inside the open panel (the content node is a descendant of the ToolWindowPanel).
            FxTestSupport.call(rig.manager(), "updateActivePanel", new Class[] {Node.class}, rig.content());
            boolean whileFocused = button.getPseudoClassStates().contains(ACTIVE);
            // Focus leaves every tool window (e.g. back to the editor).
            FxTestSupport.call(rig.manager(), "updateActivePanel", new Class[] {Node.class}, (Node) null);
            boolean afterBlur = button.getPseudoClassStates().contains(ACTIVE);
            return new boolean[] {beforeFocus, whileFocused, afterBlur};
        });
        assertFalse(r[0], "merely opening a window does not make its button active");
        assertTrue(r[1], "the button is active while focus is within its panel");
        assertFalse(r[2], "the button clears its active state once focus leaves");
    }
}
