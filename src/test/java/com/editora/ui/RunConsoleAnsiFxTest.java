package com.editora.ui;

import java.util.Collection;

import javafx.scene.control.Label;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Run console draws what a terminal would: colours, carriage-return progress bars, clear. */
@Tag("fx")
class RunConsoleAnsiFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static RunPanel started() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            RunPanel p = new RunPanel(() -> {});
            p.started("bash x.sh");
            return p;
        });
    }

    private static String text(RunPanel p) throws Exception {
        CodeArea output = FxTestSupport.field(p, "output");
        return FxTestSupport.callOnFx(output::getText);
    }

    private static Collection<String> styleAt(RunPanel p, int offset) throws Exception {
        CodeArea output = FxTestSupport.field(p, "output");
        return FxTestSupport.callOnFx(() -> output.getStyleAtPosition(offset + 1));
    }

    @Test
    void colourCodesAreDrawnNotPrinted() throws Exception {
        RunPanel p = started();
        FxTestSupport.runOnFx(() -> {
            p.appendOutput("\u001b[0;32m[ OK ]\u001b[0m step one", false);
            p.appendOutput("\u001b[31m[FAIL]\u001b[0m step two", true);
        });
        assertEquals("[ OK ] step one\n[FAIL] step two\n", text(p), "no escape characters in the console");
        assertTrue(styleAt(p, 1).contains("ansi-green"), styleAt(p, 1).toString());
        assertFalse(styleAt(p, 8).contains("ansi-green"));
        int fail = "[ OK ] step one\n".length() + 1;
        assertTrue(styleAt(p, fail).containsAll(java.util.List.of("run-stderr", "ansi-red")));
    }

    @Test
    void aCarriageReturnOverwritesTheLineLikeATerminal() throws Exception {
        RunPanel p = started();
        FxTestSupport.runOnFx(() -> {
            p.appendOutput("before", false);
            for (int i = 1; i <= 5; i++) {
                p.appendPartialOutput("\rprogress " + i + "/5", false);
            }
            p.appendOutput("", false); // the newline after the loop
            p.appendOutput("xxxx\u001b[2K\rcleared line", false);
            p.appendOutput("a\rb\rlast", false);
        });
        assertEquals("before\nprogress 5/5\ncleared line\nlast\n", text(p));
    }

    @Test
    void clearEmptiesTheConsole() throws Exception {
        RunPanel p = started();
        FxTestSupport.runOnFx(() -> {
            p.appendOutput("old screen", false);
            p.appendOutput("\u001b[H\u001b[2J\u001b[3Jfresh", false);
        });
        assertEquals("fresh\n", text(p));
    }

    @Test
    void aTraceLineIsMutedNotRed() throws Exception {
        RunPanel p = started();
        FxTestSupport.runOnFx(() -> {
            p.appendOutput("+/w/x.sh:3: echo hi", true);
            p.appendOutput("/w/x.sh: line 4: nope: command not found", true);
        });
        assertTrue(styleAt(p, 2).contains("run-trace"), styleAt(p, 2).toString());
        assertTrue(styleAt(p, "+/w/x.sh:3: echo hi\n".length() + 2).contains("run-stderr"));
    }

    @Test
    void aStoppedRunSaysStopped() throws Exception {
        RunPanel p = started();
        FxTestSupport.runOnFx(p::stopped);
        Label status = FxTestSupport.field(p, "status");
        assertEquals(tr("run.stopped"), FxTestSupport.callOnFx(status::getText));
    }
}
