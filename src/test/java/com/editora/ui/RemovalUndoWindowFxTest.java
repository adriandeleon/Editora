package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import com.editora.config.ExceptionBreakpoint;
import com.editora.config.MemberBreakpoint;
import com.editora.config.StopFilters;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edit: Undo Last Removal through the window (CONF-01, Pauta 6.0.1): each small removal that asks nothing is made the
 * way the user makes it, the command is run from the registry, and what was removed is back as it was. Then the same
 * removal with its place taken or gone: the status line says it can no longer be undone and nothing is overwritten.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class RemovalUndoWindowFxTest {

    private static final String HINT = " Undo Last Removal takes this back.";
    private static final String TEXT = "a = 1\nb = 2\nc = 3\nd = 4\ne = 5\n";

    /** The file with no tab: Java, a language a Project Map card sets breakpoints in without one. */
    private static final String OTHER = "class Other {\n  int a = 1;\n  int b = 2;\n  int c = 3;\n}\n";

    private FxWindowFixture fx;
    private DebugCoordinator debug;
    private Path script;
    private Path other;

    /** A file whose text the tests edit: never closed, so its unsaved text raises no question. */
    private Path edited;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("undo")).toRealPath();
        script = Files.writeString(root.resolve("main.py"), TEXT);
        other = Files.writeString(root.resolve("Other.java"), OTHER);
        edited = Files.writeString(root.resolve("edited.py"), TEXT);
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            debug.applySupport();
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** Each test starts with nothing to take back, no breakpoint, pin or bookmark, and main.py in front. */
    @BeforeEach
    void clear() throws Exception {
        EditorBuffer b = open(script);
        FxTestSupport.runOnFx(() -> {
            for (int line : new ArrayList<>(b.getBreakpointManager().lines())) {
                b.dropBreakpoint(line);
            }
            b.getPinManager().clear();
            b.clearBookmarks();
            ops().breakpointMap().remove(DebugCoordinator.key(other));
            debug.memberBreakpoints().all().forEach(debug.memberBreakpoints()::remove);
            ops().persistExceptionBreakpoints(List.of());
            fx.controller.editorSettings.resetAllShortcuts();
            fx.controller.removalUndo.clear();
            fx.controller.setStatus("");
        });
    }

    // --- harness ------------------------------------------------------------------------------------------

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private DebugCoordinator.Ops ops() {
        return FxTestSupport.field(debug, "ops");
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private void undo() throws Exception {
        run("edit.undoRemoval");
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private int recorded() {
        return call(() -> fx.controller.removalUndo.size());
    }

    private EditorBuffer open(Path file) throws Exception {
        if (!call(() -> fx.controller.activeBuffer() != null
                && file.equals(fx.controller.activeBuffer().getPath()))) {
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        }
        await(
                file.getFileName() + " in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && file.equals(fx.controller.activeBuffer().getPath())
                        && fx.controller.activeBuffer().lineCount() >= 5));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void close(Path file) throws Exception {
        EditorBuffer b = open(file);
        FxTestSupport.runOnFx(() -> fx.controller.closeTab(fx.controller.editorArea.selectedTab()));
        await(file.getFileName() + " closed", () -> call(() -> ops().bufferForPath(file) != b));
    }

    /** A breakpoint on {@code line} with everything a user can type or choose on one. */
    private static Breakpoint detailed(int line, String lineText) {
        return new Breakpoint(
                line,
                "a > 1",
                "",
                false,
                lineText,
                ">= 3",
                false,
                List.of(),
                false,
                false,
                "bp1",
                "",
                "Setup",
                new StopFilters("main", "", ""));
    }

    /** Puts {@code bp} on its line of {@code b} and the caret there. */
    private void set(EditorBuffer b, Breakpoint bp) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.getBreakpointManager().add(bp);
            b.getFocusedArea().moveTo(bp.line(), 0);
        });
    }

    private Breakpoint at(EditorBuffer b, int line) {
        return call(() -> b.getBreakpointManager().get(line));
    }

    private List<Breakpoint> stored(Path file) {
        return call(() -> List.copyOf(ops().breakpointMap().getOrDefault(DebugCoordinator.key(file), List.of())));
    }

    // --- 2. line breakpoints --------------------------------------------------------------------------------

    @Test
    void aBreakpointWithDetailsToggledOffComesBackAsItWas() throws Exception {
        EditorBuffer b = open(script);
        Breakpoint before = detailed(1, "b = 2");
        set(b, before);
        run("debug.toggleBreakpoint");
        assertNull(at(b, 1));
        assertEquals("Breakpoint on line 2 of main.py removed." + HINT, status());

        undo();
        assertEquals(before, at(b, 1), "its condition, hit count, group, id, filter and disabled state");
        assertEquals("Breakpoint on line 2 of main.py restored.", status());
        assertEquals(0, recorded());
        undo();
        assertEquals("No removal to undo.", status());
    }

    @Test
    void aPlainBreakpointRecordsNothingAndSaysNothing() throws Exception {
        EditorBuffer b = open(script);
        set(b, Breakpoint.plain(1, "b = 2"));
        run("debug.toggleBreakpoint");
        assertNull(at(b, 1));
        assertEquals(0, recorded(), "the same key puts it back");
        assertEquals("", status());
        undo();
        assertEquals("No removal to undo.", status());
        assertNull(at(b, 1));
    }

    @Test
    void theTemporaryAndRecordingTogglesAndTheBreakpointsListRecordTheirRemovalsToo() throws Exception {
        EditorBuffer b = open(script);
        Breakpoint temporary = detailed(1, "b = 2").withEnabled(true).withTemporary(true);
        set(b, temporary);
        run("debug.toggleTemporaryBreakpoint");
        assertNull(at(b, 1));
        undo();
        assertEquals(temporary, at(b, 1));

        Breakpoint recording = detailed(2, "c = 3").withEnabled(true).withRecording(true);
        set(b, recording);
        run("debug.toggleRecordingBreakpoint");
        assertNull(at(b, 2));
        undo();
        assertEquals(recording, at(b, 2));

        // Debug: Breakpoints, Alt+D on the row of the one on line 3.
        FxTestSupport.runOnFx(() -> debug.breakpointsPicker()
                .remove(debug.breakpointsPicker().entries().stream()
                        .filter(e -> e.buffer() == b && e.line() == 2)
                        .findFirst()
                        .orElseThrow()));
        assertNull(at(b, 2));
        assertEquals("Breakpoint on line 3 of main.py removed." + HINT, status());
        undo();
        assertEquals(recording, at(b, 2));
    }

    @Test
    void aBreakpointTheProgramRemovedRecordsNothing() throws Exception {
        EditorBuffer b = open(script);
        set(b, detailed(1, "b = 2").withTemporary(true));
        FxTestSupport.runOnFx(() -> b.dropBreakpoint(1)); // what a temporary breakpoint that was hit goes through
        assertNull(at(b, 1));
        assertEquals(0, recorded());
    }

    @Test
    void theLastLambdaToggledOffRecordsOnlyWhatTheSameKeyWouldNotBringBack() throws Exception {
        EditorBuffer b = open(script);
        // A breakpoint in lambda 1 alone: the toggle that removes it sets it again.
        FxTestSupport.runOnFx(() -> {
            debug.lambdaBreakpoints().toggle(b, 1, 1);
            debug.lambdaBreakpoints().toggle(b, 1, 1);
        });
        assertNull(at(b, 1));
        assertEquals(0, recorded());
        assertEquals("Removed the breakpoint in lambda 1 on line 2.", status());

        // The same with a condition typed on it: that would be gone for good.
        FxTestSupport.runOnFx(() -> {
            debug.lambdaBreakpoints().toggle(b, 1, 1);
            b.getBreakpointManager().setCondition(1, "a > 1");
        });
        Breakpoint before = at(b, 1);
        FxTestSupport.runOnFx(() -> debug.lambdaBreakpoints().toggle(b, 1, 1));
        assertNull(at(b, 1));
        assertEquals("Breakpoint on line 2 of main.py removed." + HINT, status());
        undo();
        assertEquals(before, at(b, 1), "in its lambda, with its condition");

        // Toggle Breakpoint on a breakpoint whose lambdas were chosen: they are what it carries.
        FxTestSupport.runOnFx(() -> {
            b.getBreakpointManager().setCondition(1, "");
            b.getFocusedArea().moveTo(1, 0);
        });
        Breakpoint lambdasOnly = at(b, 1);
        run("debug.toggleBreakpoint");
        assertEquals(1, recorded());
        undo();
        assertEquals(lambdasOnly, at(b, 1));
    }

    @Test
    void aBreakpointIsNotPutBackOverOneSetSince() throws Exception {
        EditorBuffer b = open(script);
        set(b, detailed(1, "b = 2"));
        run("debug.toggleBreakpoint");
        Breakpoint since = Breakpoint.plain(1, "b = 2");
        set(b, since);

        undo();
        assertEquals("This can no longer be undone: Breakpoint on line 2 of main.py removed.", status());
        assertEquals(since, at(b, 1), "the breakpoint set since is left alone");
        assertEquals(0, recorded(), "and the entry is dropped");
    }

    @Test
    void aBreakpointIsNotPutBackOnALineTheTextMovedAwayFrom() throws Exception {
        EditorBuffer b = open(edited);
        set(b, detailed(1, "b = 2"));
        run("debug.toggleBreakpoint");
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "import os\n"));
        try {
            undo();
            assertEquals("This can no longer be undone: Breakpoint on line 2 of edited.py removed.", status());
            assertTrue(call(() -> b.getBreakpointManager().lines().isEmpty()), "line 2 is other code now");
        } finally {
            FxTestSupport.runOnFx(() -> b.getArea().deleteText(0, "import os\n".length()));
        }
    }

    @Test
    void aBreakpointIsNotPutBackOnceItsTabIsClosed() throws Exception {
        EditorBuffer b = open(script);
        set(b, detailed(1, "b = 2"));
        run("debug.toggleBreakpoint");
        close(script);

        undo();
        assertEquals("This can no longer be undone: Breakpoint on line 2 of main.py removed.", status());
        assertEquals(List.of(), stored(script), "nothing is written to the closed file's breakpoints");
    }

    @Test
    void aBreakpointOfAFileWithNoTabComesBackInTheStoredList() throws Exception {
        Breakpoint detailed = detailed(1, "  int a = 1;");
        Breakpoint plain = Breakpoint.plain(3, "  int c = 3;");
        FxTestSupport.runOnFx(() ->
                ops().breakpointMap().put(DebugCoordinator.key(other), new ArrayList<>(List.of(detailed, plain))));

        // A Project Map card's gutter, on a file with no tab.
        FxTestSupport.runOnFx(() -> debug.toggleCardBreakpoint(other, 3, OTHER));
        assertEquals(List.of(detailed), stored(other));
        assertEquals(0, recorded(), "a plain one records nothing");

        FxTestSupport.runOnFx(() -> debug.toggleCardBreakpoint(other, 1, OTHER));
        assertEquals(List.of(), stored(other));
        assertEquals("Breakpoint on line 2 of Other.java removed." + HINT, status());
        undo();
        assertEquals(List.of(detailed), stored(other));
        assertEquals("Breakpoint on line 2 of Other.java restored.", status());

        // Removed again, and a plain one set on the line since: it stays.
        FxTestSupport.runOnFx(() -> {
            debug.toggleCardBreakpoint(other, 1, OTHER);
            debug.toggleCardBreakpoint(other, 1, OTHER);
        });
        undo();
        assertEquals("This can no longer be undone: Breakpoint on line 2 of Other.java removed.", status());
        assertEquals(List.of(Breakpoint.plain(1, "  int a = 1;")), stored(other));
    }

    @Test
    void aStoredBreakpointIsNotPutBackOnceTheFileHasATab() throws Exception {
        Breakpoint detailed = detailed(1, "  int a = 1;");
        FxTestSupport.runOnFx(() -> {
            ops().breakpointMap().put(DebugCoordinator.key(other), new ArrayList<>(List.of(detailed)));
            debug.toggleCardBreakpoint(other, 1, OTHER);
        });
        assertEquals(1, recorded());
        EditorBuffer tab = open(other);
        try {
            undo();
            assertEquals("This can no longer be undone: Breakpoint on line 2 of Other.java removed.", status());
            assertNull(at(tab, 1), "the tab's breakpoints are the file's now");
        } finally {
            close(other);
        }
    }

    // --- 3. method breakpoints and field watchpoints ----------------------------------------------------------

    @Test
    void aMethodBreakpointWithAConditionComesBackWhereItWasInTheList() throws Exception {
        MemberBreakpoint first = MemberBreakpoint.method("demo.Shop", "open");
        MemberBreakpoint total = new MemberBreakpoint(
                MemberBreakpoint.Kind.METHOD, "demo.Shop", "total", "", "n > 3", "2", false, "Setup");
        MemberBreakpoint field = MemberBreakpoint.field("demo.Shop", "count", MemberBreakpoint.READ_WRITE)
                .withHitCondition("5");
        FxTestSupport.runOnFx(() -> debug.memberBreakpoints().addAll(List.of(first, total, field)));

        // The toggle at the caret, on a method that has one: it removes it.
        FxTestSupport.runOnFx(() -> debug.memberBreakpoints().toggle(MemberBreakpoint.method("demo.Shop", "total")));
        assertEquals(List.of(first, field), call(() -> debug.memberBreakpoints().all()));
        assertEquals("Removed the method breakpoint on Shop.total()." + HINT, status());
        undo();
        assertEquals(
                List.of(first, total, field),
                call(() -> debug.memberBreakpoints().all()));
        assertEquals("Method breakpoint on Shop.total() restored.", status());

        // Remove on its line and the Breakpoints list go through remove(): a field watchpoint, the last of the list.
        FxTestSupport.runOnFx(() -> debug.memberBreakpoints().remove(field));
        assertEquals("Removed the field watchpoint on Shop.count." + HINT, status());
        undo();
        assertEquals(
                List.of(first, total, field),
                call(() -> debug.memberBreakpoints().all()));
        assertEquals("Field watchpoint on Shop.count restored.", status());

        // A plain one: the toggle says it removed it, and records nothing.
        FxTestSupport.runOnFx(() -> debug.memberBreakpoints().toggle(MemberBreakpoint.method("demo.Shop", "open")));
        assertEquals("Removed the method breakpoint on Shop.open().", status());
        assertEquals(0, recorded());
    }

    @Test
    void aMethodBreakpointIsNotPutBackOverOneSetSince() throws Exception {
        MemberBreakpoint total = MemberBreakpoint.method("demo.Shop", "total").withCondition("n > 3");
        MemberBreakpoint since = MemberBreakpoint.method("demo.Shop", "total");
        FxTestSupport.runOnFx(() -> {
            debug.memberBreakpoints().add(total);
            debug.memberBreakpoints().remove(total);
            debug.memberBreakpoints().add(since);
        });
        undo();
        assertEquals("This can no longer be undone: Removed the method breakpoint on Shop.total().", status());
        assertEquals(List.of(since), call(() -> debug.memberBreakpoints().all()));
    }

    // --- 4. exception breakpoints -------------------------------------------------------------------------------

    @Test
    void anExceptionBreakpointRemovedInItsFormComesBackWhereItWas() throws Exception {
        ExceptionBreakpoint key = new ExceptionBreakpoint("python", "KeyError", "", true);
        ExceptionBreakpoint value = new ExceptionBreakpoint("python", "ValueError", "len(args) > 1", false);
        ExceptionBreakpoint os = new ExceptionBreakpoint("python", "OSError", "", true);
        FxTestSupport.runOnFx(() -> {
            ops().persistExceptionBreakpoints(List.of(key, value, os));
            debug.exceptions().exceptionEditor =
                    (initial, isNew) -> Optional.of(new ExceptionBreakpointDialog.Result(null, true)); // Remove
        });
        try {
            FxTestSupport.runOnFx(() -> debug.exceptions().editExceptionBreakpoint(1, "python"));
            assertEquals(List.of(key, os), call(() -> ops().exceptionBreakpoints()));
            assertEquals("Exception breakpoint “ValueError  if len(args) > 1” removed." + HINT, status());
            undo();
            assertEquals(List.of(key, value, os), call(() -> ops().exceptionBreakpoints()));
            assertEquals("Exception breakpoint “ValueError  if len(args) > 1” restored.", status());

            // Removed again, and typed again since: a second copy is not added.
            FxTestSupport.runOnFx(() -> {
                debug.exceptions().editExceptionBreakpoint(1, "python");
                ops().persistExceptionBreakpoints(List.of(key, os, value));
            });
            undo();
            assertEquals(
                    "This can no longer be undone: Exception breakpoint “ValueError  if len(args) > 1” removed.",
                    status());
            assertEquals(List.of(key, os, value), call(() -> ops().exceptionBreakpoints()));
        } finally {
            FxTestSupport.runOnFx(() -> debug.exceptions().exceptionEditor = null);
        }
    }

    // --- 8. values pinned to a line -------------------------------------------------------------------------------

    @Test
    void theValuesUnpinnedFromALineArePinnedAgainInTheirOrder() throws Exception {
        EditorBuffer b = open(script);
        FxTestSupport.runOnFx(() -> {
            b.getPinManager().toggle(2, "c");
            b.getPinManager().toggle(2, "a + b");
            b.getFocusedArea().moveTo(2, 0);
        });
        run("debug.unpinLine");
        assertEquals(List.of(), call(() -> b.getPinManager().expressions(2)));
        assertEquals("Unpinned the values on line 3." + HINT, status());
        undo();
        assertEquals(List.of("c", "a + b"), call(() -> b.getPinManager().expressions(2)));
        assertEquals("Pinned the values on line 3 again.", status());

        // Unpinned again, and a value pinned to the line since: it stays alone.
        run("debug.unpinLine");
        FxTestSupport.runOnFx(() -> b.getPinManager().toggle(2, "d"));
        undo();
        assertEquals("This can no longer be undone: Unpinned the values on line 3.", status());
        assertEquals(List.of("d"), call(() -> b.getPinManager().expressions(2)));
    }

    @Test
    void oneValueUnpinnedComesBackInItsPlaceOnItsLine() throws Exception {
        EditorBuffer b = open(script);
        FxTestSupport.runOnFx(() -> {
            b.getPinManager().toggle(2, "c");
            b.getPinManager().toggle(2, "len(typed)"); // as typed in Pin Value to Line's prompt
            b.getPinManager().toggle(2, "a + b");
            debug.pinnedValues().toggle(b, 2, "len(typed)"); // the command, on a value pinned there: it unpins it
        });
        assertEquals(List.of("c", "a + b"), call(() -> b.getPinManager().expressions(2)));
        assertEquals("Unpinned len(typed) from line 3." + HINT, status());

        undo();
        assertEquals(
                List.of("c", "len(typed)", "a + b"),
                call(() -> b.getPinManager().expressions(2)));
        assertEquals("Pinned len(typed) to line 3 again.", status());

        FxTestSupport.runOnFx(() -> debug.pinnedValues().toggle(b, 2, "d")); // pinning records nothing
        assertEquals(0, recorded());
        assertEquals("Pinned d to line 3", status());
    }

    @Test
    void oneValueUnpinnedIsNotPinnedAgainOnceItIsThereOrItsLineIsGone() throws Exception {
        EditorBuffer b = open(script);
        // Pinned there again by hand since: nothing to put back, and no second copy.
        FxTestSupport.runOnFx(() -> {
            b.getPinManager().toggle(2, "c");
            b.getPinManager().toggle(2, "len(typed)");
            debug.pinnedValues().toggle(b, 2, "len(typed)");
            debug.pinnedValues().toggle(b, 2, "len(typed)");
        });
        assertEquals(1, recorded(), "the pin is not a removal");
        undo();
        assertEquals("This can no longer be undone: Unpinned len(typed) from line 3.", status());
        assertEquals(List.of("c", "len(typed)"), call(() -> b.getPinManager().expressions(2)));

        // Its tab is closed.
        FxTestSupport.runOnFx(() -> debug.pinnedValues().toggle(b, 2, "c"));
        close(script);
        undo();
        assertEquals("This can no longer be undone: Unpinned c from line 3.", status());

        // The line reads something else now.
        EditorBuffer e = open(edited);
        FxTestSupport.runOnFx(() -> {
            e.getPinManager().clear();
            e.getPinManager().toggle(2, "c");
            e.getPinManager().toggle(2, "len(typed)");
            debug.pinnedValues().toggle(e, 2, "len(typed)");
            e.getArea().insertText(0, "import os\n");
        });
        try {
            undo();
            assertEquals("This can no longer be undone: Unpinned len(typed) from line 3.", status());
            assertEquals(List.of(), call(() -> e.getPinManager().expressions(2)), "line 3 is other code now");
            assertEquals(List.of("c"), call(() -> e.getPinManager().expressions(3)), "its line keeps what it has");
        } finally {
            FxTestSupport.runOnFx(() -> {
                e.getArea().deleteText(0, "import os\n".length());
                e.getPinManager().clear();
            });
        }
    }

    // --- 9. a bookmark with a mnemonic ------------------------------------------------------------------------------

    private Map<String, List<Bookmark>> bookmarks() {
        BookmarkCoordinator.Ops ops = FxTestSupport.field(fx.controller.bookmarkCoordinator, "ops");
        return ops.bookmarks();
    }

    private List<Integer> storedBookmarkLines() {
        return call(() -> bookmarks().getOrDefault(script.toString(), List.of()).stream()
                .map(Bookmark::line)
                .toList());
    }

    private Bookmark bookmarkAt(EditorBuffer b, int line) {
        return call(() -> b.getBookmarkManager().snapshot().stream()
                .filter(bm -> bm.line() == line)
                .findFirst()
                .orElse(null));
    }

    @Test
    void aBookmarkWithAMnemonicComesBackWithItWhereItWasInTheBookmarksList() throws Exception {
        EditorBuffer b = open(script);
        FxTestSupport.runOnFx(() -> {
            b.getBookmarkManager().add(0, "");
            b.getBookmarkManager().add(new Bookmark(3, "", "", "7"));
            b.getBookmarkManager().add(4, "");
            fx.controller.bookmarkCoordinator.persistBookmarks(b);
            // The user's order in the Bookmarks window: the one with the mnemonic first.
            List<Bookmark> mine = new ArrayList<>(bookmarks().get(script.toString()));
            mine.add(0, mine.remove(1));
            bookmarks().put(script.toString(), mine);
            b.getFocusedArea().moveTo(3, 0);
        });
        assertEquals(List.of(3, 0, 4), storedBookmarkLines());
        Bookmark before = bookmarkAt(b, 3);

        try (ConfirmAnswers questions = ConfirmAnswers.cancelling()) {
            run("bookmarks.toggle");
            assertEquals(List.of(), questions.asked(), "no note: no question");
        }
        assertNull(bookmarkAt(b, 3));
        assertEquals("Bookmark 7 on line 4 removed." + HINT, status());

        undo();
        assertEquals(before, bookmarkAt(b, 3), "with its mnemonic");
        assertEquals("7", before.mnemonic());
        assertEquals(List.of(3, 0, 4), storedBookmarkLines(), "and its place in the list");
        assertEquals("Bookmark 7 on line 4 restored.", status());

        // A plain bookmark: the same key puts it back, so nothing is recorded.
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(4, 0));
        run("bookmarks.toggle");
        assertNull(bookmarkAt(b, 4));
        assertEquals(0, recorded());
    }

    @Test
    void aBookmarkIsNotPutBackOnceItsMnemonicNamesAnotherOne() throws Exception {
        EditorBuffer b = open(script);
        FxTestSupport.runOnFx(() -> {
            b.getBookmarkManager().add(new Bookmark(3, "", "", "7"));
            b.getFocusedArea().moveTo(3, 0);
        });
        run("bookmarks.toggle");
        FxTestSupport.runOnFx(() -> b.getBookmarkManager().add(new Bookmark(0, "", "", "7")));

        undo();
        assertEquals("This can no longer be undone: Bookmark 7 on line 4 removed.", status());
        assertNull(bookmarkAt(b, 3));
        assertEquals("7", bookmarkAt(b, 0).mnemonic(), "the bookmark that has the mnemonic now keeps it");
    }

    // --- 5. key bindings ------------------------------------------------------------------------------------------

    private KeymapManager keymap() {
        return FxTestSupport.field(fx.controller, "keymap");
    }

    private String boundTo(String chord) {
        return call(() -> keymap().commandFor(chord));
    }

    @Test
    void aRemovedKeyBindingIsBoundAgain() throws Exception {
        EditorSettingsCoordinator settings = fx.controller.editorSettings;
        FxTestSupport.runOnFx(() -> settings.rebindShortcut("edit.selectAll", "C-M-S-F9"));
        Map<String, String> before = call(settings::currentKeybindings);
        assertEquals("edit.selectAll", boundTo("C-M-S-F9"));

        FxTestSupport.runOnFx(() -> settings.clearShortcut("edit.selectAll")); // Remove on the Keymaps page
        assertNull(boundTo("C-M-S-F9"));
        assertEquals("Removed the shortcut of Edit: Select All." + HINT, status());

        undo();
        assertEquals("edit.selectAll", boundTo("C-M-S-F9"));
        assertEquals(before, call(settings::currentKeybindings));
        assertEquals("Shortcut of Edit: Select All restored.", status());
    }

    @Test
    void aKeyBindingIsNotRestoredOnceItsKeyRunsAnotherCommand() throws Exception {
        EditorSettingsCoordinator settings = fx.controller.editorSettings;
        FxTestSupport.runOnFx(() -> {
            settings.rebindShortcut("edit.selectAll", "C-M-S-F9");
            settings.clearShortcut("edit.selectAll");
            settings.rebindShortcut("edit.copy", "C-M-S-F9");
        });
        undo();
        assertEquals("This can no longer be undone: Removed the shortcut of Edit: Select All.", status());
        assertEquals("edit.copy", boundTo("C-M-S-F9"), "the key keeps the command it was given since");
    }

    @Test
    void aResetThatDiscardsTheUsersChordIsTakenBackAndOneThatDiscardsNothingRecordsNothing() throws Exception {
        EditorSettingsCoordinator settings = fx.controller.editorSettings;
        FxTestSupport.runOnFx(() -> settings.resetShortcut("edit.selectAll"));
        assertEquals(0, recorded(), "it was on its default already");

        FxTestSupport.runOnFx(() -> settings.rebindShortcut("edit.selectAll", "C-M-S-F9"));
        Map<String, String> before = call(settings::currentKeybindings);
        FxTestSupport.runOnFx(() -> settings.resetShortcut("edit.selectAll"));
        assertNull(boundTo("C-M-S-F9"));
        assertEquals("Reset shortcut for Edit: Select All." + HINT, status());
        undo();
        assertEquals("edit.selectAll", boundTo("C-M-S-F9"));
        assertEquals(before, call(settings::currentKeybindings), "its default is suppressed again, as it was");
    }

    // --- 1. a watch, through the window's Debug panel ----------------------------------------------------------------

    @Test
    void aRemovedWatchComesBackThroughTheCommandAndIsSaved() throws Exception {
        DebugPanel panel = call(debug::panel);
        FxTestSupport.runOnFx(() -> {
            panel.setWatches(List.of("a", "a + b", "c"));
            panel.setWatchPaused("a + b", true);
            FxTestSupport.call(panel, "removeWatch", new Class<?>[] {String.class}, "a + b");
        });
        assertEquals(List.of("a", "c"), call(() -> ops().debugWatches()));
        assertEquals("Watch “a + b” removed." + HINT, status());

        undo();
        assertEquals(List.of("a", "a + b", "c"), call(panel::getWatches));
        assertEquals(List.of("a + b"), call(panel::getPausedWatches));
        assertEquals(List.of("a", "a + b", "c"), call(() -> ops().debugWatches()), "saved with the window");
        assertEquals("Watch “a + b” restored.", status());
        FxTestSupport.runOnFx(() -> panel.setWatches(List.of()));
    }

    // --- the window ------------------------------------------------------------------------------------------------

    @Test
    void theCommandIsInTheEditMenuAndHasNoKeyInAnyKeymap() {
        assertTrue(MenuBarModel.menus(false).stream()
                .anyMatch(menu -> menu.entries().contains("edit.undoRemoval")));
        assertFalse(KeymapManager.boundByAnyKeymap("edit.undoRemoval", false));
        assertFalse(KeymapManager.boundByAnyKeymap("edit.undoRemoval", true));
    }

    @Test
    void closingTheWindowForgetsItsRemovals() throws Exception {
        FxWindowFixture second = FxWindowFixture.create();
        try {
            RemovalUndo undo = second.controller.removalUndo;
            FxTestSupport.runOnFx(() -> undo.removed("Watch “a” removed.", "Watch “a” restored.", () -> true));
            assertEquals(1, call(undo::size));
            second.dispose();
            assertEquals(0, call(undo::size), "a closed window keeps no restore, and so nothing a restore holds");
        } finally {
            second.dispose();
        }
    }
}
