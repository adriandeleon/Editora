package com.editora.ui;

import java.util.Collection;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #560: the External Tools console colors stderr. {@code show(...)} receives stdout and stderr as
 * separate strings; the stderr region gets the {@code run-stderr} style so a tool's error output stands out.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalToolConsoleColorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void stderrRangeIsColoredAndStdoutIsNot() throws Exception {
        boolean[] flags = FxTestSupport.callOnFx(() -> {
            ExternalToolPanel panel = new ExternalToolPanel();
            CodeArea out = FxTestSupport.field(panel, "output");

            panel.show("fmt", "fmt file.txt", "clean stdout line\n", "error: something broke\n", 1);

            String text = out.getText();
            Collection<String> stderrStyle = out.getStyleOfChar(text.indexOf("error: something broke"));
            Collection<String> stdoutStyle = out.getStyleOfChar(text.indexOf("clean stdout line"));
            return new boolean[] {stderrStyle.contains("run-stderr"), stdoutStyle.contains("run-stderr")};
        });

        assertTrue(flags[0], "the stderr region is tinted with run-stderr");
        assertFalse(flags[1], "the stdout region is not tinted");
    }
}
