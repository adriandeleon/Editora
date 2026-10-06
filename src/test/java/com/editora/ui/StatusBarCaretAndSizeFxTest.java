package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status bar's caret and size segments stay off the hot paths: one refresh per pulse however many
 * properties a key changes, a selection's line count without copying the selection, and the byte size of a
 * large document counted off the FX thread.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusBarCaretAndSizeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void selectedLinesMatchesCountingTheSelectedTextsLines() {
        String text = "one\ntwo\n\nfour\nfive\n\n";
        int[] starts = {0, 4, 8, 9, 14, 19, 20};
        for (int from = 0; from < text.length(); from++) {
            for (int to = from + 1; to <= text.length(); to++) {
                long expected = text.substring(from, to).lines().count();
                int startParagraph = paragraphOf(starts, from);
                int endParagraph = paragraphOf(starts, to);
                long actual = StatusBar.selectedLines(startParagraph, endParagraph, to - starts[endParagraph]);
                assertEquals(expected, actual, "selection " + from + ".." + to);
            }
        }
    }

    private static int paragraphOf(int[] starts, int offset) {
        int paragraph = 0;
        while (paragraph + 1 < starts.length && starts[paragraph + 1] <= offset) {
            paragraph++;
        }
        return paragraph;
    }

    @Test
    void utf8LengthMatchesEncodingTheText() {
        String[] samples = {
            "",
            "plain ascii\n",
            "café ß",
            "€ 中文",
            "😀 emoji",
            "lone \ud83d high",
            "lone \ude00 low",
            "\ud83d😀",
            "￿ࠀ߿\u0080\u007f"
        };
        for (String s : samples) {
            assertEquals(s.getBytes(StandardCharsets.UTF_8).length, StatusBar.utf8Length(s), "'" + s + "'");
        }
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            StringBuilder sb = new StringBuilder();
            for (int i = random.nextInt(40); i > 0; i--) {
                sb.append((char) random.nextInt(0x10000)); // any UTF-16 unit, paired or not
            }
            String s = sb.toString();
            assertEquals(s.getBytes(StandardCharsets.UTF_8).length, StatusBar.utf8Length(s));
        }
    }

    @Test
    void aKeyThatMovesCaretAndSelectionRefreshesTheSegmentsOnce() throws Exception {
        Object[] made = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("first line\nsecond line\nthird\n");
            StatusBar sb = new StatusBar(() -> buffer, new CommandRegistry(), Settings::new);
            sb.attach(buffer);
            return new Object[] {buffer, sb};
        });
        EditorBuffer buffer = (EditorBuffer) made[0];
        StatusBar sb = (StatusBar) made[1];
        FxTestSupport.drainFx();

        int before = FxTestSupport.callOnFx(() -> {
            int count = sb.caretRefreshes;
            CodeArea area = buffer.getArea();
            area.selectRange(0, 5); // moves the caret and the selection
            area.selectRange(0, 17);
            area.selectRange(0, 22); // ...and a drag or a held Shift+arrow does so many times a pulse
            assertEquals(count, sb.caretRefreshes, "nothing is recomputed inside the key event");
            return count;
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertEquals(before + 1, sb.caretRefreshes, "one refresh for the whole burst");
            Label position = FxTestSupport.field(sb, "position");
            assertEquals("Ln 2, Col 12 (22 selected, 2 lines)", position.getText());
            buffer.getArea().selectRange(0, 23); // through the line break: still two lines
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            Label position = FxTestSupport.field(sb, "position");
            assertEquals("Ln 3, Col 1 (23 selected, 2 lines)", position.getText());
        });
    }

    @Test
    void aLargeDocumentsSizeIsCountedOffTheFxThreadAndASupersededCountIsDropped() throws Exception {
        ConcurrentLinkedDeque<Runnable> held = new ConcurrentLinkedDeque<>();
        String big = "héllo wörld €\n".repeat(StatusBar.INLINE_SIZE_CHARS / 14 + 1000);
        String bigger = big + "tail\n".repeat(60_000);
        assertTrue(big.length() > StatusBar.INLINE_SIZE_CHARS);
        EditorBuffer[] active = new EditorBuffer[1];
        Object[] made = FxTestSupport.callOnFx(() -> {
            EditorBuffer small = new EditorBuffer();
            small.setContent("hello\n");
            EditorBuffer a = new EditorBuffer();
            a.setContent(big);
            EditorBuffer b = new EditorBuffer();
            b.setContent(bigger);
            active[0] = small;
            StatusBar sb = new StatusBar(() -> active[0], new CommandRegistry(), Settings::new);
            sb.sizeCounter = held::add;
            sb.attach(small);
            return new Object[] {sb, a, b};
        });
        StatusBar sb = (StatusBar) made[0];
        EditorBuffer a = (EditorBuffer) made[1];
        EditorBuffer b = (EditorBuffer) made[2];
        Label size = FxTestSupport.callOnFx(() -> FxTestSupport.field(sb, "size"));

        FxTestSupport.runOnFx(() -> {
            assertEquals(StatusBar.formatSize(6), size.getText(), "a small document is counted where it is asked");
            assertTrue(held.isEmpty());

            active[0] = a;
            sb.attach(a);
            assertEquals(1, held.size(), "a large one is handed to the counter");
            assertEquals("", size.getText(), "and the previous tab's size is not left showing meanwhile");

            active[0] = b; // the user switches again before the count comes back
            sb.attach(b);
            assertEquals(2, held.size());
        });

        held.poll().run(); // the count for the tab that was left
        FxTestSupport.drainFx();
        assertEquals("", FxTestSupport.callOnFx(size::getText), "a superseded count is dropped");

        held.poll().run();
        FxTestSupport.drainFx();
        assertEquals(
                StatusBar.formatSize(bigger.getBytes(StandardCharsets.UTF_8).length),
                FxTestSupport.callOnFx(size::getText),
                "the same text the inline count gives");
    }
}
