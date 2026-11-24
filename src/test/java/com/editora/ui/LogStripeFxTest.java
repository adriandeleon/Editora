package com.editora.ui;

import java.time.Duration;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The log overview stripe: on with the log viewer, its marks follow the text, filtered or not, small or large. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogStripeFxTest {

    private static final String LOG = """
            2026-11-24 10:00:00 INFO  started
            2026-11-24 10:00:01 WARN  pool low
            2026-11-24 10:00:02 ERROR request failed
            \tat com.example.Pool.take(Pool.java:42)
            2026-11-24 10:00:03 INFO  done
            """;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer log(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setInitialContent(text);
            b.setLogViewForced(true);
            b.setLogHighlightEnabled(true);
            return b;
        });
    }

    private static int[] marks(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.logStripeIfAttached().marks().lines());
    }

    @Test
    void marksAppearWithTheViewerAndGoWithIt() throws Exception {
        EditorBuffer b = log(LOG);
        assertArrayEquals(new int[] {1, 2}, marks(b));
        assertTrue(FxTestSupport.callOnFx(() -> b.logStripeIfAttached().isActive()));

        FxTestSupport.runOnFx(() -> b.setLogHighlightEnabled(false));
        assertFalse(FxTestSupport.callOnFx(() -> b.logStripeIfAttached().isActive()));
        assertEquals(0, marks(b).length);

        EditorBuffer plain = FxTestSupport.callOnFx(() -> {
            EditorBuffer p = new EditorBuffer();
            p.setInitialContent(LOG);
            p.setLogHighlightEnabled(true); // not a log: nothing is built
            return p;
        });
        assertNull(FxTestSupport.callOnFx(plain::logStripeIfAttached));
    }

    @Test
    void anAppendedErrorIsMarkedOnceTheTextSettles() throws Exception {
        EditorBuffer b = log(LOG);
        FxTestSupport.runOnFx(() -> b.getArea().appendText("2026-11-24 10:00:05 ERROR again\n"));
        FxTestSupport.awaitFxCondition(() -> marks(b).length == 3, "the appended ERROR line marked");
        assertArrayEquals(new int[] {1, 2, 5}, marks(b));
    }

    @Test
    void aFilteredLogMarksWhatTheFilterKept() throws Exception {
        EditorBuffer b = log(LOG);
        FxTestSupport.runOnFx(
                () -> b.applyLogFilter(com.editora.logviewer.LogLevel.ERROR, com.editora.logviewer.LogQuery.NONE));
        FxTestSupport.awaitFxCondition(() -> marks(b).length == 1 && marks(b)[0] == 0, "only the ERROR, now line 0");
    }

    @Test
    void aLargeLogIsScannedOffTheFxThread() throws Exception {
        StringBuilder text = new StringBuilder();
        int lines = 0;
        while (text.length() <= 300 * 1024) {
            text.append(lines % 1000 == 999 ? "2026-11-24 10:00:00 ERROR boom\n" : "2026-11-24 10:00:00 INFO  ok\n");
            lines++;
        }
        EditorBuffer b = log(text.toString());
        int expected = lines / 1000;
        FxTestSupport.awaitFxCondition(() -> marks(b).length == expected, "every ERROR marked", Duration.ofSeconds(20));
        assertEquals(999, marks(b)[0]);
    }
}
