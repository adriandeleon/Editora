package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import com.editora.editor.fold.NarrowLines;
import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import com.editora.text.FoldRegions.Region;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A narrow or widen is finished in the frame it happens in: the text keeps its syntax colours, the fold gutter
 * its regions and the outline its symbols, instead of showing plain text on stale folds until the debounced
 * highlight and fold passes come back (a quarter to over half a second on a 1,600-line file, which made the
 * Structure window's narrow toggle feel slow). Each check reads the buffer in the same FX call as the swap.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NarrowCarryFxTest {

    private static final String DOC = """
            package demo;

            /** A class. */
            public class Demo {
                private int count;

                public void greet(String name) {
                    if (name != null) {
                        System.out.println("Hello " + name);
                    }
                }

                public int increment() {
                    count++;
                    return count;
                }
            }
            """;

    /** Document lines of {@code greet}'s block (its signature to its closing brace). */
    private static final int GREET = 6;

    private static final int GREET_END = 10;

    private record Whole(StyleSpans<Collection<String>> spans, List<Region> regions, List<Symbol> symbols) {}

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer javaBuffer() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            new Scene(new StackPane(buffer.getArea()), 600, 400);
            buffer.setLanguageOverride("java");
            buffer.setContent(DOC);
            return buffer;
        });
        settle();
        return b;
    }

    /** Lets the debounced highlight and fold passes land. */
    private static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            Thread.sleep(120);
            FxTestSupport.drainFx();
        }
    }

    private static Whole whole(EditorBuffer b) {
        return new Whole(
                b.getArea().getStyleSpans(0, b.getArea().getLength()),
                b.getFoldManager().regions(),
                b.symbols());
    }

    private static boolean narrowToGreet(EditorBuffer b) {
        int start = NarrowLines.offsetOfLine(DOC, GREET);
        int end = DOC.indexOf('\n', NarrowLines.offsetOfLine(DOC, GREET_END));
        return b.narrowTo(start, end);
    }

    @Test
    void narrowingKeepsTheRegionsColoursFoldsAndSymbolsInTheSameFrame() throws Exception {
        EditorBuffer b = javaBuffer();
        FxTestSupport.runOnFx(() -> {
            Whole before = whole(b);
            int start = NarrowLines.offsetOfLine(DOC, GREET);
            int end = DOC.indexOf('\n', NarrowLines.offsetOfLine(DOC, GREET_END));
            StyleSpans<Collection<String>> regionSpans = b.getArea().getStyleSpans(start, end);
            assertTrue(styled(regionSpans), "the fixture is highlighted before narrowing");

            assertTrue(narrowToGreet(b));

            assertEquals(regionSpans, b.getArea().getStyleSpans(0, b.getArea().getLength()), "colours kept");
            List<Region> inside = new ArrayList<>();
            for (Region r : before.regions()) {
                if (r.startLine() >= GREET && r.endLine() <= GREET_END) {
                    inside.add(new Region(r.startLine() - GREET, r.endLine() - GREET));
                }
            }
            assertTrue(inside.contains(new Region(0, GREET_END - GREET)), "greet's own block folds: " + inside);
            assertEquals(inside, b.getFoldManager().regions(), "the region's folds, region-relative");
            assertTrue(
                    b.symbols().stream().anyMatch(s -> s.line() == 0 && s.name().contains("greet")),
                    "greet's symbol is on the region's first line: " + b.symbols());
            assertTrue(b.symbols().stream().noneMatch(s -> s.name().contains("increment")), "out of the region");
        });
    }

    @Test
    void wideningPutsBackTheWholeFilesColoursFoldsAndSymbolsInTheSameFrame() throws Exception {
        EditorBuffer b = javaBuffer();
        Whole before = FxTestSupport.callOnFx(() -> whole(b));
        FxTestSupport.runOnFx(() -> assertTrue(narrowToGreet(b)));
        settle(); // the region's own highlight and fold passes run
        FxTestSupport.runOnFx(() -> {
            b.widen();
            Whole after = whole(b);
            assertEquals(before.spans(), after.spans(), "colours put back");
            assertEquals(before.regions(), after.regions(), "the whole file's folds");
            assertEquals(before.symbols(), after.symbols(), "the whole file's symbols");
        });
    }

    @Test
    void linesAddedWhileNarrowedMoveTheFoldsBelowTheRegionAtOnce() throws Exception {
        EditorBuffer b = javaBuffer();
        Region increment = FxTestSupport.callOnFx(() -> b.getFoldManager().regions().stream()
                .filter(r -> r.startLine() == 12)
                .findFirst()
                .orElseThrow());
        FxTestSupport.runOnFx(() -> assertTrue(narrowToGreet(b)));
        FxTestSupport.runOnFx(() -> b.getArea().insertText(b.getArea().getLength(), "\n    // two\n    // more"));
        settle();
        List<Region> carried = FxTestSupport.callOnFx(() -> {
            b.widen();
            return b.getFoldManager().regions();
        });
        assertTrue(
                carried.contains(new Region(increment.startLine() + 2, increment.endLine() + 2)),
                "increment moved down two lines: " + carried);
        assertTrue(carried.stream().anyMatch(r -> r.startLine() == 3), "the class still folds: " + carried);
        settle(); // the fold scan of the widened text has the last word, and agrees
        assertEquals(carried, FxTestSupport.callOnFx(() -> b.getFoldManager().regions()));
        assertNotEquals(DOC, FxTestSupport.callOnFx(b::getContent));
    }

    private static boolean styled(StyleSpans<Collection<String>> spans) {
        return spans.stream().anyMatch(s -> !s.getStyle().isEmpty());
    }
}
