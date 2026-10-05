package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tab's LSP re-indent asks the server about the text on screen: a pending (debounced) didChange is flushed
 * before the range-formatting request. Otherwise the server formats the line it still has — the old one.
 */
@Tag("fx")
class LspFormatSyncsFirstFxTest {

    @TempDir
    Path root;

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {}
    }

    @Test
    void tabReindentFlushesThePendingChangeBeforeAskingTheServer() throws Exception {
        FxTestSupport.bootToolkit();
        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls"));
        FakeHost host = new FakeHost();
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {\n}\n");
        try {
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                LspCoordinator coordinator = new LspCoordinator(host, manager, new LspOpsStub());
                coordinator.setServerAvailableForTest("java", true);
                EditorBuffer x = new EditorBuffer();
                x.setPath(file);
                x.setContent("class A {\n}\n");
                host.buffers.add(x);
                host.active = x;
                coordinator.wireBuffer(x);
                coordinator.syncBuffer(x);
                x.setLspRangeFormatAvailable(true);
                return x;
            });
            FakeLanguageServer fake = fakes.get(0);
            assertEquals(0, fake.changed.size(), "precondition: nothing edited yet");

            FxTestSupport.runOnFx(() -> {
                b.getArea().insertText(1, 0, "foo();\n"); // typed just now: the 300 ms debounce has not fired
                b.getArea().moveTo(1, 3);
                b.getArea()
                        .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            });

            assertEquals(1, fake.rangeFormattings.size(), "Tab asked the server to format the line");
            assertEquals(1, fake.changed.size(), "after sending it the line it is being asked about");
        } finally {
            manager.shutdownAll();
        }
    }
}
