package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.CodeAction;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.ServerCapabilities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link LspCoordinator} over a scripted {@link FakeLanguageServer}, in a real (headless) window: buffers
 * sit in a shown scene, so popups have somewhere to open, and the pickers and prompts a flow raises can be
 * answered the way a user answers them. No language server is ever forked.
 *
 * <p>The host and ops record what the coordinator told the window (status lines, files opened, tool windows
 * raised); a test reads those rather than peeking at the coordinator's fields.
 */
final class LspCoordinatorFixture implements AutoCloseable {

    /** One {@code promptText} the coordinator raised, and the way to answer it. */
    record Prompt(String title, String label, String initial, Consumer<String> onAccept) {}

    static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        final List<String> statuses = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<Prompt> prompts = new ArrayList<>();
        OverlayHost overlay;
        Window window;
        int saveRequests;
        int settingsSyncs;

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
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void setError(String message) {
            errors.add(message);
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            prompts.add(new Prompt(title, label, initial, onAccept));
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public Window window() {
            return window;
        }

        @Override
        public void requestSave() {
            saveRequests++;
        }

        @Override
        public void syncSettingsWindow() {
            settingsSyncs++;
        }

        String lastStatus() {
            return statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);
        }

        String lastError() {
            return errors.isEmpty() ? null : errors.get(errors.size() - 1);
        }
    }

    record Jump(Path file, int line, int col) {}

    record ReadOnlyDoc(String title, String content, String language, EditorBuffer buffer) {}

    static final class Ops extends LspOpsStub {
        final Map<Path, EditorBuffer> open = new HashMap<>();
        final List<Jump> jumps = new ArrayList<>();
        final List<ReadOnlyDoc> readOnlyDocs = new ArrayList<>();
        final List<Boolean> loading = new ArrayList<>();
        final List<String> statusBarLabels = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<EditorBuffer> selected = new ArrayList<>();
        int referencesWindowOpened;
        int hierarchyWindowOpened;
        int capabilitiesReady;
        boolean editable = true;
        boolean featureEnabled = true;
        /** Whether {@link #selectBufferTab} finds the tab (false models a tab the user has closed). */
        boolean tabsSelectable = true;
        /** Called for each read-only document opened, to put it in the window. */
        java.util.function.Function<ReadOnlyDoc, EditorBuffer> readOnlyOpener = doc -> null;

        Path projectRoot;

        @Override
        public void executeCommand(String id) {
            commands.add(id);
        }

        @Override
        public void openAndGoto(Path file, int line0, int col0) {
            jumps.add(new Jump(file, line0, col0));
        }

        @Override
        public EditorBuffer openReadOnlyDoc(String title, String content, String language) {
            EditorBuffer opened = readOnlyOpener.apply(new ReadOnlyDoc(title, content, language, null));
            readOnlyDocs.add(new ReadOnlyDoc(title, content, language, opened));
            return opened;
        }

        @Override
        public boolean selectBufferTab(EditorBuffer buffer) {
            selected.add(buffer);
            return tabsSelectable;
        }

        @Override
        public boolean activeEditable() {
            return editable;
        }

        @Override
        public boolean lspFeatureEnabled() {
            return featureEnabled;
        }

        @Override
        public void setLspLoading(boolean on) {
            loading.add(on);
        }

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return file == null ? null : open.get(file.toAbsolutePath().normalize());
        }

        @Override
        public void setStatusBarLsp(String label) {
            statusBarLabels.add(label);
        }

        @Override
        public void openReferencesWindow() {
            referencesWindowOpened++;
        }

        @Override
        public void openHierarchyWindow() {
            hierarchyWindowOpened++;
        }

        @Override
        public void onServerCapabilitiesReady() {
            capabilitiesReady++;
        }

        @Override
        public Path lspProjectRoot() {
            return projectRoot;
        }
    }

    /** A buffer whose code-action popup is answered by the test instead of by a click. */
    static final class PickingBuffer extends EditorBuffer {
        List<CodeAction> offered = List.of();
        private Consumer<CodeAction> accept;

        @Override
        public void showCodeActions(List<CodeAction> actions, Consumer<CodeAction> onAccept) {
            offered = List.copyOf(actions);
            accept = onAccept;
        }

        /** Picks the offered action titled {@code title}, as Enter on its row would. */
        void pick(String title) {
            CodeAction chosen = offered.stream()
                    .filter(a -> a.title().equals(title))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no action titled " + title + " in " + offered));
            accept.accept(chosen);
        }

        /** Hands back an action the popup never listed. */
        void pickUnlisted(CodeAction action) {
            accept.accept(action);
        }

        /** Closes the popup without choosing. */
        void dismiss() {
            accept.accept(null);
        }
    }

    final Path root;
    final LspManager manager;
    final List<FakeLanguageServer> fakes;
    final Host host = new Host();
    final Ops ops = new Ops();
    final ServerCapabilities capabilities;
    LspCoordinator coordinator;
    private Stage stage;
    private StackPane sceneRoot;

    LspCoordinatorFixture(Path root) throws Exception {
        this(root, LspTestHooks.caps());
    }

    LspCoordinatorFixture(Path root, ServerCapabilities capabilities) throws Exception {
        FxTestSupport.bootToolkit();
        this.root = root;
        this.capabilities = capabilities;
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessionsWithRawRequests(manager, capabilities);
        manager.configure(true, Map.of("java", "jdtls"));
        FxTestSupport.runOnFx(() -> {
            sceneRoot = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(sceneRoot, 900, 600));
            stage.show();
            host.overlay = new OverlayHost();
            host.overlay.install(sceneRoot);
            host.window = stage;
            // The workspace-edit transaction runs inline: nothing here waits on another thread.
            coordinator = new LspCoordinator(
                    host, manager, ops, LspCoordinator.WorkspaceFileOperations.SYSTEM, Runnable::run);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    /** Creates {@code name} on disk and opens it as the active, server-managed buffer. */
    EditorBuffer open(String name, String content) throws Exception {
        return open(name, content, new EditorBuffer());
    }

    PickingBuffer openPicking(String name, String content) throws Exception {
        return open(name, content, FxTestSupport.callOnFx(PickingBuffer::new));
    }

    <T extends EditorBuffer> T open(String name, String content, T buffer) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, content);
        var firstPass = new java.util.concurrent.CountDownLatch(1);
        highlighted.put(buffer, firstPass);
        FxTestSupport.runOnFx(() -> {
            buffer.setOnSymbolsChanged(firstPass::countDown);
            buffer.setPath(file);
            buffer.setContent(content);
            show(buffer);
            host.active = buffer;
            coordinator.syncBuffer(buffer);
        });
        ops.open.put(file.toAbsolutePath().normalize(), buffer);
        layout();
        assertTrue(manager.isManaged(file), "precondition: " + name + " is on a language server");
        return buffer;
    }

    private final Map<EditorBuffer, java.util.concurrent.CountDownLatch> highlighted = new HashMap<>();

    /**
     * Waits for {@code buffer}'s first syntax-highlighting pass (signalled by the outline it produces, so the
     * content must declare something). The pass runs on a worker and, when it lands, nudges the editor's
     * scroll estimate — which closes a caret popup, exactly as scrolling does. A test about such a popup
     * lets the pass land first.
     */
    void awaitHighlighted(EditorBuffer buffer) throws Exception {
        assertTrue(
                highlighted.get(buffer).await(60, java.util.concurrent.TimeUnit.SECONDS),
                "the buffer was never highlighted");
        settle();
        layout();
    }

    /** Puts {@code buffer} in the window (on the FX thread) without opening it on a server. */
    void show(EditorBuffer buffer) {
        host.buffers.add(buffer);
        sceneRoot.getChildren().add(0, buffer.getNode());
    }

    FakeLanguageServer server() {
        assertFalse(fakes.isEmpty(), "no session was created");
        return fakes.get(fakes.size() - 1);
    }

    /** Runs {@code action} on the FX thread and lets the replies it caused arrive. */
    void run(Runnable action) throws Exception {
        FxTestSupport.runOnFx(action);
        settle();
    }

    /** Lets every reply already on its way reach the FX thread (each hop is one queued task). */
    void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
    }

    /** Moves the caret of {@code buffer} to a 0-based position. */
    void caret(EditorBuffer buffer, int line, int column) throws Exception {
        FxTestSupport.runOnFx(() -> buffer.getFocusedArea().moveTo(line, column));
        layout();
    }

    /**
     * Lays the window out now. A scene is otherwise laid out on the next pulse, whenever that comes — and an
     * editor's first layout moves its scroll estimate, which is one of the things that dismisses a popup.
     */
    void layout() throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            FxTestSupport.runOnFx(() -> {
                sceneRoot.applyCss();
                sceneRoot.layout();
            });
        }
    }

    // --- answering what a flow raises ------------------------------------------------------------------

    /** The card the overlay is showing (a picker), failing when nothing is up. */
    Node overlayCard() {
        assertTrue(host.overlay.isShowing(), "expected a picker to be showing");
        StackPane overlayRoot = FxTestSupport.field(host.overlay, "overlayRoot");
        return overlayRoot.getChildren().get(1);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    /** The rows of the showing single-choice picker, as labelled. FX thread. */
    List<String> pickerRows() {
        ListView<?> list = (ListView<?>) overlayCard().lookup(".list-view");
        assertNotNull(list, "the showing card has no list");
        List<String> out = new ArrayList<>();
        for (Object item : list.getItems()) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    /** Chooses row {@code index} of the showing single-choice picker and presses Enter. FX thread. */
    void choose(int index) {
        Node card = overlayCard();
        ListView<?> list = (ListView<?>) card.lookup(".list-view");
        TextField input = (TextField) card.lookup(".text-field");
        list.getSelectionModel().select(index);
        input.fireEvent(key(KeyCode.ENTER));
    }

    /** The checkbox rows of the showing multi-select picker. FX thread. */
    @SuppressWarnings("unchecked")
    List<CheckBox> checkboxes() {
        ListView<CheckBox> list = (ListView<CheckBox>) overlayCard().lookup(".list-view");
        return list.getItems();
    }

    /** Presses Enter on the showing multi-select picker, accepting what is ticked. FX thread. */
    void acceptTicked() {
        overlayCard().fireEvent(key(KeyCode.ENTER));
    }

    /** Dismisses whatever the overlay shows, as Escape does. FX thread. */
    void dismissOverlay() {
        host.overlay.hide();
    }

    /** The most recent prompt, failing when none was raised. */
    Prompt lastPrompt() {
        assertFalse(host.prompts.isEmpty(), "expected a text prompt");
        return host.prompts.get(host.prompts.size() - 1);
    }

    @Override
    public void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            host.buffers.forEach(EditorBuffer::dispose);
            stage.close();
        });
        manager.shutdownAll();
    }
}
