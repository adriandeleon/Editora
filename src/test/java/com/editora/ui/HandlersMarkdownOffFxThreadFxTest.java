package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.MarkdownEditing;
import com.editora.editor.markdown.MarkdownFormatting;
import com.editora.editor.markdown.MarkdownPreviewPane;
import com.editora.markdown.MarkdownBlocks;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), the Markdown handlers: each test drives on the FX thread, the way a key, a click or a
 * command does, what used to scan or parse the whole document there — typing {@code *}, Enter in a list and in a
 * table, Backspace on an empty item, Tab, the heading and fence commands, the heading crumbs, the table of
 * contents, a footnote, the link commands and Ctrl-click, Format Document, the preview's Copy, the agent's
 * streamed reply — and listens to {@link BlockingSites}: {@link MarkdownBlocks#SCAN_SITE} (every block scan names
 * itself) and each moved place are heard off the FX thread, none on it. The other tests check that the answers
 * land right: on the text they were worked out from, worked out again or dropped when it moved meanwhile.
 *
 * <p>What it cannot see: a scan or parse at a place that does not name itself (it checks the named places, and
 * every {@code MarkdownBlocks.scan}, but not every loop over a document); the HTML export's look at the source
 * ({@code ExportCoordinator.HTML_EXPORT_CHECK_SITE}, named, but driving an export needs a file chooser); and the
 * popups' Markdown parse (hover, signature help, completion docs), which another part moves.
 */
@Tag("fx")
class HandlersMarkdownOffFxThreadFxTest {

    /** Every {@link BlockingSites#enter} heard: the site and whether it was the FX thread. */
    private record Heard(String site, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
        buffers.clear();
    }

    private void listen() {
        heard.clear();
        BlockingSites.listenForTest((site, thread) -> heard.add(new Heard(site, Platform.isFxApplicationThread())));
    }

    private void assertNothingOnFx() throws Exception {
        FxTestSupport.drainFx();
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "work done in place on the FX thread; heard: " + heard);
    }

    private void awaitHeard(String site) throws Exception {
        SettingsRig.awaitFx(
                site + " heard off FX",
                () -> heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()));
    }

    /** A Markdown buffer holding {@code text}, its line map scanned. */
    private EditorBuffer markdown(String text) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(
                    Path.of("/tmp/handlers-md", "doc" + buffers.size() + ".md").toAbsolutePath());
            buffer.setLanguageOverride("markdown");
            buffer.getArea().replaceText(text);
            buffers.add(buffer);
            return buffer;
        });
        awaitMap(b);
        return b;
    }

    private static void awaitMap(EditorBuffer b) throws Exception {
        boolean[] current = {false};
        FxTestSupport.runOnFx(() -> b.markdown().blockMap().whenCurrent(() -> current[0] = true));
        SettingsRig.awaitFx("the line map scanned", () -> current[0]);
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    // A long document: a fence far above the list, so a scan from the top would be the cost the keys used to pay.
    private static final String FAR = "```\n- not a list\n```\n\n" + "Prose line.\n".repeat(5_000);

    @Test
    void theMarkdownKeysAndLineCommandsLookTheirLinesUp() throws Exception {
        EditorBuffer b = markdown(FAR + "- item\n\n| a | b |\n|---|---|\n| 1 | 2 |\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        listen();
        FxTestSupport.runOnFx(() -> {
            int item = a.getText().indexOf("- item") + "- item".length();
            a.moveTo(item);
            assertTrue(b.markdown().enter(a), "Enter continues the list");
            assertTrue(b.markdown().backspace(a), "Backspace on the empty item steps out of it");
            a.replaceText(a.getCaretPosition(), a.getCaretPosition(), "- x");
            b.markdown().tab(a, true); // Tab on an item: nested under the one above
            int cell = a.getText().indexOf("| 1 ") + 2;
            a.moveTo(cell);
            assertTrue(b.markdown().enter(a), "Enter in a table row moves to the next row");
            a.moveTo(a.getText().indexOf("Prose line."));
            b.markdown().formatHeading(1);
            b.markdown().formatCodeFence();
        });
        assertTrue(text(b).contains("- item\n"), text(b));
        assertTrue(text(b).contains("# Prose line.") || text(b).contains("```\n# Prose line."), "the commands ran");
        awaitHeard(com.editora.editor.markdown.MarkdownBlockMap.SITE);
        awaitHeard(MarkdownBlocks.SCAN_SITE);
        // A typed '*' in prose pairs; inside the fence above it is just a character.
        FxTestSupport.runOnFx(() -> {
            a.moveTo(a.getText().indexOf("- not a list") + 2);
            assertFalse(b.markdown().typed(a, '*'), "a fence's line is not prose: nothing paired");
        });
        assertNothingOnFx();
    }

    @Test
    void enterInAListInsideAFenceIsAPlainNewlineAndOutsideItContinues() throws Exception {
        EditorBuffer b = markdown(FAR + "- one\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        FxTestSupport.runOnFx(() -> {
            a.moveTo(a.getText().indexOf("- not a list") + "- not a list".length());
            assertFalse(b.markdown().enter(a), "inside the fence: the caller's plain newline");
            a.moveTo(a.getLength() - 1);
            assertTrue(b.markdown().enter(a));
        });
        assertTrue(text(b).endsWith("- one\n- \n"), text(b));
    }

    /**
     * Between an edit and its scan the map is one version behind: the keys decide from it (no scan on the FX
     * thread), and the scan of the version they left lands afterwards.
     */
    @Test
    void aKeyPressedBeforeTheScanLandsDecidesFromTheMapItHas() throws Exception {
        EditorBuffer b = markdown("```\ncode\n```\n\n- one\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        listen();
        FxTestSupport.runOnFx(() -> {
            a.moveTo(a.getLength() - 1);
            assertTrue(b.markdown().enter(a), "the list continues: lines above the edit are known");
            a.replaceText(a.getCaretPosition(), a.getCaretPosition(), "two");
            assertTrue(b.markdown().enter(a), "the first edited line keeps its kind");
            assertFalse(b.markdown().blockMap().current());
            a.moveTo(1, 2);
            assertFalse(b.markdown().typed(a, '*'), "the fence above every edit is still code");
        });
        assertEquals("```\ncode\n```\n\n- one\n- two\n- \n", text(b));
        assertFalse(held.isEmpty(), "a scan was asked for");
        while (!held.isEmpty()) {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            now.forEach(Runnable::run);
            FxTestSupport.drainFx();
        }
        // The scan the key asked for answers the version it saw; the edit after it is scanned at the next pause
        // (or for whoever waits), not chased at once (perf review 2026-11-23 E1).
        boolean[] current = {false};
        FxTestSupport.runOnFx(() -> b.markdown().blockMap().whenCurrent(() -> current[0] = true));
        while (!held.isEmpty()) {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            now.forEach(Runnable::run);
            FxTestSupport.drainFx();
        }
        assertTrue(current[0], "a waiter is answered for the document as it is");
        assertTrue(FxTestSupport.callOnFx(() -> b.markdown().blockMap().current()), "the last scan is current");
        assertNothingOnFx();
    }

    private static long materializations(EditorBuffer buffer) {
        return (Long) FxTestSupport.call(buffer, "documentSnapshotMaterializations", new Class<?>[] {});
    }

    /** Typing copies and scans nothing per keystroke: the map is scanned when typing pauses (E1). */
    @Test
    void typingAsksNoScanPerKeystrokeOnlyWhenItPauses() throws Exception {
        EditorBuffer b = markdown(FAR + "- one\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        // The load's own scan (the first key after opening decides from it) lands before the count is taken.
        SettingsRig.awaitFx(
                "the load's scan landed", () -> b.markdown().blockMap().current());
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> {
            if (name.equals(com.editora.editor.markdown.MarkdownBlockMap.SITE)) {
                held.add(task);
            } else {
                task.run();
            }
        });
        long before = FxTestSupport.callOnFx(() -> materializations(b));
        FxTestSupport.runOnFx(() -> {
            for (char c : "typed words".toCharArray()) {
                a.replaceText(a.getLength(), a.getLength(), String.valueOf(c));
            }
        });
        assertTrue(held.isEmpty(), "no scan per keystroke");
        assertEquals(
                before, FxTestSupport.callOnFx(() -> materializations(b)), "no copy of the document per keystroke");
        SettingsRig.awaitFx("the pause asks for one scan", () -> !held.isEmpty());
        assertEquals(1, held.size());
        assertEquals(
                before,
                FxTestSupport.callOnFx(() -> materializations(b)),
                "the scan's text is built on its own thread, not when it is asked for");
        held.forEach(Runnable::run);
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(() -> b.markdown().blockMap().current()));
    }

    @Test
    void theHeadingCrumbsAreParsedOffTheFxThreadAndAStaleParseIsDropped() throws Exception {
        EditorBuffer b = markdown("# Guide\n\ntext\n\n## Use\n\nmore\n");
        FileBreadcrumb bar = FxTestSupport.callOnFx(() -> {
            FileBreadcrumb crumbs = new FileBreadcrumb(p -> {});
            crumbs.setEnabled(true);
            crumbs.setActiveBuffer(b);
            return crumbs;
        });
        SettingsRig.awaitFx("first parse", () -> !bar.headingCrumbs().parsing());
        listen();
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(6, 0);
            b.getArea().replaceText(0, 7, "# Top"); // stale now
            bar.headingCrumbs().refresh();
            b.getArea().replaceText(0, 5, "# Head"); // typed while the parse is out: its answer is dropped
        });
        SettingsRig.awaitFx("dropped", () -> !bar.headingCrumbs().parsing());
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(6, 0); // an edit leaves the caret behind its text
            bar.headingCrumbs().refresh();
        });
        SettingsRig.awaitFx("parsed again", () -> !bar.headingCrumbs().parsing());
        assertEquals(
                List.of("Head", "Use"),
                FxTestSupport.callOnFx(() -> bar.headingCrumbs().titles()));
        awaitHeard(MarkdownHeadingCrumbs.SITE);
        assertNothingOnFx();
    }

    @Test
    void theDocumentCommandsAndLinksWorkOnASnapshotOffTheFxThread() throws Exception {
        EditorBuffer b = markdown("# T\n\n## A\n\nsee [docs][d] and [x](http://x.org)\n\n[d]: http://docs.org\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        List<String> opened = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> b.setOpenUrlHandler(opened::add));
        listen();
        // Ctrl-click on a reference link: resolved against the whole document, off FX.
        FxTestSupport.runOnFx(() -> b.markdown().linkAt(a, a.getText().indexOf("docs]") + 1, url -> {
            if (url != null) {
                opened.add(url);
            }
        }));
        SettingsRig.awaitFx("the clicked link opened", () -> opened.contains("http://docs.org"));
        // Open Link at the caret.
        Boolean[] found = {null};
        FxTestSupport.runOnFx(() -> {
            a.moveTo(a.getText().indexOf("[x]") + 1);
            b.openLinkUnderCaret(f -> found[0] = f);
        });
        SettingsRig.awaitFx("Open Link answered", () -> found[0] != null);
        assertTrue(found[0]);
        // Inline → reference.
        Integer[] converted = {null};
        FxTestSupport.runOnFx(() -> b.markdown().convertLink(false, r -> converted[0] = r));
        SettingsRig.awaitFx("converted", () -> converted[0] != null);
        assertEquals(0, converted[0]);
        // The table of contents and a footnote.
        Boolean[] toc = {null};
        FxTestSupport.runOnFx(() -> {
            a.moveTo(4);
            b.insertOrUpdateToc(t -> toc[0] = t);
        });
        SettingsRig.awaitFx("the TOC written", () -> toc[0] != null);
        assertTrue(text(b).contains("<!-- toc -->"), text(b));
        FxTestSupport.runOnFx(() -> {
            a.moveTo(a.getText().indexOf("see"));
            b.markdown().insertFootnote();
        });
        SettingsRig.awaitFx("the footnote written", () -> a.getText().contains("[^1]: "));
        // Format Document.
        MarkdownFormatting.Outcome[] formatted = {null};
        FxTestSupport.runOnFx(() -> MarkdownFormatting.formatDocument(b, o -> formatted[0] = o));
        SettingsRig.awaitFx("formatted", () -> formatted[0] != null);
        // A paste of structured HTML: the clipboard is converted off FX.
        String[] pasted = {"none"};
        FxTestSupport.runOnFx(() -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putHtml("<table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>2</td></tr></table>");
            cc.putString("a\tb\n1\t2");
            Clipboard.getSystemClipboard().setContent(cc);
            assertTrue(b.markdown().pasteMayConvert(false));
            b.markdown().pasteAsMarkdown(false, md -> pasted[0] = md);
        });
        SettingsRig.awaitFx("the clipboard converted", () -> !"none".equals(pasted[0]));
        assertTrue(pasted[0] != null && pasted[0].contains("| a"), String.valueOf(pasted[0]));
        awaitHeard(MarkdownEditing.PASTE_SITE);
        awaitHeard(MarkdownEditing.LINK_AT_SITE);
        awaitHeard(MarkdownEditing.LINK_SITE);
        awaitHeard(MarkdownEditing.TOC_SITE);
        awaitHeard(MarkdownEditing.FOOTNOTE_SITE);
        awaitHeard(MarkdownFormatting.FORMAT_SITE);
        assertNothingOnFx();
    }

    /** The table of contents worked out for a text typed into meanwhile is worked out again, then applied once. */
    @Test
    void aCommandWhoseTextMovedMeanwhileIsWorkedOutAgain() throws Exception {
        EditorBuffer b = markdown("# T\n\n\n\n## A\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        Boolean[] toc = {null};
        FxTestSupport.runOnFx(() -> {
            a.moveTo(4);
            b.insertOrUpdateToc(t -> toc[0] = t);
            a.appendText("## B\n"); // typed before the answer lands
            a.moveTo(4); // the caret back where the command was asked
        });
        runAll(held); // the first answer is for the old text: dropped, worked out again
        assertTrue(toc[0], "written once the second answer landed");
        assertTrue(text(b).contains("- [B](#b)"), "worked out from the text as it was then: " + text(b));
        assertEquals(1, text(b).split("<!-- toc -->", -1).length - 1, "one block");
    }

    /** A command typed over every time its answer lands gives up after its retries: nothing is applied. */
    @Test
    void aCommandTypedOverEveryTimeIsDroppedAfterItsRetries() throws Exception {
        EditorBuffer b = markdown("# T\n\n\n\n## A\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        Boolean[] toc = {null};
        MarkdownFormatting.Outcome[] formatted = {null};
        FxTestSupport.runOnFx(() -> {
            a.moveTo(4);
            b.insertOrUpdateToc(t -> toc[0] = t);
            MarkdownFormatting.formatDocument(b, o -> formatted[0] = o);
        });
        for (int round = 0; round < 10 && toc[0] == null; round++) {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            FxTestSupport.runOnFx(() -> {
                a.appendText("x"); // typed again before each answer lands
                a.moveTo(4);
                now.forEach(Runnable::run);
            });
        }
        assertEquals(Boolean.FALSE, toc[0], "given up: told there was nothing written");
        assertEquals(MarkdownFormatting.Outcome.REFUSED, formatted[0]);
        assertFalse(text(b).contains("<!-- toc -->"));
    }

    /** A Ctrl-click answer for a text changed since the click is dropped: no link opens. */
    @Test
    void aClickedLinkWhoseTextChangedIsNotOpened() throws Exception {
        EditorBuffer b = markdown("[x](http://x.org)\n");
        CodeArea a = FxTestSupport.callOnFx(b::getArea);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        String[] url = {"none"};
        FxTestSupport.runOnFx(() -> {
            b.markdown().linkAt(a, 1, u -> url[0] = u);
            a.appendText("more\n");
        });
        runAll(held);
        assertEquals("none", url[0]);
    }

    @Test
    void thePreviewsCopyCutsItsSourceOffTheFxThread() throws Exception {
        String doc = "# One\n\nFirst paragraph.\n\nSecond paragraph.\n";
        EditorBuffer b = markdown(doc);
        MarkdownPreviewPane[] pane = {null};
        Stage[] stage = {null};
        FxTestSupport.runOnFx(() -> {
            pane[0] = new MarkdownPreviewPane((buffer, line) -> {});
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new HBox(b.getNode(), pane[0].node()), 900, 500));
            stage[0].show();
            pane[0].setSource(b);
        });
        try {
            SettingsRig.awaitFx(
                    "the preview rendered", () -> pane[0].preview().selection().selectAll());
            listen();
            FxTestSupport.runOnFx(() -> {
                Clipboard.getSystemClipboard().clear();
                assertTrue(pane[0].preview().copySelection());
            });
            SettingsRig.awaitFx(
                    "the clipboard filled",
                    () -> String.valueOf(Clipboard.getSystemClipboard().getString())
                            .contains("Second paragraph."));
            awaitHeard(com.editora.editor.markdown.MarkdownPreview.COPY_SITE);
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(() -> {
                pane[0].dispose();
                stage[0].close();
            });
        }
    }

    @Test
    void theAgentsStreamedReplyIsParsedOffTheFxThread() throws Exception {
        AgentPanel panel = FxTestSupport.callOnFx(
                () -> new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, path -> {}));
        listen();
        FxTestSupport.runOnFx(() -> {
            panel.appendChunk("# Plan\n\nFirst **step**.\n\n");
            panel.appendChunk("Second step.");
        });
        SettingsRig.awaitFx("a streaming render", () -> panel.wholeRenderCount() + panel.tailRenderCount() > 0);
        int before = FxTestSupport.callOnFx(panel::wholeRenderCount);
        FxTestSupport.runOnFx(() -> panel.setBusy(false)); // the turn ends: the reply is rendered whole
        SettingsRig.awaitFx("the whole render", () -> panel.wholeRenderCount() > before);
        awaitHeard(AgentPanel.PARSE_SITE);
        assertNothingOnFx();
    }

    @Test
    void theMermaidFenceIsLookedUpInTheLineMap() throws Exception {
        EditorBuffer b = markdown("text\n\n```mermaid\ngraph TD\n\n```\n");
        assertEquals(
                "mermaid",
                FxTestSupport.callOnFx(() -> b.markdown()
                        .blockMap()
                        .fenceInfoAt(4, b.getArea().getParagraphs().size())));
        assertNull(FxTestSupport.callOnFx(() -> b.markdown()
                .blockMap()
                .fenceInfoAt(0, b.getArea().getParagraphs().size())));
    }

    private static void runAll(List<Runnable> held) throws Exception {
        for (int round = 0; round < 10 && !held.isEmpty(); round++) {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            FxTestSupport.runOnFx(() -> now.forEach(Runnable::run));
            FxTestSupport.drainFx();
        }
    }
}
