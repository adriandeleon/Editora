package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sticky scroll end to end: the pinned lines are produced from the buffer's real fold regions and its real
 * viewport, which is the half {@code StickyScrollTest} cannot cover — that test knows the decision is
 * right given a set of regions, this one knows the buffer hands it the right regions and the right first
 * visible line, and that turning the feature off actually clears the bar.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StickyScrollFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A class with a long method, so scrolling into the body leaves both headers above the viewport. */
    private static String source() {
        StringBuilder sb = new StringBuilder("class Outer {\n    void body() {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("        int v").append(i).append(" = ").append(i).append(";\n");
        }
        return sb.append("    }\n}\n").toString();
    }

    private EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(source());
            b.setStickyScrollEnabled(true);
            // Fold regions normally land on a 250 ms debounce after the text change. Waiting on real time
            // in a test is a flake waiting to happen, so force exactly what the debounce would have done.
            b.getFoldManager().recompute();
            return b;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> pinned(EditorBuffer b) throws Exception {
        return (List<Integer>) FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "stickyLines"));
    }

    /** Drives the buffer's own update path for a viewport starting at {@code firstVisible}. */
    private static List<Integer> pinnedFor(EditorBuffer b, int firstVisible) throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                java.lang.reflect.Method m = EditorBuffer.class.getDeclaredMethod("stickyLinesFor", int.class);
                m.setAccessible(true);
                m.invoke(b, firstVisible);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        return pinned(b);
    }

    @Test
    void deepInAMethodBothEnclosingHeadersArePinned() throws Exception {
        EditorBuffer b = buffer();
        assertEquals(List.of(0, 1), pinnedFor(b, 100), "the class and the method are both off screen above");
    }

    @Test
    void atTheTopOfTheFileNothingIsPinned() throws Exception {
        EditorBuffer b = buffer();
        assertTrue(pinnedFor(b, 0).isEmpty());
    }

    @Test
    void turningItOffClearsTheBar() throws Exception {
        EditorBuffer b = buffer();
        pinnedFor(b, 100);
        assertFalse(pinnedFor(b, 100).isEmpty());
        FxTestSupport.runOnFx(() -> b.setStickyScrollEnabled(false));
        assertTrue(pinned(b).isEmpty(), "disabling must clear what is pinned, not leave it frozen on screen");
    }

    @Test
    void aBufferWithNoNestingPinsNothing() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setLanguageOverride("plaintext");
            x.setContent("just\nsome\nprose\n".repeat(50));
            x.setStickyScrollEnabled(true);
            x.getFoldManager().recompute();
            return x;
        });
        assertTrue(pinnedFor(b, 40).isEmpty(), "there is nothing to pin in prose");
    }
}
