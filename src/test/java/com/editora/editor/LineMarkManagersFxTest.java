package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import com.editora.editor.BreakpointManager.Live;
import com.editora.editor.BreakpointManager.LiveState;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bookmarks and breakpoints on a live document: toggling and editing them, how they follow edits, come back
 * with an undo, re-anchor to their line's text on restore, and survive narrowing.
 */
@Tag("fx")
class LineMarkManagersFxTest {

    private static final String TEXT = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static CodeArea area(String text) {
        CodeArea area = new CodeArea();
        area.replaceText(text);
        return area;
    }

    // ---- breakpoints -------------------------------------------------------------------------------------

    @Test
    void aBreakpointCanBeConditionalALogpointDisabledAndCleared() throws Exception {
        EditorFx.onFx(() -> {
            BreakpointManager manager = new BreakpointManager(area(TEXT));
            AtomicInteger changes = new AtomicInteger();
            manager.setOnChanged(changes::incrementAndGet);
            assertNull(manager.styleClasses(2), "no breakpoint, no glyph classes");
            assertNull(manager.tooltip(2));

            assertTrue(manager.toggle(2));
            assertEquals("gamma", manager.get(2).lineText(), "the line's text is captured for re-anchoring");
            assertNull(manager.styleClasses(2), "a plain breakpoint");
            manager.setCondition(2, "i > 3");
            assertEquals("conditional", manager.styleClasses(2));
            manager.setLogMessage(2, "at {i}");
            assertEquals("logpoint", manager.styleClasses(2), "a logpoint wins over a condition");
            manager.setEnabled(2, false);
            manager.setEnabled(2, false);
            assertEquals("disabled", manager.styleClasses(2));
            assertEquals(4, changes.get(), "each real change is reported once");

            manager.setCondition(2, null);
            manager.setLogMessage(2, null);
            assertEquals("", manager.get(2).condition());
            assertEquals("", manager.get(2).logMessage());
            manager.setCondition(5, "x"); // no breakpoint on that line: nothing to edit
            manager.setLogMessage(5, "x");
            manager.setEnabled(5, false);
            assertFalse(manager.isBreakpoint(5));
            assertEquals(6, changes.get());

            manager.add(null);
            manager.add(new Breakpoint(4, "", "", true, "epsilon"));
            assertEquals(List.of(2, 4), List.copyOf(manager.lines()));
            manager.remove(4);
            manager.remove(4); // already gone
            assertEquals(List.of(2), List.copyOf(manager.lines()));
            assertEquals(8, changes.get());

            manager.clear();
            assertTrue(manager.lines().isEmpty());
            manager.clear(); // nothing left: not reported again
            assertEquals(9, changes.get());
            manager.setOnChanged(null);
            assertTrue(manager.toggle(0), "with no listener a toggle still works");
        });
    }

    @Test
    void aLiveDebugSessionMarksUnboundAndRejectedBreakpoints() throws Exception {
        EditorFx.onFx(() -> {
            BreakpointManager manager = new BreakpointManager(area(TEXT));
            List<Collection<Integer>> repaints = new ArrayList<>();
            manager.setOnLinesRepaint(repaints::add);
            manager.toggle(1);
            manager.toggle(2);
            manager.toggle(3);
            manager.toggle(4);
            manager.setCondition(3, "ok");
            manager.setEnabled(4, false);
            assertNull(manager.live(1), "no session: a breakpoint just looks like itself");

            manager.setLive(Map.of(
                    1, new Live(LiveState.VERIFIED, "bound"),
                    2, new Live(LiveState.REJECTED, "No executable code here"),
                    4, new Live(LiveState.VERIFIED, "")));
            assertNull(manager.styleClasses(1), "verified: drawn solid");
            assertEquals("bound", manager.tooltip(1));
            assertEquals("unverified rejected", manager.styleClasses(2));
            assertEquals("No executable code here", manager.tooltip(2));
            assertEquals("conditional unverified", manager.styleClasses(3), "the adapter has not answered yet");
            assertEquals(LiveState.PENDING, manager.live(3).state());
            assertNull(manager.tooltip(3), "nothing to say about an unanswered one");
            assertEquals("disabled", manager.styleClasses(4), "a disabled breakpoint is never sent");
            assertNull(manager.live(4));
            assertNull(manager.live(0));

            int before = repaints.size();
            manager.setLive(Map.of(
                    1, new Live(LiveState.VERIFIED, "bound"),
                    2, new Live(LiveState.REJECTED, "No executable code here"),
                    4, new Live(LiveState.VERIFIED, "")));
            assertEquals(before, repaints.size(), "the same answer repaints nothing");
            manager.setLive(null);
            assertEquals(before + 1, repaints.size());
            assertNull(manager.styleClasses(2));
            manager.setOnLinesRepaint(null);
            manager.setLive(Map.of());
        });
    }

    @Test
    void theAdapterCanMoveABreakpointToTheLineItBound() throws Exception {
        EditorFx.onFx(() -> {
            BreakpointManager manager = new BreakpointManager(area(TEXT));
            manager.toggle(1);
            manager.toggle(3);
            manager.setCondition(1, "n == 0");
            assertFalse(manager.moveDocumentLine(0, 2), "nothing on line 0 to move");
            assertFalse(manager.moveDocumentLine(1, 3), "the target line already has one");
            assertFalse(manager.moveDocumentLine(1, 99), "past the end");
            assertFalse(manager.moveDocumentLine(1, -1));

            assertTrue(manager.moveDocumentLine(1, 2));
            assertEquals(List.of(2, 3), List.copyOf(manager.lines()));
            Breakpoint moved = manager.get(2);
            assertEquals(2, moved.line());
            assertEquals("gamma", moved.lineText(), "re-captured from its new line");
            assertEquals("n == 0", moved.condition(), "and keeps its condition");
        });
    }

    @Test
    void breakpointsFollowEditsAndComeBackWhenADeletedLineReturns() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = area(TEXT);
            BreakpointManager manager = new BreakpointManager(area);
            List<String> changes = new ArrayList<>();
            manager.setOnChanged(() -> changes.add(manager.isEditDriven() ? "edit" : "user"));
            manager.toggle(3);
            manager.setCondition(3, "d > 0");

            area.insertText(0, "intro\n");
            assertEquals(List.of(4), List.copyOf(manager.lines()), "pushed down with its line");

            // Typing on the marked line changes its text only: reported once, picked up by the next snapshot.
            int deltaStart = area.getText().indexOf("delta");
            area.insertText(deltaStart + 5, "!");
            area.insertText(deltaStart + 6, "!");
            assertEquals("delta!!", manager.snapshot().get(0).lineText());
            assertEquals(List.of("user", "user", "edit", "edit"), changes);

            // Cutting the whole line drops the breakpoint...
            int lineStart = area.getAbsolutePosition(4, 0);
            String cut = area.getText(lineStart, lineStart + "delta!!\n".length());
            area.deleteText(lineStart, lineStart + cut.length());
            assertTrue(manager.lines().isEmpty());
            // ...typing does not bring it back, pasting the same line elsewhere does — condition and all.
            area.insertText(0, "x");
            assertTrue(manager.lines().isEmpty());
            area.insertText(area.getAbsolutePosition(1, 0), cut);
            assertEquals(List.of(1), List.copyOf(manager.lines()));
            assertEquals("d > 0", manager.get(1).condition());

            // A restore replaces the state silently and forgets what was dropped before it.
            int reported = changes.size();
            assertFalse(manager.restore(List.of(new Breakpoint(1, "", "", true, "delta!!"))));
            assertEquals(reported, changes.size(), "restoring is not a change to persist");
        });
    }

    @Test
    void restoredBreakpointsReanchorToTheirLineText() throws Exception {
        EditorFx.onFx(() -> {
            BreakpointManager manager = new BreakpointManager(area(TEXT));
            // Saved against an older copy of the file: "delta" was on line 1, "zeta" on line 2, and one more
            // whose text no longer exists anywhere.
            boolean moved = manager.restore(List.of(
                    new Breakpoint(1, "", "", true, "delta"),
                    new Breakpoint(2, "", "", true, "zeta"),
                    new Breakpoint(0, "", "", true, "vanished"),
                    new Breakpoint(-1, "", "", true, "folder marker")));
            assertTrue(moved, "at least one line was healed, so the caller persists the new indices");
            assertEquals("delta", manager.get(3).lineText());
            assertEquals("zeta", manager.get(5).lineText());
            assertTrue(manager.isBreakpoint(0), "content gone: it stays on its stored line");
            assertEquals(3, manager.lines().size());

            assertFalse(manager.restore(null));
            assertTrue(manager.lines().isEmpty());
            assertFalse(manager.restore(List.of(new Breakpoint(1, "", "", true, "beta"))), "already in place");

            // More breakpoints than lines: the surplus is dropped rather than stacked on one line.
            BreakpointManager tiny = new BreakpointManager(area("only"));
            tiny.restore(List.of(new Breakpoint(0, "", "", true, "a"), new Breakpoint(0, "", "", true, "b")));
            assertEquals(1, tiny.lines().size());

            NavigableMap<Integer, Breakpoint> none =
                    BreakpointManager.reanchor(List.of(new Breakpoint(0, "", "", true, "a")), 0, line -> "", 10);
            assertTrue(none.isEmpty(), "an empty document holds no breakpoints");
        });
    }

    @Test
    void narrowingKeepsWholeDocumentBreakpointsForTheDebugger() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = area(TEXT);
            BreakpointManager manager = new BreakpointManager(area);
            AtomicInteger changes = new AtomicInteger();
            manager.toggle(0);
            manager.toggle(3);
            manager.toggle(5);
            manager.setOnChanged(changes::incrementAndGet);
            manager.widen(() -> {}); // not narrowed: nothing to release
            assertEquals(0, changes.get());

            int start = TEXT.indexOf("gamma");
            int end = TEXT.indexOf("\nzeta");
            manager.narrow(start, end, () -> area.replaceText(TEXT.substring(start, end)));
            assertEquals(0, changes.get(), "narrowing moves nothing in the file");
            assertEquals(2, manager.regionFirstLine());
            assertEquals(List.of(1), List.copyOf(manager.lines()), "region-relative: delta is its line 1");
            assertEquals(
                    List.of(0, 3, 5),
                    manager.documentSnapshot().stream().map(Breakpoint::line).toList(),
                    "the debugger still gets whole-document lines, including those outside the region");

            manager.setLive(Map.of(3, new Live(LiveState.REJECTED, "nope")));
            assertEquals("unverified rejected", manager.styleClasses(1), "live state is looked up by document line");
            assertTrue(manager.moveDocumentLine(3, 4), "document line 4 is the region's line 2");
            assertEquals(List.of(2), List.copyOf(manager.lines()));
            assertFalse(manager.moveDocumentLine(4, 0), "document line 0 is outside the region");

            manager.widen(() -> area.replaceText(TEXT));
            assertEquals(0, manager.regionFirstLine());
            assertEquals(List.of(0, 4, 5), List.copyOf(manager.lines()));
            assertEquals(2, changes.get(), "the move, and the widen that put the rest back");
            assertEquals(manager.snapshot(), manager.documentSnapshot());
        });
    }

    // ---- bookmarks ---------------------------------------------------------------------------------------

    @Test
    void bookmarksCycleCarryNotesAndClear() throws Exception {
        EditorFx.onFx(() -> {
            BookmarkManager manager = new BookmarkManager(area(TEXT));
            AtomicInteger changes = new AtomicInteger();
            manager.setOnChanged(changes::incrementAndGet);
            assertNull(manager.next(0), "no bookmarks: nowhere to go");
            assertNull(manager.previous(0));
            manager.clear();
            assertEquals(0, changes.get());

            manager.add(1, "check this");
            manager.add(4, null);
            assertEquals("check this", manager.snapshot().get(0).note());
            assertEquals("", manager.snapshot().get(1).note());
            assertEquals("epsilon", manager.snapshot().get(1).lineText());
            assertEquals(4, manager.next(1));
            assertEquals(1, manager.next(4), "wraps to the first");
            assertEquals(1, manager.previous(4));
            assertEquals(4, manager.previous(1), "wraps to the last");
            assertEquals(4, manager.previous(0));

            manager.setNote(4, "later");
            manager.setNote(4, null);
            manager.setNote(2, "no bookmark here");
            assertEquals("", manager.snapshot().get(1).note());
            assertFalse(manager.isBookmarked(2));

            manager.remove(1);
            manager.remove(1);
            assertEquals(List.of(4), List.copyOf(manager.lines()));
            int before = changes.get();
            manager.clear();
            assertTrue(manager.lines().isEmpty());
            assertEquals(before + 1, changes.get());
            manager.setOnChanged(null);
            manager.setOnLinesRepaint(null);
            assertTrue(manager.toggle(0));
            assertFalse(manager.toggle(0), "the second toggle removes it");
        });
    }

    @Test
    void restoredBookmarksReanchorAndNarrowingHoldsTheOthers() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = area(TEXT);
            BookmarkManager manager = new BookmarkManager(area);
            assertTrue(manager.restore(List.of(
                    new Bookmark(0, "", "gamma", ""),
                    new Bookmark(1, "", "beta", ""),
                    new Bookmark(1, "", "", ""),
                    new Bookmark(Bookmark.FOLDER_LINE, "", "a folder", ""))));
            assertTrue(manager.isBookmarked(2), "moved to the line that reads gamma");
            assertTrue(manager.isBookmarked(1));
            assertEquals(3, manager.lines().size(), "the text-less one stepped aside instead of sharing line 1");
            assertFalse(manager.restore(null));
            assertFalse(manager.restore(List.of(new Bookmark(3, "", "delta", ""))));

            BookmarkManager tiny = new BookmarkManager(area("only"));
            tiny.restore(List.of(new Bookmark(0, "", "a", ""), new Bookmark(0, "", "b", "")));
            assertEquals(1, tiny.lines().size(), "one line cannot hold two bookmarks");
            assertTrue(BookmarkManager.reanchor(List.of(new Bookmark(0, "", "a", "")), 0, line -> "", 10)
                    .isEmpty());

            AtomicInteger changes = new AtomicInteger();
            manager.toggle(0);
            manager.setOnChanged(changes::incrementAndGet);
            manager.widen(() -> {});
            int start = TEXT.indexOf("gamma");
            int end = TEXT.indexOf("\nzeta");
            manager.narrow(start, end, () -> area.replaceText(TEXT.substring(start, end)));
            assertEquals(List.of(1), List.copyOf(manager.lines()), "delta, as the region's line 1");
            manager.clear();
            assertEquals(1, changes.get(), "clearing while narrowed also drops the held ones");
            manager.widen(() -> area.replaceText(TEXT));
            assertTrue(manager.lines().isEmpty());

            // An edit on a bookmarked line re-captures its text for the next snapshot.
            manager.toggle(1);
            area.insertText(area.getAbsolutePosition(1, 0) + 4, "s");
            area.insertText(area.getAbsolutePosition(1, 0) + 5, "!");
            assertEquals("betas!", manager.snapshot().get(0).lineText());
        });
    }
}
