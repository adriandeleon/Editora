package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Project;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Welcome page's layout and keyboard rules: a long name never elides before its folder, the column never
 * grows past its cap, zoom scales the layout as well as the text, the recent entries are numbered, and saved
 * projects are listed and open.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WelcomeLayoutFxTest {

    private static final String LONG_NAME = "OrderFulfillmentServiceImplementation.java";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aLongFileNameKeepsItsWidthAndTheFolderElidesInstead(@TempDir Path dir) throws Exception {
        Path deep = Files.createDirectories(
                dir.resolve("clients/acme-corporation/backend-services/order-management/src/main/java/com/acme"));
        RecentFiles recents = new RecentFiles(dir);
        recents.add(Files.createFile(deep.resolve(LONG_NAME)));

        FxTestSupport.runOnFx(() -> {
            WelcomePane pane = pane(recents, new CommandRegistry(), List::of);
            layout(pane, 1280, 800, 1.0);

            Hyperlink name = link(pane, LONG_NAME);
            assertTrue(
                    name.getWidth() >= name.prefWidth(-1) - 0.5,
                    "the name is laid out at its full width: " + name.getWidth() + " < " + name.prefWidth(-1));
            Node content = pane.lookup(".welcome-content");
            assertTrue(
                    content.getLayoutBounds().getWidth() <= WelcomePane.COLUMN_WIDTH + 0.5,
                    "a long folder does not widen the column: "
                            + content.getLayoutBounds().getWidth());
        });
    }

    @Test
    void zoomScalesTheColumnSoNothingClips(@TempDir Path dir) throws Exception {
        RecentFiles recents = new RecentFiles(dir);
        recents.add(Files.createFile(dir.resolve("notes.txt")));

        FxTestSupport.runOnFx(() -> {
            WelcomePane pane = pane(recents, new CommandRegistry(), List::of);
            layout(pane, 1280, 800, 1.5);

            for (Node n : pane.lookupAll(".welcome-action")) {
                Hyperlink action = (Hyperlink) n;
                assertTrue(
                        action.getWidth() >= action.prefWidth(-1) - 0.5,
                        "\"" + action.getText() + "\" is not clipped at 150 %");
            }
            for (Node n : pane.lookupAll(".welcome-key")) {
                Label key = (Label) n;
                assertTrue(key.getWidth() >= key.prefWidth(-1) - 0.5, key.getText() + " is not clipped at 150 %");
            }
            assertTrue(
                    pane.lookup(".welcome-content").getLayoutBounds().getWidth() > WelcomePane.COLUMN_WIDTH,
                    "the column cap grows with the zoom");
        });
    }

    @Test
    void digitsOpenTheNumberedEntriesInPageOrder(@TempDir Path dir) throws Exception {
        RecentFiles recents = new RecentFiles(dir);
        for (int i = 12; i >= 1; i--) {
            recents.add(Files.createFile(dir.resolve("f" + i + ".txt")));
        }
        List<Path> opened = new ArrayList<>();

        WelcomePane pane = FxTestSupport.callOnFx(() -> {
            WelcomePane p = new WelcomePane(
                    new CommandRegistry(),
                    new KeymapManager(),
                    recents,
                    List::of,
                    opened::add,
                    url -> {},
                    () -> true,
                    () -> true,
                    List::of,
                    c -> {},
                    "");
            layout(p, 1280, 800, 1.0);
            assertEquals(WelcomePane.NUMBERED_ENTRIES, p.numberedEntries().size(), "the first nine are numbered");
            p.requestFocus();
            return p;
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> assertEquals(
                "f1.txt", ((Hyperlink) pane.getScene().getFocusOwner()).getText(), "focus starts on entry 1"));

        // A rebuild (settings change, keymap switch) keeps focus on the page, not the toolbar.
        FxTestSupport.runOnFx(() -> {
            pane.refresh();
            pane.applyCss();
            pane.layout();
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertTrue(
                    pane.ownsFocus(),
                    "focus survives refresh: " + pane.getScene().getFocusOwner());

            KeyEvent three = new KeyEvent(KeyEvent.KEY_TYPED, "3", "3", KeyCode.UNDEFINED, false, false, false, false);
            javafx.event.Event.fireEvent(pane.getScene().getFocusOwner(), three);
            assertEquals(List.of(dir.resolve("f3.txt")), opened);

            // A chord with a modifier is the keymap's, not an entry number.
            KeyEvent ctrl = new KeyEvent(KeyEvent.KEY_TYPED, "4", "4", KeyCode.UNDEFINED, false, true, false, false);
            javafx.event.Event.fireEvent(pane.getScene().getFocusOwner(), ctrl);
            assertEquals(1, opened.size());
        });
    }

    @Test
    void savedProjectsAreListedAndOpenTheirWindow(@TempDir Path dir) throws Exception {
        Path site = Files.createDirectories(dir.resolve("site"));
        Path notes = Files.createDirectories(dir.resolve("notes"));
        Project siteProject = new Project("site-id", "site", site.toString());
        Project notesProject = new Project("notes-id", "notes", notes.toString());
        Project gone = new Project("gone-id", "gone", dir.resolve("deleted").toString());
        RecentFiles recents = new RecentFiles(dir);
        recents.add(Files.createFile(site.resolve("index.html")));
        recents.add(Files.createFile(notes.resolve("todo.md"))); // newest
        List<Project> openedProjects = new ArrayList<>();

        FxTestSupport.runOnFx(() -> {
            WelcomePane pane = pane(recents, new CommandRegistry(), () -> List.of(siteProject, notesProject, gone));
            pane.setProjectOpener(openedProjects::add);
            pane.refresh();
            layout(pane, 1280, 800, 1.0);

            List<String> numbered =
                    pane.numberedEntries().stream().map(Hyperlink::getText).toList();
            // notes has the newest file, so it leads; a project whose folder is gone is not offered.
            assertEquals(List.of("notes", "site", "todo.md", "index.html"), numbered);
            link(pane, "notes").fire();
            assertEquals(List.of(notesProject), openedProjects);
        });
    }

    @Test
    void openFileShowsItsOwnChordAndCustomizeNamesTheKeymap(@TempDir Path dir) throws Exception {
        CommandRegistry registry = new CommandRegistry();
        List<String> ran = new ArrayList<>();
        registry.register(Command.of("keymap.select", () -> ran.add("keymap.select")));
        KeymapManager vscode = new KeymapManager();
        vscode.loadNamed("vscode");

        FxTestSupport.runOnFx(() -> {
            WelcomePane pane = new WelcomePane(
                    registry,
                    vscode,
                    new RecentFiles(dir),
                    List::of,
                    p -> {},
                    url -> {},
                    () -> true,
                    () -> true,
                    List::of,
                    c -> {},
                    "");
            pane.setCustomizeSource(() -> "VS Code", () -> "Editora Light");
            pane.refresh();
            layout(pane, 1280, 800, 1.0);

            // Open File runs the Open dialog, so it shows file.open's Ctrl+O, not Quick Open's Ctrl+P.
            Hyperlink open = link(pane, com.editora.i18n.Messages.tr("welcome.openFile"));
            assertEquals(vscode.displayChord("file.open"), open.getAccessibleHelp());
            // A keymap other than Emacs needs no notation hint.
            assertNull(textOf(pane, com.editora.i18n.Messages.tr("welcome.notation.emacs")));
            link(pane, "VS Code").fire();
            assertEquals(List.of("keymap.select"), ran);
        });
    }

    private static WelcomePane pane(
            RecentFiles recents, CommandRegistry registry, java.util.function.Supplier<List<Project>> projects) {
        KeymapManager emacs = new KeymapManager();
        emacs.loadNamed("emacs");
        return new WelcomePane(
                registry, emacs, recents, projects, p -> {}, url -> {}, () -> true, () -> true, List::of, c -> {}, "");
    }

    private static void layout(WelcomePane pane, double w, double h, double zoom) {
        Scene scene = new Scene(pane, w, h);
        scene.getStylesheets()
                .add(WelcomePane.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        pane.setFontScale(zoom);
        pane.applyCss();
        pane.layout();
        pane.applyCss();
        pane.layout();
    }

    private static Hyperlink link(WelcomePane pane, String text) {
        for (Node n : pane.lookupAll(".hyperlink")) {
            if (n instanceof Hyperlink h && text.equals(h.getText())) {
                return h;
            }
        }
        throw new AssertionError("no link \"" + text + "\"");
    }

    private static Label textOf(WelcomePane pane, String text) {
        for (Node n : pane.lookupAll(".label")) {
            if (n instanceof Label l && text.equals(l.getText())) {
                return l;
            }
        }
        return null;
    }
}
