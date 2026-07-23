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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transform occurrence tracks the field live: it renders correctly at expansion, and updates as the user
 * types into the value-defining field.
 *
 * <p>Typed characters go through the atomic {@link SnippetSession#replaceInActiveField} path (which the
 * editor's KEY_TYPED filter uses), so those assertions are synchronous. The last test exercises the
 * <em>reactive</em> path a paste/backspace takes — which defers a pulse when a mirror precedes the field —
 * and asserts across a second FX turn.
 */
@Tag("fx")
class SnippetTransformSessionFxTest {

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

    private static CodeArea start(SnippetSession[] out, String body) {
        CodeArea area = new CodeArea();
        out[0] = new SnippetSession(area, SnippetParser.parse(body, n -> null), 0, 0, "");
        return area;
    }

    /** Types into the selected value field the way the editor's KEY_TYPED filter does (atomic path). */
    private static void typeAtomic(SnippetSession s, CodeArea area, String text) {
        boolean handled = s.replaceInActiveField(
                area.getSelection().getStart(), area.getSelection().getEnd(), text);
        assertTrue(handled, "a mirrored field edit is handled atomically");
    }

    @Test
    void suffixTransformRendersAtExpansionAndUpdatesLive() throws Exception {
        onFx(() -> {
            SnippetSession[] s = new SnippetSession[1];
            // the shape of the bundled PowerShell foreach-item: value first, transform second
            CodeArea area = start(s, "${1:collection} ${1/(.*)/$1Item/}");
            assertEquals("collection collectionItem", area.getText(), "transform is applied at expansion");

            typeAtomic(s[0], area, "users");
            assertEquals("users usersItem", area.getText(), "the transform tracks the typed value");
            return null;
        });
    }

    @Test
    void transformBeforeTheValueFieldAlsoTracks() throws Exception {
        onFx(() -> {
            SnippetSession[] s = new SnippetSession[1];
            // transform occurrence emitted BEFORE the value field — the editable field is the second one
            CodeArea area = start(s, "${1/(.*)/$1Item/} in ${1:collection}");
            assertEquals("collectionItem in collection", area.getText());

            typeAtomic(s[0], area, "users");
            assertEquals("usersItem in users", area.getText(), "the leading transform tracks the later field");
            return null;
        });
    }

    @Test
    void aLengthChangingTransformShiftsFollowingText() throws Exception {
        onFx(() -> {
            SnippetSession[] s = new SnippetSession[1];
            CodeArea area = start(s, "${1:fooBar}=${1/(.*)/${1:/snakecase}/};");
            assertEquals("fooBar=foo_bar;", area.getText(), "snakecase applied at expansion");

            typeAtomic(s[0], area, "bazQux");
            assertEquals("bazQux=baz_qux;", area.getText(), "the transform re-renders at the new length");
            return null;
        });
    }

    @Test
    void sanitisingTransformProducesValidText() throws Exception {
        onFx(() -> {
            SnippetSession[] s = new SnippetSession[1];
            // the shape of the bundled PowerShell splat: $${1/[^\w]/_/g}Params
            CodeArea area = start(s, "$${1/[^\\w]/_/g}Params = ${1:name}");
            assertEquals("$nameParams = name", area.getText());

            typeAtomic(s[0], area, "Get-Item");
            assertEquals("$Get_ItemParams = Get-Item", area.getText(), "non-word chars sanitised in the mirror only");
            return null;
        });
    }

    @Test
    void oneUndoRevertsTheFieldAndItsTransformTogether() throws Exception {
        onFx(() -> {
            SnippetSession[] s = new SnippetSession[1];
            // surrounding literals keep the atomic field-replace from spanning the whole document, so the raw
            // CodeArea's default undo manager doesn't merge it with the initial expansion (the editor's own
            // UndoMerge boundaries separate them regardless)
            CodeArea area = start(s, "[${1:collection} ${1/(.*)/$1Item/}]");
            assertEquals("[collection collectionItem]", area.getText());
            typeAtomic(s[0], area, "users");
            assertEquals("[users usersItem]", area.getText());

            area.undo();
            assertEquals("[collection collectionItem]", area.getText(), "one undo reverts the field and its transform");
            return null;
        });
    }

    @Test
    void reactivePasteBeforeTheFieldDefersButStillMirrors() throws Exception {
        SnippetSession[] s = new SnippetSession[1];
        AtomicReference<CodeArea> ref = new AtomicReference<>();
        // A paste is not a typed char, so it takes the reactive path, which defers a pulse because the
        // transform occurrence precedes the field. It must not corrupt the paste's caret, and must mirror.
        onFx(() -> {
            CodeArea area = start(s, "${1/(.*)/$1Item/} in ${1:collection}");
            ref.set(area);
            // replaceSelection is the non-atomic edit path (like a paste over the selected field).
            area.replaceSelection("users");
            return null;
        });
        // A second FX turn — by now the deferred mirror has run.
        String text = onFx(() -> ref.get().getText());
        assertEquals("usersItem in users", text, "the deferred reactive mirror applied the transform");
    }
}
