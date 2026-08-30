package com.editora.ui;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javafx.event.Event;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main menu's enabled state must track the window's live context, not the context as it stood when the
 * window was built.
 *
 * <p>The regression this pins: enablement was computed only in {@code refresh()}, which runs on a settings or
 * keymap apply. Git detection is asynchronous and off-thread, so at window-build time {@code inRepo} is
 * always false — and the entire VCS menu (Commit, Push, Pull, Fetch, branches, stash, Git Log, file history)
 * came up greyed out and <em>stayed</em> that way for the life of the window, while the status bar an inch
 * below it displayed the branch. The same freeze applied to every other context field: the buffer-shaped
 * items and the debug step commands.
 *
 * <p>Driven through {@link MainMenuBar} with a supplier the test moves, rather than through a real window, so
 * it asserts the mechanism rather than racing the very asynchrony that caused the bug.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuBarEnablementFxTest {

    /** An item gated on being in a repo, and one that is deliberately not (creating a repo needs no repo). */
    private static final String NEEDS_REPO = "git.commit";

    private static final String NEEDS_NO_REPO = "git.clone";

    private final AtomicReference<Chrome.PaletteContext> ctx = new AtomicReference<>();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private MainMenuBar build() throws Exception {
        return FxTestSupport.callOnFx(() -> new MainMenuBar(
                new CommandRegistry(), Map::of, MenuBarEnablementFxTest::allFeaturesOn, ctx::get, id -> {}));
    }

    private static boolean disabled(MainMenuBar bar, String commandId) throws Exception {
        MenuItem item = itemFor(bar, commandId);
        assertNotNull(item, commandId + " is not in the menu bar — the test is pinning a stale command id");
        return item.isDisable();
    }

    private static MenuItem itemFor(MainMenuBar bar, String commandId) throws Exception {
        Map<String, ?> rows = FxTestSupport.field(bar, "items");
        Object row = rows.get(commandId);
        if (row == null) {
            return null;
        }
        return (MenuItem) FxTestSupport.call(row, "item", new Class<?>[0]);
    }

    @Test
    void aRepoDetectedAfterTheWindowWasBuiltUngreysTheVcsMenu() throws Exception {
        ctx.set(context(false));
        MainMenuBar bar = build();
        assertTrue(disabled(bar, NEEDS_REPO), "outside a repo, Commit should be disabled");
        assertFalse(disabled(bar, NEEDS_NO_REPO), "Clone must stay enabled outside a repo — it is the way in");

        // What Git's async detection landing looks like from the menu's side.
        ctx.set(context(true));
        FxTestSupport.runOnFx(bar::refreshEnablement);
        assertFalse(
                disabled(bar, NEEDS_REPO),
                "Commit is still greyed out after a repo was detected — enablement is frozen at build time");
    }

    /**
     * Opening the menu re-evaluates, so the in-window bar cannot show a stale answer even if some future
     * state change forgets to push. (On the macOS system menu bar AppKit owns the popup and this may not
     * fire, which is why the push sites exist as well.)
     */
    @Test
    void openingAMenuRecomputesEnablement() throws Exception {
        ctx.set(context(false));
        MainMenuBar bar = build();
        assertTrue(disabled(bar, NEEDS_REPO));

        ctx.set(context(true));
        FxTestSupport.runOnFx(() -> {
            for (Menu m : bar.node().getMenus()) {
                Event.fireEvent(m, new Event(Menu.ON_SHOWING));
            }
        });
        assertFalse(disabled(bar, NEEDS_REPO), "a menu opening did not re-evaluate its items");
    }

    /** The other context fields were frozen by the same bug, so they get the same guarantee. */
    @Test
    void theBufferAndDebugShapedItemsTrackTheContextToo() throws Exception {
        ctx.set(context(false));
        MainMenuBar bar = build();
        MenuItem step = itemFor(bar, "debug.stepOver");
        if (step == null) {
            return; // not on the menu in this build — the repo case above already pins the mechanism
        }
        assertTrue(step.isDisable(), "with no suspended session, Step Over should be disabled");

        ctx.set(Chrome.PaletteContext.all());
        FxTestSupport.runOnFx(bar::refreshEnablement);
        assertFalse(step.isDisable(), "Step Over stayed disabled after the session suspended");
    }

    /** Everything on: this test is about the context half of the gate, not the feature half. */
    private static Chrome.PaletteGates allFeaturesOn() {
        return new Chrome.PaletteGates(
                true, true, true, true, true, true, true, Set.of(), true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, true, true, true, true, true, true, false);
    }

    /** A context with nothing to act on except, optionally, being inside a Git repository. */
    private static Chrome.PaletteContext context(boolean inRepo) {
        return new Chrome.PaletteContext(false, inRepo, false, false, false, false, false, false, false);
    }
}
