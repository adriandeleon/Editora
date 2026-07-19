package com.editora.snippet;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mirrored snippet field's edit + its mirror are one undo unit (#415): typing into a field whose stop appears
 * twice ({@code $1 = $1;}) and pressing Ctrl-Z once must revert cleanly to the placeholder, not leave a
 * half-reverted {@code x = ;} with the session cancelled.
 */
@Tag("fx")
class SnippetSessionMirrorUndoFxTest {

    @BeforeAll
    static void boot() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another FX test booted the toolkit in this JVM
        }
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> body) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(body.call());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX task did not complete");
        if (err.get() != null) {
            throw new AssertionError(err.get());
        }
        return out.get();
    }

    @Test
    void typingIntoAMirroredFieldIsUndoneInOneStep() throws Exception {
        boolean ok = onFx(() -> {
            CodeArea area = new CodeArea();
            ParsedSnippet parsed = SnippetParser.parse("$1 = $1;", name -> null);
            SnippetSession session = new SnippetSession(area, parsed, 0, 0, "");
            assertEquals(" = ;", area.getText(), "the placeholder text after expansion");

            // The field is selected/at the caret; type 'x' the way the editor's KEY_TYPED filter does.
            boolean handled = session.replaceInActiveField(
                    area.getSelection().getStart(), area.getSelection().getEnd(), "x");
            assertTrue(handled, "a mirrored-field edit is handled atomically");
            assertEquals("x = x;", area.getText(), "both occurrences mirror");

            area.undo();
            // The fix: ONE undo reverts the primary AND its mirror together — not the old half-reverted "x = ;".
            assertEquals(" = ;", area.getText(), "one undo reverts the field and its mirror together");
            // Undo ends the session cleanly (the document is fully reverted, just no longer tracked) rather than
            // leaving it in a half-consistent state.
            assertFalse(session.isActive(), "undo ends the session cleanly");
            return true;
        });
        assertTrue(ok);
    }

    @Test
    void aSecondTypedCharAlsoMirrorsAtomically() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSession session = new SnippetSession(area, SnippetParser.parse("$1 = $1;", n -> null), 0, 0, "");
            session.replaceInActiveField(
                    area.getSelection().getStart(), area.getSelection().getEnd(), "a");
            assertEquals("a = a;", area.getText(), "first char mirrors");
            // caret now sits after 'a' in the primary; type 'b' at the caret (no selection) — still mirrors both.
            int caret = area.getCaretPosition();
            assertTrue(session.replaceInActiveField(caret, caret, "b"));
            assertEquals("ab = ab;", area.getText(), "a caret-position (non-selection) edit also mirrors atomically");
            return null;
        });
    }

    @Test
    void anUnmirroredFieldIsNotIntercepted() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea();
            // $2 appears once → no mirror → the reactive path already yields one undo unit, so we don't intercept.
            SnippetSession session = new SnippetSession(area, SnippetParser.parse("$1 $2", n -> null), 0, 0, "");
            // active is $1 (single occurrence) → not intercepted.
            assertFalse(
                    session.replaceInActiveField(
                            area.getSelection().getStart(), area.getSelection().getEnd(), "x"),
                    "a single-occurrence field is left to the normal insert path");
            return null;
        });
    }
}
