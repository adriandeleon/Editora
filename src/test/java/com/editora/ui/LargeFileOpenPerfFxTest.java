package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the large-file open freeze: setting the language on a large, fold-heavy buffer that
 * isn't laid out yet must not synchronously recreate a gutter graphic per fold region. Before the fix,
 * {@code FoldManager.recompute()} recreated every fold-start line's graphic (thousands of them) on the first
 * recompute, freezing the FX thread for seconds; now it only touches currently-visible rows.
 */
@Tag("fx")
class LargeFileOpenPerfFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void settingLanguageOnLargeFoldHeavyBufferIsFast() throws Exception {
        // ~16k lines with a fold region every ~3 lines → thousands of fold headers.
        StringBuilder sb = new StringBuilder(600_000);
        for (int i = 0; i < 4000; i++) {
            sb.append("class C").append(i).append(" {\n");
            sb.append("    int f() {\n");
            sb.append("        return ").append(i).append(";\n");
            sb.append("    }\n");
        }
        String text = sb.toString();

        long ms = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent(text);
            long s = System.nanoTime();
            buf.setLanguageOverride("java"); // first fold recompute over the whole document
            return (System.nanoTime() - s) / 1_000_000;
        });

        System.out.println("setLanguageOverride on " + (text.length() / 1024) + " KB fold-heavy buffer: " + ms + " ms");
        // Pre-fix this was multiple seconds (one recreateParagraphGraphic per fold header). The visible-only
        // fix makes it a few ms; a generous bound catches a regression to O(folds) graphic recreation.
        assertTrue(ms < 2000, "setLanguageOverride took " + ms + " ms (expected < 2000 ms — fold-graphic regression?)");
    }
}
