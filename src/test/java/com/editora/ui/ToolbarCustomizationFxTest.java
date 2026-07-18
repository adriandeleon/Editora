package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the customizable toolbar: proves {@link ToolbarCoordinator} rebuilds the real
 * {@code toolBar}'s customizable cluster from {@code Settings.toolbarLayout} (default, a custom layout, and
 * restore-default), complementing the pure {@code ToolbarLayoutTest}/{@code ToolbarCatalogTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolbarCustomizationFxTest {

    private FxWindowFixture fx;
    private Settings settings;
    private ToolbarCoordinator coord;
    private Button saveButton;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        settings = config.getSettings();
        coord = FxTestSupport.field(fx.controller, "toolbarCoordinator");
        saveButton = FxTestSupport.field(fx.controller, "saveButton");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private List<Node> rebuildWith(List<String> layout) throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setToolbarLayout(layout);
            coord.rebuild();
        });
        return FxTestSupport.field(coord, "customNodes");
    }

    @Test
    void defaultLayoutBuildsEveryCatalogDefaultItem() throws Exception {
        List<Node> nodes = rebuildWith(List.of()); // empty ⇒ shipped default
        int size = FxTestSupport.callOnFx(nodes::size);
        assertEquals(ToolbarCatalog.defaultLayout().size(), size, "default toolbar size matches the default layout");
        assertTrue(FxTestSupport.callOnFx(() -> nodes.contains(saveButton)), "default toolbar reuses the Save button");
    }

    @Test
    void customLayoutReordersAndAddsAnExtraButton() throws Exception {
        // A default item (reuses the @FXML Save button) followed by an extra (a fresh Git Commit button).
        List<Node> nodes = rebuildWith(List.of("file.save", "git.commit"));
        assertEquals(2, FxTestSupport.callOnFx(nodes::size), "custom layout yields exactly its two items");
        assertSame(saveButton, FxTestSupport.callOnFx(() -> nodes.get(0)), "base widget reused for a default item");
        assertInstanceOf(Button.class, FxTestSupport.callOnFx(() -> nodes.get(1)), "extra item is a fresh button");
    }

    @Test
    void restoreDefaultClearsTheSettingAndRebuildsTheDefault() throws Exception {
        rebuildWith(List.of("file.save")); // customize first
        FxTestSupport.runOnFx(() -> coord.restoreDefault());
        assertTrue(settings.getToolbarLayout().isEmpty(), "restore clears the saved layout (empty ⇒ default)");
        List<Node> nodes = rebuildWith(List.of());
        assertEquals(
                ToolbarCatalog.defaultLayout().size(),
                FxTestSupport.callOnFx(nodes::size),
                "toolbar is back to the default arrangement");
    }
}
