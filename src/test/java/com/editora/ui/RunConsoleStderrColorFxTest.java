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
 * Regression for #558: the Run console colors stderr. A program's error output (e.g. a Python traceback) arrives
 * on the stderr stream ({@code appendOutput(line, true)}); those lines get the {@code run-stderr} style class so
 * they stand out, while stdout lines stay unstyled.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunConsoleStderrColorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void stderrLinesAreColoredAndStdoutIsNot() throws Exception {
        boolean[] flags = FxTestSupport.callOnFx(() -> {
            RunPanel panel = new RunPanel(() -> {});
            CodeArea out = FxTestSupport.field(panel, "output");

            panel.appendOutput("Traceback (most recent call last):", true); // stderr
            panel.appendOutput("hello from stdout", false); // stdout

            Collection<String> stderrStyle = out.getStyleOfChar(0);
            int stdoutStart = out.getText().indexOf("hello from stdout");
            Collection<String> stdoutStyle = out.getStyleOfChar(stdoutStart);

            return new boolean[] {stderrStyle.contains("run-stderr"), stdoutStyle.contains("run-stderr")};
        });

        assertTrue(flags[0], "a stderr line is tinted with run-stderr so error output stands out");
        assertFalse(flags[1], "a stdout line is not tinted");
    }

    @Test
    void stdoutLogLevelsAndUrlsUseOutputConsoleStyles() throws Exception {
        boolean[] flags = FxTestSupport.callOnFx(() -> {
            RunPanel panel = new RunPanel(() -> {});
            CodeArea out = FxTestSupport.field(panel, "output");

            panel.appendOutput("[WARN] See https://example.com/docs.", false);
            int warn = out.getText().indexOf("[WARN]");
            int url = out.getText().indexOf("https://");
            Collection<String> warningStyle = out.getStyleOfChar(warn);
            Collection<String> urlStyle = out.getStyleOfChar(url);
            return new boolean[] {
                warningStyle.contains("log-warn"), urlStyle.contains("log-warn"), urlStyle.contains("console-url")
            };
        });

        assertTrue(flags[0], "recognized stdout log levels use semantic console colors");
        assertTrue(flags[1], "the URL keeps its line's semantic color");
        assertTrue(flags[2], "HTTP(S) URLs are styled as clickable links");
    }
}
