package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.externaltool.ExternalTool;
import com.editora.externaltool.ToolInvocation;
import com.editora.process.ProcessRunner;
import com.editora.run.StackTraceLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External Tools as the user reaches them — the palette command, the picker, the editor menu, "run the last
 * one again" — and where a finished tool's output goes. The tool that really runs is the JVM printing its
 * version; every other outcome is fed to the result handler directly.
 */
@Tag("fx")
class ExternalToolFlowFxTest {

    private static final long WAIT_SECONDS = 60;

    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private Host host;
    private Ops ops;
    private ExternalToolCoordinator coordinator;
    private CommandRegistry registry;
    private StackPane overlayRoot;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        host = new Host();
        ops = new Ops();
        registry = new CommandRegistry();
        coordinator = FxTestSupport.callOnFx(() -> {
            overlayRoot = new StackPane();
            host.overlay.install(overlayRoot);
            return new ExternalToolCoordinator(host, ops);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            host.overlay.hide();
            buffers.forEach(EditorBuffer::dispose);
        });
    }

    private static ExternalTool tool(String name, String command, String args, ExternalTool.OutputTarget target) {
        return new ExternalTool(name, command, args, "", ExternalTool.StdinSource.NONE, target, true);
    }

    private EditorBuffer buffer(Path file, String content) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            if (file != null) {
                created.setPath(file);
            }
            created.setContent(content);
            created.markClean();
            return created;
        });
        buffers.add(b);
        return b;
    }

    private String consoleText() throws Exception {
        org.fxmisc.richtext.CodeArea output = FxTestSupport.field(coordinator.panel(), "output");
        return FxTestSupport.callOnFx(output::getText);
    }

    private void apply(ExternalTool tool, ProcessRunner.Result result, EditorBuffer target) throws Exception {
        ExternalToolCoordinator.Launch launch = FxTestSupport.callOnFx(() -> ExternalToolCoordinator.Launch.of(target));
        apply(tool, result, launch);
    }

    private void apply(ExternalTool tool, ProcessRunner.Result result, ExternalToolCoordinator.Launch launch)
            throws Exception {
        ToolInvocation invocation =
                new ToolInvocation(List.of(tool.getName()), Path.of("."), "", "$ " + tool.getName());
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                coordinator,
                "applyResult",
                new Class<?>[] {
                    ExternalTool.class,
                    ToolInvocation.class,
                    ProcessRunner.Result.class,
                    ExternalToolCoordinator.Launch.class
                },
                tool,
                invocation,
                result,
                launch));
    }

    @Test
    void aToolRunsFromItsOwnCommandThePickerTheMenuAndRerun(@TempDir Path dir) throws Exception {
        ExternalTool version = tool("Java Version", JAVA, "-version", ExternalTool.OutputTarget.CONSOLE);
        ExternalTool off = new ExternalTool(
                "Switched Off",
                JAVA,
                "-version",
                "",
                ExternalTool.StdinSource.NONE,
                ExternalTool.OutputTarget.CONSOLE,
                false);
        ExternalTool unnamed = tool("  ", JAVA, "-version", ExternalTool.OutputTarget.CONSOLE);
        host.settings.setExternalTools(List.of(version, off, unnamed));
        host.active = buffer(dir.resolve("Main.java"), "class Main {}\n");
        FxTestSupport.runOnFx(() -> coordinator.registerCommands(registry));

        // Nothing has run yet.
        FxTestSupport.runOnFx(() -> registry.run("externalTool.rerunLast"));
        assertEquals(tr("status.externalTool.noLast"), host.last);

        // Its own palette command (only enabled, named tools get one).
        String id = ExternalTool.commandIdFor("Java Version");
        assertEquals(
                List.of(id),
                registry.all().stream()
                        .map(Command::id)
                        .filter(c -> c.startsWith("externalTool.run."))
                        .toList());
        host.statuses.clear();
        FxTestSupport.runOnFx(() -> registry.run(id));
        assertEquals(tr("status.externalTool.running", "Java Version"), host.await());
        assertEquals(tr("status.externalTool.done", "Java Version"), host.await());
        assertEquals(1, ops.consoleOpened);
        String console = consoleText();
        assertTrue(console.contains("version"), console);

        // The picker lists the same tools, and Enter runs the selected one.
        FxTestSupport.runOnFx(() -> registry.run("externalTool.run"));
        Node card = FxTestSupport.callOnFx(() -> overlayRoot.lookup(".command-palette"));
        assertNotNull(card, "no picker");
        ListView<?> list = (ListView<?>) FxTestSupport.callOnFx(() -> card.lookup(".list-view"));
        assertEquals(List.of(version), FxTestSupport.callOnFx(() -> List.copyOf(list.getItems())));
        FxTestSupport.runOnFx(() -> Event.fireEvent(
                card.lookup(".text-field"),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        assertEquals(tr("status.externalTool.running", "Java Version"), host.await());
        assertEquals(tr("status.externalTool.done", "Java Version"), host.await());

        // The editor's right-click submenu.
        List<MenuItem> menu = FxTestSupport.callOnFx(coordinator::editorMenuItems);
        assertEquals(1, menu.size());
        Menu submenu = (Menu) menu.get(0);
        assertEquals(tr("editmenu.externalTools"), submenu.getText());
        assertEquals(
                List.of("Java Version"),
                submenu.getItems().stream().map(MenuItem::getText).toList());
        FxTestSupport.runOnFx(() -> submenu.getItems().get(0).fire());
        host.await();
        assertEquals(tr("status.externalTool.done", "Java Version"), host.await());

        // Run it again.
        FxTestSupport.runOnFx(() -> registry.run("externalTool.rerunLast"));
        host.await();
        assertEquals(tr("status.externalTool.done", "Java Version"), host.await());
        assertEquals(4, ops.consoleOpened);

        // Clearing the console is a command too.
        FxTestSupport.runOnFx(() -> registry.run("externalTool.clearOutput"));
        assertFalse(consoleText().contains("version"));

        // Once the tool is removed from Settings, its command goes and "rerun" has nothing to run.
        host.settings.setExternalTools(List.of(tool("Other", JAVA, "-version", ExternalTool.OutputTarget.CONSOLE)));
        FxTestSupport.runOnFx(coordinator::refreshCommands);
        assertTrue(registry.get(id).isEmpty());
        assertTrue(registry.get(ExternalTool.commandIdFor("Other")).isPresent());
        FxTestSupport.runOnFx(() -> registry.run("externalTool.rerunLast"));
        assertEquals(tr("status.externalTool.noLast"), host.last);
    }

    @Test
    void withNoToolsThereIsNothingToPickOrShow() throws Exception {
        host.settings.setExternalTools(List.of());
        FxTestSupport.runOnFx(coordinator::refreshCommands); // before any registry exists: nothing to refresh
        FxTestSupport.runOnFx(() -> coordinator.registerCommands(registry));
        FxTestSupport.runOnFx(() -> registry.run("externalTool.run"));
        assertEquals(tr("status.externalTool.none"), host.last);
        assertNull(FxTestSupport.callOnFx(() -> overlayRoot.lookup(".command-palette")));
        assertEquals(List.of(), FxTestSupport.callOnFx(coordinator::editorMenuItems));
    }

    @Test
    void simpleModeSwitchesTheFeatureOff() throws Exception {
        host.settings.setExternalTools(
                List.of(tool("Java Version", JAVA, "-version", ExternalTool.OutputTarget.CONSOLE)));
        FxTestSupport.runOnFx(() -> coordinator.registerCommands(registry));
        host.simple = true;
        assertFalse(coordinator.isEnabled());
        FxTestSupport.runOnFx(() -> {
            registry.run("externalTool.run");
            registry.run(ExternalTool.commandIdFor("Java Version"));
        });
        assertNull(host.last, "nothing ran and nothing was said");
        assertEquals(List.of(), FxTestSupport.callOnFx(coordinator::editorMenuItems));
        assertNull(FxTestSupport.callOnFx(() -> overlayRoot.lookup(".command-palette")));
    }

    @Test
    void aToolIsNotStartedOnARemoteFileOrWithoutACommand(@TempDir Path dir) throws Exception {
        host.settings.setExternalTools(List.of(
                tool("Java Version", JAVA, "-version", ExternalTool.OutputTarget.CONSOLE),
                tool("Empty", "   ", "", ExternalTool.OutputTarget.CONSOLE)));
        FxTestSupport.runOnFx(() -> coordinator.registerCommands(registry));
        host.active = buffer(dir.resolve("remote.txt"), "x");
        host.local = false;
        FxTestSupport.runOnFx(() -> registry.run(ExternalTool.commandIdFor("Java Version")));
        assertEquals(tr("status.externalTool.remoteUnsupported"), host.last);

        host.local = true;
        FxTestSupport.runOnFx(() -> registry.run(ExternalTool.commandIdFor("Empty")));
        assertEquals(tr("status.externalTool.noCommand", "Empty"), host.last);
        assertEquals(0, ops.consoleOpened);
    }

    @Test
    void aToolCanRunWithNoEditorInFrontUsingTheProjectFolder(@TempDir Path dir) throws Exception {
        host.settings.setExternalTools(
                List.of(tool("Java Version", JAVA, "-version", ExternalTool.OutputTarget.CONSOLE)));
        FxTestSupport.runOnFx(() -> coordinator.registerCommands(registry));
        ops.root = dir;
        host.active = null;
        FxTestSupport.runOnFx(() -> registry.run(ExternalTool.commandIdFor("Java Version")));
        host.await();
        assertEquals(tr("status.externalTool.done", "Java Version"), host.await());

        // And with an unsaved buffer, which has no folder of its own.
        host.active = buffer(null, "draft");
        FxTestSupport.runOnFx(() -> registry.run(ExternalTool.commandIdFor("Java Version")));
        host.await();
        assertEquals(tr("status.externalTool.done", "Java Version"), host.await());
    }

    @Test
    void consoleOutputIsShownWhetherTheToolSucceededOrNot() throws Exception {
        ExternalTool lint = tool("Lint", "lint", "", ExternalTool.OutputTarget.CONSOLE);
        apply(lint, new ProcessRunner.Result(0, "all good\n", ""), (EditorBuffer) null);
        assertEquals(tr("status.externalTool.done", "Lint"), host.last);
        assertTrue(consoleText().contains("all good"));

        apply(lint, new ProcessRunner.Result(2, "", "line 3: bad\n"), (EditorBuffer) null);
        assertEquals(tr("status.externalTool.failed", "Lint", "line 3: bad"), host.last);
        assertTrue(consoleText().contains("line 3: bad"));
        assertEquals(2, ops.consoleOpened);
    }

    @Test
    void aFailedFilterLeavesTheDocumentAloneAndShowsItsError() throws Exception {
        EditorBuffer doc = buffer(null, "keep me\n");
        ExternalTool fmt = tool("Format", "fmt", "", ExternalTool.OutputTarget.REPLACE_BUFFER);
        apply(fmt, new ProcessRunner.Result(1, "half formatted", "syntax error"), doc);
        assertEquals(tr("status.externalTool.failed", "Format", "syntax error"), host.last);
        assertEquals("keep me\n", FxTestSupport.callOnFx(doc::getContent));
        assertEquals(1, ops.consoleOpened);
        assertTrue(consoleText().contains("syntax error"));
    }

    @Test
    void aSilentFilterIsNotAFailureAndBlanksNothing() throws Exception {
        EditorBuffer doc = buffer(null, "keep me\n");
        ExternalTool fmt = tool("Format", "fmt", "", ExternalTool.OutputTarget.REPLACE_BUFFER);
        apply(fmt, new ProcessRunner.Result(0, "", ""), doc);
        assertEquals(tr("status.externalTool.noOutput", "Format"), host.last);
        assertEquals("keep me\n", FxTestSupport.callOnFx(doc::getContent));
        assertEquals(0, ops.consoleOpened);
    }

    @Test
    void outputIsNotAppliedWithoutAnEditableDocumentOrToOneThatChangedMeanwhile() throws Exception {
        ExternalTool fmt = tool("Format", "fmt", "", ExternalTool.OutputTarget.REPLACE_BUFFER);
        apply(fmt, new ProcessRunner.Result(0, "new text", ""), (EditorBuffer) null);
        assertEquals(tr("status.externalTool.notEditable"), host.last);

        EditorBuffer readOnly = buffer(null, "locked\n");
        FxTestSupport.runOnFx(() -> readOnly.setViewMode(true));
        apply(fmt, new ProcessRunner.Result(0, "new text", ""), readOnly);
        assertEquals(tr("status.externalTool.notEditable"), host.last);
        assertEquals("locked\n", FxTestSupport.callOnFx(readOnly::getContent));

        // The user typed while the tool was running: its output was computed from text that is gone.
        EditorBuffer doc = buffer(null, "before\n");
        ExternalToolCoordinator.Launch launch = FxTestSupport.callOnFx(() -> ExternalToolCoordinator.Launch.of(doc));
        FxTestSupport.runOnFx(() -> doc.getArea().insertText(0, "typed "));
        apply(fmt, new ProcessRunner.Result(0, "formatted before\n", ""), launch);
        assertEquals(tr("status.externalTool.bufferChanged", "Format"), host.last);
        assertEquals("typed before\n", FxTestSupport.callOnFx(doc::getContent));
        assertEquals(1, ops.consoleOpened, "the output is kept where it can be read");
        assertTrue(consoleText().contains("formatted before"));
    }

    @Test
    void outputGoesToTheBufferTheSelectionOrTheCaret() throws Exception {
        EditorBuffer whole = buffer(null, "old\n");
        apply(
                tool("Format", "fmt", "", ExternalTool.OutputTarget.REPLACE_BUFFER),
                new ProcessRunner.Result(0, "new\n", ""),
                whole);
        assertEquals("new\n", FxTestSupport.callOnFx(whole::getContent), "a whole-buffer result keeps its newline");
        assertEquals(tr("status.externalTool.done", "Format"), host.last);

        // At the caret, without the newline the tool ended its output with — whichever kind it used.
        EditorBuffer caret = buffer(null, "ab");
        FxTestSupport.runOnFx(() -> caret.getArea().moveTo(1));
        ExternalTool insert = tool("Date", "date", "", ExternalTool.OutputTarget.INSERT_AT_CARET);
        apply(insert, new ProcessRunner.Result(0, "X\n", ""), caret);
        assertEquals("aXb", FxTestSupport.callOnFx(caret::getContent));
        apply(insert, new ProcessRunner.Result(0, "Y\r\n", ""), caret);
        apply(insert, new ProcessRunner.Result(0, "Z", ""), caret);
        assertEquals("aXYZb", FxTestSupport.callOnFx(caret::getContent));
        apply(insert, new ProcessRunner.Result(0, "W\r", ""), caret);
        assertEquals("aXYZWb", FxTestSupport.callOnFx(caret::getContent));
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final OverlayHost overlay = new OverlayHost();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;
        volatile boolean local = true;
        volatile boolean simple;
        volatile String last;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simple;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer buffer) {
            return local;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public void setStatus(String message) {
            last = message;
            statuses.add(message);
        }

        @Override
        public void setError(String message) {
            errors.add(message);
        }

        String await() throws InterruptedException {
            String status = statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(status, "no status message arrived");
            return status;
        }
    }

    private static final class Ops implements ExternalToolCoordinator.Ops {
        volatile Path root;
        volatile int consoleOpened;

        @Override
        public Path projectRoot() {
            return root;
        }

        @Override
        public void openConsole() {
            consoleOpened++;
        }

        @Override
        public void onOutputLink(StackTraceLinks.Link link) {}
    }
}
