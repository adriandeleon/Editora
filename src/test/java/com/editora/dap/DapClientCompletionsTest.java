package com.editora.dap;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.CompletionItem;
import org.eclipse.lsp4j.debug.CompletionItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAP {@code completions} as {@link DapClient} sends and reads it (follow-up 5), over a real connection to
 * {@link FakeDebugAdapter}: the frame, the text and the caret column (1-based, as the client declares), and an
 * answer shaped like java-debug's — a method labelled with its parameters and return type, its text with the
 * parentheses, no replace range.
 */
class DapClientCompletionsTest {

    private static final class Host implements DapClient.Host {
        @Override
        public void onStopped(int threadId, String reason) {}

        @Override
        public void onContinued() {}

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {}

        @Override
        public void onError(String message) {}
    }

    private static CompletionItem item(String label, String text, CompletionItemType type, String sortText) {
        CompletionItem c = new CompletionItem();
        c.setLabel(label);
        c.setText(text);
        c.setType(type);
        c.setSortText(sortText);
        return c;
    }

    @Test
    void theFrameTextAndCaretGoOutAndJavaDebugsAnswerComesBack() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            adapter.completions = List.of(
                    item("size() : int", "size()", CompletionItemType.FUNCTION, "999999035"),
                    item("names", "names", CompletionItemType.VARIABLE, "999999161"));
            DapClient client = new DapClient(new Host());
            try {
                client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
                FakeDebugAdapter.Session session = adapter.awaitSession();
                assertTrue(client.supportsCompletions());

                List<DapModels.CompletionTarget> got =
                        client.completions(4, "names.si", 8).get(10, TimeUnit.SECONDS);

                var asked = session.completionRequests.getFirst();
                assertEquals(4, asked.getFrameId());
                assertEquals("names.si", asked.getText());
                assertEquals(9, asked.getColumn(), "the caret after the 8th character, 1-based");
                assertEquals(
                        List.of(
                                new DapModels.CompletionTarget(
                                        "size() : int", "size()", "function", null, "999999035", -1, -1),
                                new DapModels.CompletionTarget(
                                        "names", "names", "variable", null, "999999161", -1, -1)),
                        got);
                assertEquals("size()", got.getFirst().insert());
            } finally {
                client.dispose();
            }
        }
    }

    @Test
    void anAdapterThatCannotCompleteSaysSo() throws Exception {
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            DapClient client = new DapClient(new Host());
            try {
                client.connect(adapter.port(), "python").get(10, TimeUnit.SECONDS);
                adapter.awaitSession();
                assertFalse(client.supportsCompletions());
            } finally {
                client.dispose();
            }
        }
    }
}
