package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.Collection;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #560: the Debug console colors the DAP {@code stderr} category. A debuggee's stderr output gets
 * the {@code run-stderr} style so it stands out from normal ({@code stdout}/{@code console}) output.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugConsoleColorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A no-op {@link DebugPanel.Actions} (14 methods) via a dynamic proxy — appendOutput never invokes it. */
    private static DebugPanel.Actions noopActions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    @Test
    void stderrCategoryIsColoredAndStdoutIsNot() throws Exception {
        boolean[] flags = FxTestSupport.callOnFx(() -> {
            DebugPanel panel = new DebugPanel(noopActions());
            CodeArea console = FxTestSupport.field(panel, "console");

            panel.appendOutput("Exception in thread main\n", "stderr");
            panel.appendOutput("normal program output\n", "stdout");

            String text = console.getText();
            Collection<String> stderrStyle = console.getStyleOfChar(text.indexOf("Exception in thread main"));
            Collection<String> stdoutStyle = console.getStyleOfChar(text.indexOf("normal program output"));
            return new boolean[] {stderrStyle.contains("run-stderr"), stdoutStyle.contains("run-stderr")};
        });

        assertTrue(flags[0], "the DAP stderr category is tinted with run-stderr");
        assertFalse(flags[1], "the stdout category is not tinted");
    }
}
