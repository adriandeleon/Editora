package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.ComboBox;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownLint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's {@code {{variable}}} marks follow the file's environment: the env files found for the file, the
 * environment chosen in its response panel, and a change to those files while the editor is open.
 */
@Tag("fx")
class HttpVariableHintsFxTest {

    private Host host;
    private HttpClientCoordinator coordinator;
    private HttpVariableHints hints;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @BeforeEach
    void setUp() {
        host = new Host();
        host.settings.setHttpClientSupport(true);
        coordinator = new HttpClientCoordinator(host, new HttpClientCoordinator.WindowOps() {
            @Override
            public void openTab(EditorBuffer buffer) {}

            @Override
            public void updateRunGating() {}
        });
        hints = new HttpVariableHints(new HttpVariableHints.Source() {
            @Override
            public String environment(EditorBuffer buffer) {
                return coordinator.selectedEnvironment(buffer);
            }

            @Override
            public Path envDir(EditorBuffer buffer) {
                return coordinator.envDirFor(buffer);
            }
        });
        coordinator.setEnvironmentListener(hints::refresh);
    }

    @AfterEach
    void tearDown() throws Exception {
        hints.shutdown();
        coordinator.shutdown();
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private EditorBuffer open(Path file, String text) throws Exception {
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            hints.wire(created);
            created.setHttpEnabled(true);
            coordinator.ensureHttpPreview(created);
            return created;
        });
        buffers.add(b);
        return b;
    }

    private static List<String> marked(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.httpVariableDiagnostics().stream()
                .map(MarkdownLint.Diagnostic::code)
                .toList());
    }

    private static void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(done::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, "the marks should settle");
            Thread.sleep(20);
        }
    }

    @Test
    void theMarksFollowTheEnvironmentAndItsFiles(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git")); // one repository: its root's env files apply below it
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("http-client.env.json"), "{\"dev\":{\"host\":\"http://h\"},\"prod\":{}}");
        Files.writeString(api.resolve(".env"), "KEY=v\n");
        EditorBuffer b =
                open(api.resolve("a.http"), "GET {{host}}/x?k={{$dotenv KEY}}\nAuthorization: Bearer {{token}}\n");
        await(() -> !b.httpVariableDiagnostics().isEmpty());
        assertEquals(List.of("{{host}}", "{{token}}"), marked(b), "no environment chosen yet");

        ComboBox<String> env = FxTestSupport.field(coordinator.panelForTest(b), "envCombo");
        FxTestSupport.runOnFx(() -> env.setValue("dev"));
        await(() -> b.httpVariableDiagnostics().size() == 1);
        assertEquals(List.of("{{token}}"), marked(b), "the env file of the parent folder applies");

        Files.writeString(root.resolve("http-client.private.env.json"), "{\"dev\":{\"token\":\"secret-but-local\"}}");
        FxTestSupport.runOnFx(() -> coordinator.refreshEnvironments(b)); // the window's focus pass does this
        await(() -> b.httpVariableDiagnostics().isEmpty());

        FxTestSupport.runOnFx(() -> env.setValue("prod"));
        await(() -> b.httpVariableDiagnostics().size() == 2);
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {}
    }
}
