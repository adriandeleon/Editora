package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.externaltool.ExternalTool;
import com.editora.externaltool.ToolInvocation;
import com.editora.process.ProcessRunner;
import com.editora.run.StackTraceLinks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An external tool whose stdout exceeded the capture cap. {@code ProcessRunner} keeps the first 10 MB and
 * flags the rest as dropped; applying that to a replace-buffer or replace-selection target wrote a silently
 * cut-off prefix of the tool's output over the document.
 */
@Tag("fx")
class ExternalToolTruncatedOutputFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class RecordingHost extends CoordinatorHostStub {
        final List<String> statuses = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void setError(String message) {
            errors.add(message);
        }
    }

    private static final class Ops implements ExternalToolCoordinator.Ops {
        int consoleOpened;

        @Override
        public Path projectRoot() {
            return null;
        }

        @Override
        public void openConsole() {
            consoleOpened++;
        }

        @Override
        public void onOutputLink(StackTraceLinks.Link link) {}
    }

    private static final ToolInvocation INVOCATION = new ToolInvocation(List.of("fmt", "-"), Path.of("."), "", "fmt -");

    private static ExternalTool tool(ExternalTool.OutputTarget target) {
        return new ExternalTool("fmt", "fmt", "-", "", ExternalTool.StdinSource.BUFFER, target, true);
    }

    private static EditorBuffer buffer(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent(content);
            buffer.getArea().getUndoManager().forgetHistory();
            return buffer;
        });
    }

    private static void apply(
            ExternalToolCoordinator c, ExternalTool tool, ProcessRunner.Result result, EditorBuffer target)
            throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                c,
                "applyResult",
                new Class[] {
                    ExternalTool.class, ToolInvocation.class, ProcessRunner.Result.class, EditorBuffer.class, long.class
                },
                tool,
                INVOCATION,
                result,
                target,
                target.docVersion()));
    }

    @Test
    void truncatedOutputIsNeverAppliedToTheDocument() throws Exception {
        for (ExternalTool.OutputTarget target : List.of(
                ExternalTool.OutputTarget.REPLACE_BUFFER,
                ExternalTool.OutputTarget.REPLACE_SELECTION,
                ExternalTool.OutputTarget.INSERT_AT_CARET)) {
            RecordingHost host = new RecordingHost();
            Ops ops = new Ops();
            ExternalToolCoordinator c = FxTestSupport.callOnFx(() -> new ExternalToolCoordinator(host, ops));
            EditorBuffer document = buffer("the whole original document\n");
            try {
                ProcessRunner.Result cutOff = new ProcessRunner.Result(0, "only the first part", "", true, false);

                apply(c, tool(target), cutOff, document);

                assertEquals(
                        "the whole original document\n",
                        FxTestSupport.callOnFx(document::getContent),
                        target + ": a prefix of the tool's output must not replace the text");
                assertFalse(FxTestSupport.callOnFx(document::isDirty), target + ": nothing was edited");
                String message = tr("status.externalTool.outputTruncated", "fmt");
                assertEquals(List.of(message), host.errors, "the reason is reported");
                assertEquals(1, ops.consoleOpened, "and what was captured goes to the console");
                String console = FxTestSupport.callOnFx(
                        () -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(c.panel(), "output")
                                .getText());
                assertTrue(console.contains("only the first part") && console.contains(message), console);
            } finally {
                FxTestSupport.runOnFx(document::dispose);
                c.shutdown();
            }
        }
    }

    @Test
    void completeOutputIsStillApplied() throws Exception {
        RecordingHost host = new RecordingHost();
        Ops ops = new Ops();
        ExternalToolCoordinator c = FxTestSupport.callOnFx(() -> new ExternalToolCoordinator(host, ops));
        EditorBuffer document = buffer("before\n");
        try {
            apply(
                    c,
                    tool(ExternalTool.OutputTarget.REPLACE_BUFFER),
                    new ProcessRunner.Result(0, "after\n", ""),
                    document);

            assertEquals("after\n", FxTestSupport.callOnFx(document::getContent));
            assertTrue(host.errors.isEmpty());
            assertEquals(0, ops.consoleOpened);
        } finally {
            FxTestSupport.runOnFx(document::dispose);
            c.shutdown();
        }
    }

    @Test
    void theDecisionIsTheCaptureFlagNotTheSize() {
        assertTrue(ExternalToolCoordinator.outputIncomplete(new ProcessRunner.Result(0, "x", "", true, false)));
        assertFalse(ExternalToolCoordinator.outputIncomplete(new ProcessRunner.Result(0, "x", "", false, true)));
        assertFalse(ExternalToolCoordinator.outputIncomplete(new ProcessRunner.Result(0, "x", "")));
    }
}
