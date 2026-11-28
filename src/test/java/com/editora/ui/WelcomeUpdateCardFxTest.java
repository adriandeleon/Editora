package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.Labeled;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-run update question (HN dry run, 2026-11-27): the check used to run on the first launch, before it could
 * be turned off. While it is unanswered the Welcome page asks, Check Daily or Don't Check, and an answer hides it.
 */
@Tag("fx")
class WelcomeUpdateCardFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static WelcomePane pane(Path dir) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed("emacs");
            return new WelcomePane(
                    new CommandRegistry(),
                    keymap,
                    new RecentFiles(dir),
                    List::of,
                    p -> {},
                    url -> {},
                    () -> true,
                    () -> true,
                    List::of,
                    c -> {},
                    "");
        });
    }

    private static void show(WelcomePane pane) {
        if (pane.getScene() == null) {
            new javafx.scene.Scene(pane, 1200, 900);
        }
        pane.refresh();
        pane.applyCss();
        pane.layout();
    }

    /** The button worded {@code label} on the page, or null. */
    private static Button button(WelcomePane pane, String label) {
        return pane.lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static boolean asks(WelcomePane pane) {
        return pane.lookupAll(".label").stream()
                .anyMatch(n ->
                        n instanceof Labeled l && tr("welcome.updateCard.title").equals(l.getText()));
    }

    @Test
    void anUnansweredInstallIsAskedAndAnAnswerHidesTheCard(@TempDir Path dir) throws Exception {
        for (boolean yes : new boolean[] {true, false}) {
            WelcomePane pane = pane(dir);
            boolean[] asked = {false};
            List<Boolean> answers = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                pane.setUpdateCheckChoice(() -> !asked[0], on -> {
                    answers.add(on);
                    asked[0] = true;
                });
                show(pane);
            });
            assertTrue(FxTestSupport.callOnFx(() -> asks(pane)), "the question is on the page");
            String label = tr(yes ? "welcome.updateCard.check" : "welcome.updateCard.skip");
            FxTestSupport.runOnFx(() -> {
                button(pane, label).fire();
                show(pane);
            });
            assertEquals(List.of(yes), answers);
            assertFalse(FxTestSupport.callOnFx(() -> asks(pane)), "answered: the card is gone");
        }
    }

    @Test
    void anAnsweredInstallIsNotAsked(@TempDir Path dir) throws Exception {
        WelcomePane pane = pane(dir);
        FxTestSupport.runOnFx(() -> {
            pane.setUpdateCheckChoice(() -> false, on -> {});
            show(pane);
        });
        assertFalse(FxTestSupport.callOnFx(() -> asks(pane)));
    }
}
