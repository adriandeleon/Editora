package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.CaretLook;
import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.MarkdownFormatting;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), the editor's handlers: each test drives on the FX thread, the way an event does, what used to
 * search or parse the user's text there — Select All Occurrences, Jump to Matching Bracket and Mark Sexp, the caret
 * beside a bracket whose partner is off screen, a whole-document rewrite (a diff apply, a history restore, Markdown
 * Format Document, Convert Indentation), text given to a new tab, an edit that replaces the span a personal note sat
 * in, and a window whose session no one read ahead — and listens to {@link BlockingSites}: every named place is heard
 * off the FX thread, none on it. Then the stale-answer paths: an answer for text that changed is dropped, never
 * applied.
 *
 * <p>What it cannot see: work at a place that does not name itself (it checks the named places, not every loop); the
 * {@code *} typed after {@code /*} in a Java buffer with a language server, which looks only {@code 4096} characters
 * below in place by design (it checks the outcome, not a thread); the MCP and agent writes (their search runs on the
 * caller's thread, not the FX thread, by construction).
 */
@Tag("fx")
class HandlersEditorOffFxThreadFxTest {

    /** Every {@link BlockingSites#enter} heard: the site and whether it was the FX thread. */
    private record Heard(String site, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private FxWindowFixture fx;

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        if (fx != null) {
            fx.dispose();
        }
    }

    private void listen() {
        heard.clear();
        BlockingSites.listenForTest((site, thread) -> heard.add(new Heard(site, Platform.isFxApplicationThread())));
    }

    private void assertNothingOnFx() {
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

    private EditorBuffer open(Path file) throws Exception {
        MainController mc = fx.controller;
        FxTestSupport.runOnFx(() -> mc.openAndNavigate(file, 0));
        SettingsRig.awaitFx("the tab of " + file.getFileName(), () -> {
            EditorBuffer active = mc.activeBuffer();
            return active != null && file.equals(active.getPath()) && !active.isLoading();
        });
        return FxTestSupport.callOnFx(mc::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    /** A Java file: an opening brace at the top whose partner is far below, and a word used on every line. */
    private static String bigJava() {
        StringBuilder text = new StringBuilder("class Big {\n");
        String line = "    int needle = 1; // a line long enough to fill a screen and more, and more\n";
        while (text.length() < 40_000) {
            text.append(line);
        }
        return text.append("}\n").toString();
    }

    @Test
    void occurrencesBracketsAndMotionsSearchOffTheFxThread() throws Exception {
        Path big = Files.writeString(dir.resolve("Big.java"), bigJava());
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), s -> {});
        EditorBuffer buffer = open(big);
        CodeArea area = FxTestSupport.callOnFx(buffer::getArea);
        listen();

        // Select All Occurrences of the word at the caret.
        int needle = FxTestSupport.callOnFx(() -> area.getText().indexOf("needle"));
        FxTestSupport.runOnFx(() -> area.moveTo(needle + 2));
        run("edit.selectAllOccurrences");
        awaitHeard(EditorBuffer.OCCURRENCES_SITE);
        SettingsRig.awaitFx("the word selected", () -> "needle".equals(area.getSelectedText()));

        // Jump to the bracket that matches the one at the top, 40,000 characters on.
        FxTestSupport.runOnFx(() -> area.moveTo(area.getText().indexOf('{')));
        run("nav.matchingBracket");
        awaitHeard(EditingCoordinator.MOTION_SITE);
        SettingsRig.awaitFx("at the far partner", () -> area.getCaretPosition() > 39_000);

        // The caret beside that bracket: its far partner is looked for off FX, the pair shown when it lands.
        heard.clear();
        FxTestSupport.runOnFx(() -> {
            area.moveTo(area.getText().indexOf('{') + 1);
            area.requestFollowCaret();
        });
        awaitHeard("EditorBuffer.braceMatch");

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void wholeDocumentRewritesAndTextGivenToATabWorkOffTheFxThread() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("# Title\n\n* one\n+ two\n\tindented\n");
            return b;
        });
        try {
            listen();
            // A diff apply / history restore / agent write: the span that differs is worked out off FX.
            boolean[] done = {false};
            FxTestSupport.runOnFx(
                    () -> buffer.replaceWholeDocumentLater("# Title\n\n* one\n* two\n\tindented\n", ok -> {
                        done[0] = ok;
                    }));
            awaitHeard(EditorBuffer.WHOLE_EDIT_SITE);
            SettingsRig.awaitFx("applied", () -> done[0]);
            assertEquals("# Title\n\n* one\n* two\n\tindented\n", FxTestSupport.callOnFx(buffer::getContent));

            // Text given to a new tab (a response body, a library source): its line endings resolved off FX.
            boolean[] installed = {false};
            EditorBuffer tab = FxTestSupport.callOnFx(EditorBuffer::new);
            FxTestSupport.runOnFx(() -> tab.setContentLater("a\r\nb\r\n", () -> installed[0] = true));
            awaitHeard(EditorBuffer.PREPARE_TEXT_SITE);
            SettingsRig.awaitFx("installed", () -> installed[0]);
            assertEquals("a\nb\n", FxTestSupport.callOnFx(tab::getContent));
            assertEquals("CRLF", FxTestSupport.callOnFx(tab::getLineEnding));
            FxTestSupport.runOnFx(tab::dispose);

            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    @Test
    void markdownFormattingAndConvertIndentationWorkOffTheFxThread() throws Exception {
        Path md = Files.writeString(dir.resolve("notes.md"), "# Title\n\n* one\n+ two\n");
        Path code = Files.writeString(dir.resolve("Tabs.java"), "class T {\n\tint x;\n}\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), s -> {});
        EditorBuffer markdown = open(md);
        listen();
        MarkdownFormatting.Outcome[] outcome = {null};
        FxTestSupport.runOnFx(() -> MarkdownFormatting.formatDocument(markdown, o -> outcome[0] = o));
        awaitHeard(MarkdownFormatting.FORMAT_SITE);
        SettingsRig.awaitFx("formatted", () -> outcome[0] != null);
        assertEquals(MarkdownFormatting.Outcome.CHANGED, outcome[0]);

        EditorBuffer java = open(code);
        run("edit.indentationToSpaces");
        awaitHeard(EditingCoordinator.CONVERT_INDENTATION_SITE);
        SettingsRig.awaitFx("converted", () -> !java.getContent().contains("\t"));

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void aNoteInAReplacedSpanFollowsItsTextOffTheFxThread() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("intro\nthe noted words here\n");
            return b;
        });
        try {
            com.editora.config.PersonalNote note = com.editora.config.PersonalNote.create(
                    null,
                    com.editora.config.NoteScope.WORD,
                    new com.editora.config.TextAnchor(1, 4, 1, 9, "noted", "the ", " words"),
                    "a note",
                    List.of());
            boolean[] landed = {false};
            FxTestSupport.runOnFx(() -> buffer.applyNotes(List.of(note), m -> landed[0] = true));
            SettingsRig.awaitFx("the notes placed", () -> landed[0]);
            listen();
            // A Replace All / format rewrite of the whole text: the note's text moved two lines down inside it.
            FxTestSupport.runOnFx(() -> buffer.getArea()
                    .replaceText(0, buffer.getArea().getLength(), "a\nb\nintro\nthe noted words here\n"));
            awaitHeard(com.editora.editor.notes.NoteManager.REPLACEMENT_SITE);
            int at = "a\nb\nintro\nthe ".length();
            SettingsRig.awaitFx("the note on its text again", () -> noteStart(buffer) == at);
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    @Test
    void aWindowWhoseSessionNoOneReadIsBuiltWhenTheReadLands() throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        listen();
        fx = FxWindowFixture.launchWithoutReadAhead(config);
        awaitHeard(WindowManager.SESSION_READ_SITE);
        FxTestSupport.drainFx();
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx() && h.site().startsWith("WindowManager.")) {
                onFx.add(h.site());
            }
        }
        BlockingSites.listenForTest(null);
        assertEquals(List.of(), onFx, "the window's session was read on the FX thread");
    }

    @Test
    void renamingATagFindsItsPairOffTheFxThread() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("html");
            buffer.setContent("<div>text</div>");
            buffer.setAutoRenameTag(true);
            buffer.getNode();
            return buffer;
        });
        try {
            listen();
            FxTestSupport.runOnFx(() -> b.getArea().replaceText(4, 4, "x")); // <div|> → <divx>
            awaitHeard("TagRenameMirror.pair");
            SettingsRig.awaitFx("the closer renamed", () -> "<divx>text</divx>".equals(b.getContent()));
            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(b::dispose);
        }
    }

    /** Two characters typed into a tag before the first's pair was found: one rename, from the name it had. */
    @Test
    void aTagNameTypedFasterThanItsPairIsFoundIsRenamedOnce() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("html");
            buffer.setContent("<div>text</div>");
            buffer.setAutoRenameTag(true);
            buffer.getNode();
            return buffer;
        });
        try {
            FxTestSupport.runOnFx(() -> b.getArea().replaceText(4, 4, "x"));
            FxTestSupport.runOnFx(() -> b.getArea().replaceText(5, 5, "y")); // before the first pair lands
            runHeld(held); // the first answer is dropped, the second renames the closer from "div"
            assertEquals("<divxy>text</divxy>", FxTestSupport.callOnFx(b::getContent));
        } finally {
            FxTestSupport.runOnFx(b::dispose);
        }
    }

    /**
     * Round 2: Expand Selection, kill-sexp, Narrow to Defun, Toggle Comment over the whole file, Split into Lines over
     * the whole file, Query Replace (its next match and its {@code !}) and the JUnit test at the caret.
     */
    @Test
    void selectionKillsNarrowingCommentsSplitsQueryReplaceAndTheTestAtTheCaretWorkOffTheFxThread() throws Exception {
        StringBuilder text = new StringBuilder("class Big {\n    void m() {\n");
        while (text.length() < 40_000) {
            text.append("        int needle = call(1, 2);\n");
        }
        text.append("    }\n}\n");
        Path big = Files.writeString(dir.resolve("Big.java"), text.toString());
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), s -> {});
        EditorBuffer buffer = open(big);
        CodeArea area = FxTestSupport.callOnFx(buffer::getArea);
        listen();

        // Expand Selection from inside "call(1, 2)".
        int inCall = FxTestSupport.callOnFx(() -> area.getText().indexOf("1, 2"));
        FxTestSupport.runOnFx(() -> area.moveTo(inCall));
        run("edit.expandSelection");
        awaitHeard(EditingCoordinator.MOTION_SITE);
        SettingsRig.awaitFx("a selection grown", () -> area.getSelection().getLength() > 0);

        // kill-sexp from before "(1, 2)": the balanced expression goes.
        heard.clear();
        int paren = FxTestSupport.callOnFx(() -> area.getText().indexOf("(1, 2)"));
        FxTestSupport.runOnFx(() -> area.moveTo(paren));
        run("edit.killSexp");
        awaitHeard(EditingCoordinator.MOTION_SITE);
        SettingsRig.awaitFx(
                "the sexp killed",
                () -> area.getText().startsWith("class Big {\n    void m() {\n        int needle = call;"));

        // Toggle Comment over the whole file, then Split into Lines over it.
        FxTestSupport.runOnFx(area::selectAll);
        run("edit.toggleComment");
        awaitHeard(EditorBuffer.COMMENT_SITE);
        SettingsRig.awaitFx("commented", () -> !area.getText().startsWith("class"));
        FxTestSupport.runOnFx(area::undo);
        SettingsRig.awaitFx("uncommented", () -> area.getText().startsWith("class"));
        FxTestSupport.runOnFx(() -> {
            buffer.setMultiCaretEnabled(true);
            area.selectRange(0, 200);
        });
        run("edit.splitSelectionIntoLines");
        awaitHeard("BufferCarets.splitIntoLines");

        // Narrow to Defun from inside m().
        FxTestSupport.runOnFx(() -> {
            buffer.setMultiCaretEnabled(false);
            area.moveTo(inCall);
        });
        run("edit.narrowToDefun");
        awaitHeard(EditingCoordinator.DEFUN_SITE);
        SettingsRig.awaitFx("narrowed", buffer::isNarrowed);
        FxTestSupport.runOnFx(buffer::widen);

        // Query Replace: its first match and its "!" are looked for off FX.
        FxTestSupport.runOnFx(() -> {
            area.moveTo(0);
            FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "editing"),
                    "beginQueryReplace",
                    new Class[] {EditorBuffer.class, com.editora.editor.search.QueryReplace.Spec.class},
                    buffer,
                    new com.editora.editor.search.QueryReplace.Spec("needle", "pin", false, false, false, false));
        });
        awaitHeard(EditingCoordinator.QUERY_REPLACE_SITE);
        SettingsRig.awaitFx("the first match shown", () -> area.getCaretPosition() > 0);
        FxTestSupport.runOnFx(() -> area.fireEvent(
                new KeyEvent(KeyEvent.KEY_TYPED, "!", "", KeyCode.UNDEFINED, false, false, false, false)));
        SettingsRig.awaitFx("all replaced", () -> !area.getText().contains("needle"));

        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void theTestAtTheCaretIsReadFromTheScansIndex() throws Exception {
        Path test = Files.writeString(
                dir.resolve("ATest.java"),
                "import org.junit.jupiter.api.Test;\n\nclass ATest {\n    @Test\n    void one() {\n        int x = 1;\n"
                        + "    }\n\n    @Test\n    void two() {\n    }\n}\n");
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(test);
            b.setContent(Files.readString(test));
            b.setTestGutterEnabled(true);
            return b;
        });
        boolean[] scanned = {false};
        FxTestSupport.runOnFx(() -> buffer.whenRunScanned(() -> scanned[0] = true));
        SettingsRig.awaitFx("scanned", () -> scanned[0] && buffer.testClassTarget() != null);
        FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(5, 4)); // inside one()
        assertEquals(
                "one",
                FxTestSupport.callOnFx(() -> buffer.testTargetAtCaret(false).methodName()));
        FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(3, 4)); // its @Test line
        assertEquals(
                "one",
                FxTestSupport.callOnFx(() -> buffer.testTargetAtCaret(false).methodName()));
        FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(9, 6)); // two()'s line
        assertEquals(
                "two",
                FxTestSupport.callOnFx(() -> buffer.testTargetAtCaret(false).methodName()));
    }

    /** A Query Replace command typed while the next match is still being looked for does nothing. */
    @Test
    void aQueryReplaceKeyWhileTheMatchIsSoughtDoesNothing() throws Exception {
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), s -> {});
        List<Runnable> held = new CopyOnWriteArrayList<>();
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("a b a\n");
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            buffer.getArea().moveTo(0);
            return buffer;
        });
        OffFx.runnerForTest((name, task) -> held.add(task));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "editing"),
                "beginQueryReplace",
                new Class[] {EditorBuffer.class, com.editora.editor.search.QueryReplace.Spec.class},
                b,
                new com.editora.editor.search.QueryReplace.Spec("a", "z", false, false, false, false)));
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "y", "", KeyCode.UNDEFINED, false, false, false, false)));
        assertEquals("a b a\n", FxTestSupport.callOnFx(b::getContent), "no match known yet: nothing replaced");
        runHeld(held);
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "y", "", KeyCode.UNDEFINED, false, false, false, false)));
        assertEquals("z b a\n", FxTestSupport.callOnFx(b::getContent));
        runHeld(held);
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "q", "", KeyCode.UNDEFINED, false, false, false, false)));
    }

    // --- stale answers ------------------------------------------------------------------------------------

    @Test
    void aMotionAnsweredForTextThatChangedIsDropped() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        CodeArea area = FxTestSupport.callOnFx(() -> {
            CodeArea a = new CodeArea("{ a }");
            a.moveTo(0);
            return a;
        });
        boolean[] moved = {false};
        FxTestSupport.runOnFx(() -> CaretLook.then(
                "test.look",
                area,
                at -> com.editora.editops.BraceMatcher.jumpTarget(at.text(), at.caret(), 1000),
                target -> moved[0] = true));
        assertTrue(FxTestSupport.callOnFx(() -> CaretLook.busy(area)));
        assertFalse(
                FxTestSupport.callOnFx(() -> CaretLook.then("test.look", area, at -> 1, t -> moved[0] = true)),
                "a second press while one is out is refused");
        FxTestSupport.runOnFx(() -> area.insertText(0, "x")); // typed before the answer lands
        runHeld(held);
        assertFalse(moved[0], "an answer for the old text is dropped");
        assertFalse(FxTestSupport.callOnFx(() -> CaretLook.busy(area)));
    }

    @Test
    void aRewriteWorkedOutForTextThatChangedIsNotApplied() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("one\n");
            return b;
        });
        try {
            Boolean[] done = {null};
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocumentLater("two\n", ok -> done[0] = ok));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("typed\n"));
            runHeld(held);
            assertEquals(Boolean.FALSE, done[0]);
            assertEquals("one\ntyped\n", FxTestSupport.callOnFx(buffer::getContent), "what was typed is kept");

            // A prepared edit for an older version is refused, and one for this version applies.
            EditorBuffer.PreparedEdit stale = FxTestSupport.callOnFx(
                    () -> EditorBuffer.PreparedEdit.between(buffer.docVersion() - 1, buffer.getContent(), "x\n"));
            assertFalse(FxTestSupport.callOnFx(() -> buffer.applyPreparedWholeText(stale, "x\n")));
            EditorBuffer.PreparedEdit fresh = FxTestSupport.callOnFx(() ->
                    EditorBuffer.PreparedEdit.between(buffer.docVersion(), buffer.getContent(), "one\ntyped\nmore\n"));
            assertTrue(FxTestSupport.callOnFx(() -> buffer.applyPreparedWholeText(fresh, "one\ntyped\nmore\n")));
            assertEquals("one\ntyped\nmore\n", FxTestSupport.callOnFx(buffer::getContent));

            // Text given to a tab the user typed into meanwhile is not installed over it.
            boolean[] installed = {false};
            FxTestSupport.runOnFx(() -> buffer.setContentLater("given\n", () -> installed[0] = true));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("!"));
            runHeld(held);
            assertFalse(installed[0]);
            assertEquals("one\ntyped\nmore\n!", FxTestSupport.callOnFx(buffer::getContent));
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    @Test
    void aNoteFollowedIntoAReplacementThatWasTypedIntoIsFoundAgain() throws Exception {
        List<Runnable> held = new CopyOnWriteArrayList<>();
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("intro\nthe noted words here\n");
            return b;
        });
        try {
            com.editora.config.PersonalNote note = com.editora.config.PersonalNote.create(
                    null,
                    com.editora.config.NoteScope.WORD,
                    new com.editora.config.TextAnchor(1, 4, 1, 9, "noted", "the ", " words"),
                    "a note",
                    List.of());
            boolean[] landed = {false};
            FxTestSupport.runOnFx(() -> buffer.applyNotes(List.of(note), m -> landed[0] = true));
            SettingsRig.awaitFx("the notes placed", () -> landed[0]);
            OffFx.runnerForTest((name, task) -> held.add(task));
            FxTestSupport.runOnFx(() ->
                    buffer.getArea().replaceText(0, buffer.getArea().getLength(), "a\nintro\nthe noted words here\n"));
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "z")); // typed before the follow lands
            runHeld(held); // stale: found again in the text as it is now
            runHeld(held);
            int at = "za\nintro\nthe ".length();
            assertEquals(at, FxTestSupport.callOnFx(() -> noteStart(buffer)));
        } finally {
            OffFx.runnerForTest(null);
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    @Test
    void typingStarAfterSlashStarClosesTheCommentWithABoundedLook() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class A {\n    /*\n    void m() {}\n}\n");
            b.setLspActive(true);
            return b;
        });
        try {
            int caret = "class A {\n    /*".length();
            FxTestSupport.runOnFx(() -> {
                CodeArea a = buffer.getArea();
                a.moveTo(caret);
                a.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "*", "*", KeyCode.UNDEFINED, false, false, false, false));
            });
            assertTrue(
                    FxTestSupport.callOnFx(buffer::getContent).startsWith("class A {\n    /** */"),
                    FxTestSupport.callOnFx(buffer::getContent));
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    /** Where the buffer's one note starts now; -1 when it has none placed. */
    private static int noteStart(EditorBuffer buffer) {
        List<int[]> spans = buffer.getNoteManager().activeSpans();
        return spans.isEmpty() ? -1 : spans.get(0)[0];
    }

    private static void runHeld(List<Runnable> held) throws Exception {
        List<Runnable> now = new ArrayList<>(held);
        held.clear();
        now.forEach(Runnable::run);
        FxTestSupport.drainFx();
    }
    /**
     * A record logged on the FX thread is written to the session log and the console by the log's own thread; the
     * status bar's size is counted off the FX thread after an edit; a curl command is converted off it, and its new
     * tab's text is prepared off it too (PERF-01, Pauta 6.0.1).
     */
    @Test
    void aRecordLoggedOnFxTheSizeAfterAnEditAndACurlImportWorkOffTheFxThread() throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "héllo\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(
                Files.createDirectories(dir.resolve("config")),
                s -> s.getSettings().setHttpClientSupport(true));
        EditorBuffer buffer = open(file);
        Path logDir = Files.createDirectories(dir.resolve("log"));
        DebugLog.install();
        DebugLog.attachFile(logDir);
        try {
            listen();
            String marker = "logged on the FX thread " + System.nanoTime();
            FxTestSupport.runOnFx(
                    () -> java.util.logging.Logger.getLogger("perf.guard").warning(marker));
            assertTrue(DebugLog.flushWrites(10_000), "the writer caught up");
            awaitHeard(DebugLog.WRITE_SITE);
            assertTrue(Files.readString(DebugLog.sessionFile(logDir)).contains(marker), "the record is in the file");

            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("more\n"));
            awaitHeard(StatusBar.SIZE_SITE);

            FxTestSupport.runOnFx(() -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString("curl -X PUT https://example.test/b -H 'Accept: text/plain'");
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            });
            run("http.importCurl");
            awaitHeard(HttpClientCoordinator.CURL_IMPORT_SITE);
            SettingsRig.awaitFx("the request in a new tab", () -> {
                EditorBuffer active = fx.controller.activeBuffer();
                return active != null && active.getContent().startsWith("PUT https://example.test/b");
            });
            awaitHeard(EditorBuffer.PREPARE_TEXT_SITE);

            FxTestSupport.drainFx();
            assertNothingOnFx();
        } finally {
            DebugLog.detachFileForTest();
        }
    }
}
