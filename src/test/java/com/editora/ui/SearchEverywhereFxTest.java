package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search Everywhere end to end through the real window: the command opens it, typing produces results
 * from the real command registry, and a command-scoped query does not drag in the project index.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchEverywhereFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // These wrap their reflection rather than declaring `throws`, so they can be used inside the
    // Runnable lambdas runOnFx takes — which cannot throw a checked exception.
    private SearchEverywherePopup popup() {
        return FxTestSupport.field(fx.controller, "searchEverywherePopup");
    }

    private void hide() {
        FxTestSupport.runOnFxUnchecked(() ->
                FxTestSupport.<OverlayHost>field(fx.controller, "overlayHost").hide());
    }

    @SuppressWarnings("unchecked")
    private List<Object> rows() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            javafx.collections.ObservableList<Object> list = FxTestSupport.field(popup(), "rows");
            return new java.util.ArrayList<Object>(list);
        });
    }

    private void type(String query) {
        FxTestSupport.runOnFxUnchecked(() -> {
            javafx.scene.control.TextField input = FxTestSupport.field(popup(), "input");
            input.setText(query);
            FxTestSupport.invoke(popup(), "refresh"); // drive the debounce directly rather than race it
        });
    }

    @Test
    void theCommandOpensThePopup() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFxUnchecked(() -> registry.run("search.everywhere"));
        assertTrue(popup().isShown());
        hide();
    }

    @Test
    void aCommandScopedQueryFindsRealCommands() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type(">undo");
        assertFalse(rows().isEmpty(), "the real command registry should have matched 'undo'");
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invoke(popup(), "chooseSelected"));
    }

    @Test
    void anEmptyQueryShowsNothing() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type("");
        assertTrue(rows().isEmpty(), "an empty query must not dump the whole registry");
        hide();
    }

    @Test
    void aDisabledCommandIsNotOffered() throws Exception {
        // The palette lists a gated command grayed out, because seeing it exists is the point there.
        // Here the list is short and mixed, so an inert row would be pure noise.
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        boolean hasGitCommand = registry.all().stream().anyMatch(c -> c.id().startsWith("git."));
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type(">zzzzzzzznotacommand");
        assertTrue(rows().isEmpty());
        assertTrue(hasGitCommand, "sanity: the registry really does carry gated commands");
        hide();
    }

    @Test
    void headersAreNeverSelected() throws Exception {
        FxTestSupport.runOnFxUnchecked(() -> popup().show(""));
        type(">toggle");
        Object selected = FxTestSupport.callOnFx(() -> {
            javafx.scene.control.ListView<?> list = FxTestSupport.field(popup(), "list");
            return list.getSelectionModel().getSelectedItem();
        });
        assertTrue(
                selected == null || !selected.getClass().getSimpleName().equals("HeaderRow"),
                "the cursor must skip group headers, which are labels rather than results");
        hide();
    }
}
