package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-run keymap card (r/JavaFX dry run, 2026-11-27): the default keymap is Emacs, and a newcomer from
 * IntelliJ or VS Code met {@code C-x C-f} on the Welcome page. While no keymap has been chosen the page asks which
 * keys the user knows, a button per keymap, and a choice or Keep hides the card.
 */
@Tag("fx")
class WelcomeKeymapCardFxTest {

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

    /** Rebuilds {@code pane} in a scene: the page sits in a ScrollPane, whose content joins the tree with its skin. */
    private static void show(WelcomePane pane) {
        if (pane.getScene() == null) {
            new javafx.scene.Scene(pane, 1200, 900);
        }
        pane.refresh();
        pane.applyCss();
        pane.layout();
    }

    @Test
    void aFreshInstallIsAskedForItsKeymapAndAChoiceHidesTheCard(@TempDir Path dir) throws Exception {
        WelcomePane pane = pane(dir);
        boolean[] chosen = {false};
        List<String> picked = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            pane.setKeymapChoice(
                    () -> !chosen[0],
                    id -> {
                        picked.add(id);
                        chosen[0] = true;
                    },
                    () -> chosen[0] = true);
            show(pane);
        });
        List<String> ids = FxTestSupport.callOnFx(() -> pane.lookupAll(".welcome-keymap-choice").stream()
                .map(n -> (String) n.getUserData())
                .toList());
        assertEquals(List.copyOf(KeymapManager.AVAILABLE.keySet()), ids, "one button per keymap, in their order");
        FxTestSupport.runOnFx(() -> {
            Node card = pane.lookup(".welcome-keymap-card");
            assertTrue(card != null
                    && card.lookupAll(".label").stream()
                            .anyMatch(n ->
                                    tr("welcome.keymapCard.title").equals(((javafx.scene.control.Label) n).getText())));
            pane.lookupAll(".welcome-keymap-choice").stream()
                    .map(Button.class::cast)
                    .filter(b -> "vscode".equals(b.getUserData()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            pane.applyCss();
            pane.layout();
        });
        assertEquals(List.of("vscode"), picked);
        assertNull(FxTestSupport.callOnFx(() -> pane.lookup(".welcome-keymap-card")), "asked once");
    }

    @Test
    void keepingTheActiveKeymapHidesTheCardToo(@TempDir Path dir) throws Exception {
        WelcomePane pane = pane(dir);
        boolean[] chosen = {false};
        FxTestSupport.runOnFx(() -> {
            pane.setKeymapChoice(() -> !chosen[0], id -> chosen[0] = true, () -> chosen[0] = true);
            show(pane);
            Hyperlink keep = pane.lookup(".welcome-keymap-card").lookupAll(".hyperlink").stream()
                    .map(Hyperlink.class::cast)
                    .filter(h -> h.getText().equals(tr("welcome.keymapCard.keep", "Emacs")))
                    .findFirst()
                    .orElseThrow();
            keep.fire();
            pane.applyCss();
            pane.layout();
        });
        assertTrue(chosen[0]);
        assertNull(FxTestSupport.callOnFx(() -> pane.lookup(".welcome-keymap-card")));
    }

    @Test
    void anInstallThatChoseOrAPageNotWiredHasNoCard(@TempDir Path dir) throws Exception {
        WelcomePane unwired = pane(dir);
        FxTestSupport.runOnFx(() -> show(unwired));
        assertNull(FxTestSupport.callOnFx(() -> unwired.lookup(".welcome-keymap-card")));
        WelcomePane chose = pane(dir);
        FxTestSupport.runOnFx(() -> {
            chose.setKeymapChoice(() -> false, id -> {}, () -> {});
            show(chose);
        });
        assertNull(FxTestSupport.callOnFx(() -> chose.lookup(".welcome-keymap-card")));
    }
}
