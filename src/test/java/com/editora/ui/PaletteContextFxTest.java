package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.editora.command.Command;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard for the palette's enabled predicate as it is actually wired in {@link MainController}
 * (the unit tests in {@code ChromeTest} cover the pure decision; this covers the wiring, which is where the
 * feature was silently under-reaching before).
 *
 * <p>The important half is the <b>false-negative</b> direction: graying a command that would in fact have
 * worked is a worse regression than leaving one lit, so each case asserts both that a command is grayed
 * when it can't act and that it lights up once its context arrives.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaletteContextFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The palette's live enabled-predicate verdict for each of {@code ids}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> verdicts(MainController controller, String... ids) throws Exception {
        CommandPalette palette = FxTestSupport.field(controller, "palette");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(palette, "filter", new Class<?>[] {String.class}, ""));
        List<Command> items = FxTestSupport.field(palette, "items");
        // The snapshot the pass above took — the same one the cells render against.
        Predicate<Command> enabled = FxTestSupport.field(palette, "enabledSnapshot");
        // The predicate reads live window state (active buffer, repo root, debug session), so evaluate the
        // whole batch on the FX thread in one hop.
        return FxTestSupport.callOnFx(() -> {
            Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            for (String id : ids) {
                items.stream().filter(c -> c.id().equals(id)).findFirst().ifPresent(c -> out.put(id, enabled.test(c)));
            }
            return out;
        });
    }

    @Test
    void commandsWithNothingToActOnAreGrayedOnTheWelcomeTab() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        Map<String, Boolean> v = verdicts(
                fx.controller,
                "edit.cut",
                "nav.lineUp",
                "markdown.bold",
                "csv.align",
                "preview.exportPdf",
                "file.new",
                "file.open",
                "palette.show",
                "window.new",
                "help.about",
                "app.quit",
                "git.clone");

        // No editor buffer -> the buffer-scoped families can't act.
        assertFalse(v.get("edit.cut"), "edit.* needs a buffer");
        assertFalse(v.get("nav.lineUp"), "nav.* needs a buffer");
        assertFalse(v.get("markdown.bold"), "markdown.* needs a Markdown/Typst buffer");
        assertFalse(v.get("csv.align"), "csv.* needs a CSV buffer");
        assertFalse(v.get("preview.exportPdf"), "preview.* needs a buffer with a preview");

        // ...but everything that legitimately works with no buffer stays actionable. This is the direction
        // that must never regress: a grayed-out File > Open would make the palette useless on an empty window.
        for (String id : new String[] {
            "file.new", "file.open", "palette.show", "window.new", "help.about", "app.quit", "git.clone"
        }) {
            assertTrue(v.get(id), id + " must stay actionable with no buffer open");
        }
    }

    @Test
    void openingAMarkdownFileLightsUpTheMarkdownFamily(@TempDir Path dir) throws Exception {
        Path md = dir.resolve("note.md");
        Files.writeString(md, "# Title\n\nbody\n");
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(md, 0, 0)), c -> {});
        // The config dir is the @TempDir: stop the window's config writer before JUnit deletes the directory,
        // or a late write races the cleanup and fails the test with DirectoryNotEmptyException.
        try {
            Map<String, Boolean> v =
                    verdicts(fx.controller, "edit.cut", "nav.lineUp", "markdown.bold", "markdown.toc", "csv.align");

            assertTrue(v.get("edit.cut"), "a buffer is open, so edit.* is actionable");
            assertTrue(v.get("nav.lineUp"), "a buffer is open, so nav.* is actionable");
            assertTrue(v.get("markdown.bold"), "a Markdown buffer makes markdown.* actionable");
            assertTrue(v.get("markdown.toc"), "a Markdown buffer makes markdown.* actionable");
            assertFalse(v.get("csv.align"), "a Markdown buffer is not a CSV buffer");
        } finally {
            fx.dispose();
        }
    }

    /**
     * A grayed row must explain itself, and — for a switched-off feature — name the command that turns it
     * back on, using that command's localized title rather than its raw id.
     */
    @Test
    @SuppressWarnings("unchecked")
    void grayedRowsCarryAnExplanatoryTooltip() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        CommandPalette palette = FxTestSupport.field(fx.controller, "palette");
        java.util.function.Function<com.editora.command.Command, String> reason =
                FxTestSupport.field(palette, "disabledReason");

        String lsp = FxTestSupport.callOnFx(
                () -> reason.apply(com.editora.command.Command.of("lsp.gotoDefinition", () -> {})));
        // LSP is off by default: the reason names the toggle by its localized title, not its id.
        assertTrue(lsp != null && !lsp.isBlank(), "a grayed row must say why");
        assertTrue(
                lsp.contains(com.editora.i18n.Messages.tr("command.view.toggleLsp")),
                "the tooltip should name the toggle command's title, was: " + lsp);
        assertFalse(lsp.contains("view.toggleLsp"), "the raw command id must not leak into the tooltip");

        // A context reason needs no command argument, and must not leave an unsubstituted placeholder.
        String markdown =
                FxTestSupport.callOnFx(() -> reason.apply(com.editora.command.Command.of("markdown.bold", () -> {})));
        assertTrue(markdown != null && !markdown.isBlank(), "a context-grayed row must say why");
        assertFalse(markdown.contains("{0}"), "no unsubstituted MessageFormat placeholder, was: " + markdown);

        // An actionable command has nothing to explain.
        String open = FxTestSupport.callOnFx(() -> reason.apply(com.editora.command.Command.of("file.open", () -> {})));
        assertTrue(open == null || open.isBlank(), "an enabled command needs no tooltip, was: " + open);
    }

    /**
     * Printing and exporting, per kind of file, as the File menu and the palette gate them in a real window.
     * The file itself can always be printed; its preview only when there is one that can be put on a page —
     * which an {@code .http} response panel is not, and which the preview commands used to find out only
     * after the Save dialog. A JSON file that does not parse stays lit (the gate does not parse the file on
     * every menu refresh); the command refuses it when run.
     */
    @Test
    void printAndExportAreGatedByWhatTheFileCanPutOnAPage(@TempDir Path dir) throws Exception {
        record Case(String name, String text, boolean preview, boolean exportable) {}
        for (Case c : List.of(
                new Case("Main.java", "class Main {}\n", false, false),
                new Case("note.md", "# Title\n", true, true),
                new Case("data.csv", "a,b\n1,2\n", true, true),
                new Case("req.http", "GET https://example.com\n", true, false),
                new Case("broken.json", "{\"a\": [1,\n", true, true))) {
            Path config = Files.createDirectories(dir.resolve("config-" + c.name()));
            Path file = Files.writeString(dir.resolve(c.name()), c.text());
            FxWindowFixture fx = FxWindowFixture.create(
                    config, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), w -> {});
            try {
                Map<String, Boolean> v = verdicts(
                        fx.controller,
                        "editor.print",
                        "editor.exportPdf",
                        "preview.print",
                        "preview.exportPdf",
                        "preview.copy");
                assertTrue(v.get("editor.print"), c.name() + ": the file itself can always be printed");
                assertTrue(v.get("editor.exportPdf"), c.name() + ": the file itself can always be exported");
                assertTrue(c.preview() == v.get("preview.copy"), c.name() + ": preview.copy needs only a preview");
                assertTrue(c.exportable() == v.get("preview.print"), c.name() + ": preview.print");
                assertTrue(c.exportable() == v.get("preview.exportPdf"), c.name() + ": preview.exportPdf");
            } finally {
                fx.dispose();
            }
        }
        // No buffer: nothing to print (asserted on the Welcome tab).
        FxWindowFixture fx = FxWindowFixture.create();
        Map<String, Boolean> v = verdicts(fx.controller, "editor.print", "editor.exportPdf");
        assertFalse(v.get("editor.print"), "nothing to print without a buffer");
        assertFalse(v.get("editor.exportPdf"), "nothing to export without a buffer");
    }

    @Test
    void openingACsvFileLightsUpTheCsvFamilyOnly(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "a,b\n1,2\n");
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(csv, 0, 0)), c -> {});
        // As above: opening the file queues recent-files/session writes on the config-writer thread, so the
        // writer must be stopped before JUnit deletes the @TempDir it is writing into.
        try {
            Map<String, Boolean> v = verdicts(fx.controller, "csv.align", "markdown.bold", "edit.cut");

            assertTrue(v.get("csv.align"), "a CSV buffer makes csv.* actionable");
            assertTrue(v.get("edit.cut"), "a buffer is open");
            assertFalse(v.get("markdown.bold"), "a CSV buffer is not a Markdown buffer");
        } finally {
            fx.dispose();
        }
    }
}
