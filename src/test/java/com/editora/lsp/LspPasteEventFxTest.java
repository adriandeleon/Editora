package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code handlePasteEvent} round-trip (#742) against the recording fake: what goes on the wire —
 * the params, not that the call compiles (#725's lesson) — and what the answer does. The wire shapes
 * themselves are pinned pure in {@link JdtlsPasteTest}; this proves {@link LspManager} actually sends
 * them, honours the capability gate, and drops a stale answer instead of applying it.
 */
@Tag("fx")
class LspPasteEventFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private Path file;
    private ServerCapabilities capabilities = new ServerCapabilities();

    @BeforeEach
    void setUp() throws Exception {
        fakes.clear();
        capabilities = new ServerCapabilities();
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(JdtlsPaste.COMMAND)));
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.attachForTest(fake, capabilities);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private FakeLanguageServer open() {
        manager.openDocument(file, root, "java", "class A {}");
        return fakes.get(0);
    }

    private boolean paste(java.util.function.BooleanSupplier stillValid) throws Exception {
        var result = new AtomicReference<Boolean>();
        var latch = new CountDownLatch(1);
        manager.handlePasteEvent(file, 4, 0, 5, 0, "List<String> xs;\n", 4, true, stillValid, applied -> {
            result.set(applied);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the callback never fired");
        return result.get();
    }

    /** The captured live-jdtls answer shape: {@code {insertText, additionalEdit}}. */
    private static com.google.gson.JsonElement pasteAnswer(String uri) {
        return JsonParser.parseString("{\"insertText\":\"List<String> xs;\\n\",\"additionalEdit\":{\"changes\":{\""
                + uri
                + "\":[{\"range\":{\"start\":{\"line\":0,\"character\":13},\"end\":{\"line\":2,\"character\":0}},"
                + "\"newText\":\"\\n\\nimport java.util.List;\\n\\n\"}]}}}");
    }

    @Test
    void sendsTheStringifiedParamsTheLiveServerRequires() throws Exception {
        FakeLanguageServer fake = open();
        fake.executeCommandResponse = pasteAnswer(file.toUri().toString());
        manager.setApplyEditHandler(edits -> true);

        assertTrue(paste(() -> true));

        assertEquals(1, fake.executedCommands.size());
        var sent = fake.executedCommands.get(0);
        assertEquals(JdtlsPaste.COMMAND, sent.getCommand());
        assertEquals(1, sent.getArguments().size(), "exactly one argument");
        Object arg = sent.getArguments().get(0);
        // THE point: a JSON *string*, not an object — an object deserializes to null server-side.
        String json = arg instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : (String) arg;
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(
                file.toUri().toString(),
                parsed.getAsJsonObject("location").get("uri").getAsString());
        assertEquals("List<String> xs;\n", parsed.get("text").getAsString());
        assertTrue(
                parsed.getAsJsonObject("formattingOptions").get("insertSpaces").getAsBoolean());
    }

    @Test
    void theAdditionalEditReachesTheApplyHandler() throws Exception {
        FakeLanguageServer fake = open();
        fake.executeCommandResponse = pasteAnswer(file.toUri().toString());
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler(edits -> {
            applied.set(edits);
            return true;
        });

        assertTrue(paste(() -> true));
        assertTrue(applied.get() != null, "the import edit was handed to the apply handler");
    }

    @Test
    void aStaleAnswerIsDroppedNotApplied() throws Exception {
        FakeLanguageServer fake = open();
        fake.executeCommandResponse = pasteAnswer(file.toUri().toString());
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler(edits -> {
            applied.set(edits);
            return true;
        });

        assertFalse(paste(() -> false), "the user typed during the round trip — the answer must be dropped");
        assertTrue(applied.get() == null, "nothing may reach the apply handler for a stale answer");
    }

    @Test
    void aServerWithoutTheCommandIsNeverAsked() throws Exception {
        capabilities = new ServerCapabilities(); // no executeCommandProvider at all
        FakeLanguageServer fake = open();

        assertFalse(paste(() -> true));
        assertTrue(fake.executedCommands.isEmpty(), "no request may go out — the server never advertised it");
    }

    @Test
    void anAnswerWithNoEditReportsFalseWithoutApplying() throws Exception {
        FakeLanguageServer fake = open();
        fake.executeCommandResponse = JsonParser.parseString("{\"insertText\":\"x\"}"); // nothing to import
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler(edits -> {
            applied.set(edits);
            return true;
        });

        assertFalse(paste(() -> true));
        assertTrue(applied.get() == null);
    }
}
