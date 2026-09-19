package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class JavaEditingResponsesFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private AsyncTestScope scope;
    private EditorBuffer buffer;
    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeLanguageServer fake;
    private Stage stage;

    @BeforeEach
    void setup() throws Exception {
        scope = new AsyncTestScope();
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A { void go() { foo( } }");
        var caps = LspTestHooks.caps();
        caps.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
        caps.setHoverProvider(true);
        caps.setCompletionProvider(new CompletionOptions(true, List.of(".")));
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        var fakes = LspTestHooks.useFakeSessions(manager, caps);
        manager.configure(true, Map.of("java", "jdtls"));
        FxTestSupport.runOnFx(() -> {
            buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent("class A { void go() { foo( } }");
            buffer.setAutocomplete(false, false, false, false);
            var host = new CoordinatorHostStub() {
                final Settings settings = new Settings();

                @Override
                public Settings settings() {
                    return settings;
                }

                @Override
                public EditorBuffer activeBuffer() {
                    return buffer;
                }
            };
            coordinator = new LspCoordinator(host, manager, new LspOpsStub());
            coordinator.setServerAvailableForTest("java", true);
            coordinator.wireBuffer(buffer);
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 800, 600));
            stage.show();
            buffer.getArea().moveTo(25);
            buffer.getArea().requestFocus();
        });
        fake = fakes.getFirst();
        scope.onClose(() -> FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(coordinator, "hideSignaturePopup", new Class<?>[] {});
            FxTestSupport.call(coordinator, "hideHoverPopup", new Class<?>[] {});
            buffer.dispose();
            manager.shutdownAll();
            stage.close();
        }));
    }

    @AfterEach
    void close() throws Exception {
        scope.close();
    }

    private SignatureHelp help(String label) {
        return new SignatureHelp(List.of(new SignatureInformation(label)), 0, 0);
    }

    @Test
    void oldEmptySignatureResponseCannotDismissNewerHelp() throws Exception {
        var old = new CompletableFuture<SignatureHelp>();
        var newer = new CompletableFuture<SignatureHelp>();
        FxTestSupport.runOnFx(() -> {
            fake.signatureHelpFuture = old;
            coordinator.signatureHelp(true);
            fake.signatureHelpFuture = newer;
            coordinator.signatureHelp(true);
        });
        FxTestSupport.runOnFx(() -> newer.complete(help("foo(String value)")));
        scope.awaitFx();
        Object popup = FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup"));
        assertNotNull(popup);
        FxTestSupport.runOnFx(() -> old.complete(new SignatureHelp()));
        scope.awaitFx();
        assertSame(popup, FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
    }

    @Test
    void signatureResponseAfterTypingOrEscapeDoesNotReopenPopup() throws Exception {
        for (boolean escape : List.of(false, true)) {
            var pending = new CompletableFuture<SignatureHelp>();
            FxTestSupport.runOnFx(() -> {
                fake.signatureHelpFuture = pending;
                coordinator.signatureHelp(true);
                if (escape) buffer.pressKey("ESCAPE");
                else buffer.typeString("x");
            });
            FxTestSupport.runOnFx(() -> pending.complete(help("foo(String value)")));
            scope.awaitFx();
            assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
            if (escape)
                FxTestSupport.runOnFx(() -> {
                    int requests = fake.signatureHelps.size();
                    FxTestSupport.call(coordinator, "refreshSignatureHelpIfShowing", new Class<?>[] {});
                    assertEquals(requests, fake.signatureHelps.size(), "Escape must disarm the settled retry");
                });
        }
    }

    @Test
    void typingBeforeFirstSignatureResponseStillRequestsHelpOnSettle() throws Exception {
        var old = new CompletableFuture<SignatureHelp>();
        var current = new CompletableFuture<SignatureHelp>();
        FxTestSupport.runOnFx(() -> {
            fake.signatureHelpFuture = old;
            coordinator.signatureHelp(true);
            buffer.typeString("x");
            old.complete(help("foo(old)"));
        });
        scope.awaitFx();
        FxTestSupport.runOnFx(() -> {
            assertNull(FxTestSupport.field(coordinator, "signaturePopup"));
            fake.signatureHelpFuture = current;
            int requests = fake.signatureHelps.size();
            FxTestSupport.call(coordinator, "refreshSignatureHelpIfShowing", new Class<?>[] {});
            assertEquals(requests + 1, fake.signatureHelps.size());
            current.complete(help("foo(current)"));
        });
        scope.awaitFx();
        assertNotNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
    }

    @Test
    void escapeInSecondaryViewInvalidatesPendingSignatureHelp() throws Exception {
        var pending = new CompletableFuture<SignatureHelp>();
        FxTestSupport.runOnFx(() -> {
            buffer.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            var other = (org.fxmisc.richtext.CodeArea) FxTestSupport.field(buffer, "area2");
            other.moveTo(25);
            other.requestFocus();
            fake.signatureHelpFuture = pending;
            coordinator.signatureHelp(true);
            buffer.pressKey("ESCAPE");
            pending.complete(help("foo(String value)"));
        });
        scope.awaitFx();
        assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
    }

    @Test
    void pendingHelpCannotOpenInAnInactiveSplitWithTheSameCaret() throws Exception {
        var signature = new CompletableFuture<SignatureHelp>();
        var hover = new CompletableFuture<Hover>();
        FxTestSupport.runOnFx(() -> {
            buffer.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            buffer.getArea().moveTo(25);
            buffer.getArea().requestFocus();
            fake.signatureHelpFuture = signature;
            fake.hoverFuture = hover;
            coordinator.signatureHelp(true);
            coordinator.showHover();
            var other = (org.fxmisc.richtext.CodeArea) FxTestSupport.field(buffer, "area2");
            other.moveTo(25);
            other.requestFocus();
            signature.complete(help("foo(String value)"));
            hover.complete(new Hover(new MarkupContent("plaintext", "old view")));
        });
        scope.awaitFx();
        assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
        assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "hoverPopup")));
    }

    @Test
    void hoverAfterCaretMovementDoesNotOpenAtTheNewCaret() throws Exception {
        var pending = new CompletableFuture<Hover>();
        FxTestSupport.runOnFx(() -> {
            fake.hoverFuture = pending;
            coordinator.showHover();
            buffer.getArea().moveTo(0);
        });
        FxTestSupport.runOnFx(() -> pending.complete(new Hover(new MarkupContent("plaintext", "stale"))));
        scope.awaitFx();
        assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "hoverPopup")));
    }

    @Test
    void overloadChoiceSurvivesMultilineTypingAndRefreshWithoutReplacingThePopup() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fake.signatureHelpResponse = new SignatureHelp(
                    List.of(new SignatureInformation("foo(int value)"), new SignatureInformation("foo(String value)")),
                    0,
                    0);
            coordinator.signatureHelp(true);
        });
        scope.awaitFx();
        Object popup = FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup"));
        assertNotNull(popup);
        FxTestSupport.runOnFx(() -> {
            coordinator.moveSignature(1);
            buffer.typeString("\n  x");
            assertSame(popup, FxTestSupport.field(coordinator, "signaturePopup"));
            coordinator.signatureHelp(false);
            var context = fake.signatureHelps.getLast().getContext();
            assertTrue(context.isRetrigger());
            assertEquals(1, context.getActiveSignatureHelp().getActiveSignature());
        });
        scope.awaitFx();
        assertSame(popup, FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
        FxTestSupport.runOnFx(() -> {
            var selection = (SignatureSelection) FxTestSupport.field(coordinator, "signatureSelection");
            assertEquals("foo(String value)", selection.active().label());
            fake.signatureHelpResponse = new SignatureHelp();
            buffer.typeString(")");
            coordinator.signatureHelp(false);
        });
        scope.awaitFx();
        assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "signaturePopup")));
    }

    @Test
    void eagerImportIsAppliedImmediatelyAndSyncedBeforeSelectionFeedback() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.setContent("class A { ArrayList value; }");
            buffer.getArea().moveTo(19);
            var item = new CompletionItem("ArrayList");
            item.setData(Map.of("proposal", 1)); // even with resolve data, eager edits must not wait
            item.setAdditionalTextEdits(List.of(
                    new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "import java.util.ArrayList;\n")));
            item.setCommand(new Command("selected", "java.completion.onDidSelect", List.of(1)));
            var accept = (Runnable) FxTestSupport.call(
                    coordinator,
                    "autoImportAccept",
                    new Class<?>[] {EditorBuffer.class, CompletionItem.class},
                    buffer,
                    item);
            assertNotNull(accept);
            accept.run();
            assertEquals("import java.util.ArrayList;\nclass A { ArrayList value; }", buffer.text());
            assertEquals(1, fake.executedCommands.size());
            assertEquals(
                    "java.completion.onDidSelect",
                    fake.executedCommands.getFirst().getCommand());
            assertEquals(
                    buffer.text(),
                    fake.changed.getLast().getContentChanges().getFirst().getText());
            assertEquals(
                    19 + "import java.util.ArrayList;\n".length(),
                    buffer.getArea().getCaretPosition());
        });
    }
}
