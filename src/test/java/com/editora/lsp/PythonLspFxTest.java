package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Python language server is told and asked (Python review B1, B5): the interpreter it analyses with
 * ({@code python.pythonPath}), pushed again when the project's pick changes, and Organize Imports sent the
 * way Pyright does it instead of jdtls's request.
 */
@Tag("fx")
class PythonLspFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private final List<LanguageServerSession> sessions = new CopyOnWriteArrayList<>();
    private volatile String interpreter = "/project/.venv/bin/python";
    private Path file;
    private ServerCapabilities capabilities = new ServerCapabilities();
    private String serverCommand = "pyright-langserver --stdio";

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            sessions.add(session);
            session.attachForTest(fake, capabilities);
        });
        manager.setPythonInterpreterResolver(r -> interpreter);
        file = root.resolve("app.py");
        Files.writeString(file, "import os\nimport json\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private FakeLanguageServer open() {
        manager.configure(true, Map.of("python", serverCommand));
        manager.openDocument(file, root, "python", "import os\nimport json\n");
        return fakes.get(0);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
        var f = new java.util.concurrent.CompletableFuture<T>();
        javafx.application.Platform.runLater(() -> {
            try {
                f.complete(task.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }

    private static List<Object> ask(LanguageServerSession session, String... sections) {
        var items = java.util.Arrays.stream(sections)
                .map(section -> {
                    var item = new ConfigurationItem();
                    item.setSection(section);
                    return item;
                })
                .toList();
        return session.configuration(new ConfigurationParams(items)).join();
    }

    private <T> T await(Consumer<Consumer<T>> request) throws Exception {
        var result = new AtomicReference<T>();
        var latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> request.accept(v -> {
            result.set(v);
            latch.countDown();
        }));
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the callback never fired");
        return result.get();
    }

    /** Pyright asks for the {@code python} section; without {@code pythonPath} it used whatever python3 PATH had. */
    @SuppressWarnings("unchecked")
    @Test
    void pyrightIsToldTheResolvedInterpreter() {
        open();
        LanguageServerSession session = sessions.get(0);
        List<Object> answer = ask(session, "python", "python.pythonPath");
        Map<String, Object> python = (Map<String, Object>) answer.get(0);
        assertEquals("/project/.venv/bin/python", python.get("pythonPath"));
        assertNotNull(python.get("analysis"), "the analysis settings are still sent");
        assertEquals("/project/.venv/bin/python", answer.get(1));
    }

    /** A changed pick reaches the running server as a configuration push carrying the new interpreter. */
    @SuppressWarnings("unchecked")
    @Test
    void aChangedPickIsPushedToTheRunningServer() throws Exception {
        FakeLanguageServer fake = open();
        int before = fake.configurations.size();
        interpreter = "/other/env/bin/python";
        manager.refreshPythonInterpreters();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fake.configurations.size() == before && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(fake.configurations.size() > before, "nothing was pushed");
        var settings = (Map<String, Object>) fake.configurations.getLast().getSettings();
        var python = (Map<String, Object>) settings.get("python");
        assertEquals("/other/env/bin/python", python.get("pythonPath"));
        assertEquals("/other/env/bin/python", manager.pythonPathFor(file));
    }

    /** No interpreter resolved: no {@code pythonPath} key, so Pyright keeps its own lookup. */
    @SuppressWarnings("unchecked")
    @Test
    void noInterpreterMeansNoPythonPathKey() {
        interpreter = null;
        open();
        Map<String, Object> python =
                (Map<String, Object>) ask(sessions.get(0), "python").get(0);
        assertFalse(python.containsKey("pythonPath"));
        assertNull(ask(sessions.get(0), "python.pythonPath").get(0));
    }

    /** Organize Imports on a Python file runs Pyright's command with the file's URI and applies its edit. */
    @Test
    void organizeImportsRunsPyrightsCommandAndAppliesTheEdit() throws Exception {
        FakeLanguageServer fake = open();
        String uri = file.toUri().toString();
        var edit = new WorkspaceEdit(Map.of(
                uri,
                List.of(new TextEdit(new Range(new Position(0, 0), new Position(1, 11)), "import json\nimport os"))));
        fake.executeCommandResponse = edit;
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(mapped);
            done.accept(true);
        });

        LspManager.OrganizeOutcome outcome =
                this.<LspManager.OrganizeOutcome>await(cb -> manager.organizeImportsAny(file, 1, 11, cb));

        assertEquals(LspManager.OrganizeOutcome.ORGANIZED, outcome);
        var sent = FakeLanguageServer.last(fake.executedCommands);
        assertEquals("pyright.organizeimports", sent.getCommand());
        assertEquals(uri, String.valueOf(sent.getArguments().get(0)).replace("\"", ""));
        assertNotNull(applied.get(), "the returned edit was applied");
        assertTrue(fake.rawRequests.stream().noneMatch(r -> r.method().startsWith("java/")), "no jdtls request");
    }

    /** Already sorted: Pyright answers with no edits, and the status must say so — not claim success. */
    @Test
    void organizeImportsWithNothingToDoIsUnchanged() throws Exception {
        FakeLanguageServer fake = open();
        fake.executeCommandResponse = new WorkspaceEdit(Map.of());
        assertEquals(
                LspManager.OrganizeOutcome.UNCHANGED,
                this.<LspManager.OrganizeOutcome>await(cb -> manager.organizeImportsAny(file, 1, 11, cb)));
    }

    /** A Python server without the command (pylsp, ruff server) is reported as unable, not as "already done". */
    @Test
    void organizeImportsOnAServerWithoutTheCommandIsUnsupported() throws Exception {
        serverCommand = "pylsp";
        FakeLanguageServer fake = open();
        fake.failEverything = true;
        assertEquals(
                LspManager.OrganizeOutcome.UNSUPPORTED,
                this.<LspManager.OrganizeOutcome>await(cb -> manager.organizeImportsAny(file, 1, 11, cb)));
    }

    /** basedpyright names the command after itself. */
    @Test
    void basedpyrightGetsItsOwnCommandName() {
        assertEquals(
                "basedpyright.organizeimports",
                LspManager.pyrightOrganizeCommand(List.of("basedpyright-langserver", "--stdio")));
        assertEquals("pyright.organizeimports", LspManager.pyrightOrganizeCommand(List.of("pyright-langserver")));
    }

    /** Any other server: its {@code source.organizeImports} code action, when it offers one. */
    @Test
    void otherServersUseTheirOrganizeImportsCodeAction() throws Exception {
        capabilities.setCodeActionProvider(true);
        Path ts = root.resolve("a.ts");
        Files.writeString(ts, "import b from 'b';\nimport a from 'a';\n");
        manager.configure(true, Map.of("typescript", "typescript-language-server --stdio"));
        manager.openDocument(ts, root, "typescript", "import b from 'b';\nimport a from 'a';\n");
        FakeLanguageServer fake = fakes.get(0);
        var action = new CodeAction("Organize Imports");
        action.setKind(CodeActionKind.SourceOrganizeImports);
        action.setEdit(new WorkspaceEdit(Map.of(
                ts.toUri().toString(), List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "")))));
        fake.codeActionResponse = List.of(Either.forRight(action));
        manager.setApplyEditHandler((mapped, done) -> done.accept(true));

        assertEquals(
                LspManager.OrganizeOutcome.ORGANIZED,
                this.<LspManager.OrganizeOutcome>await(cb -> manager.organizeImportsAny(ts, 1, 18, cb)));

        fake.codeActionResponse = List.of();
        assertEquals(
                LspManager.OrganizeOutcome.UNSUPPORTED,
                this.<LspManager.OrganizeOutcome>await(cb -> manager.organizeImportsAny(ts, 1, 18, cb)));
    }

    /**
     * With no project the Project panel's watcher is not there, and nothing told the server that git or a
     * terminal created a module: it stayed unresolved until a restart (review B7). The server root is watched.
     */
    @Test
    void aModuleCreatedOnDiskReachesTheServerWithoutAProject() throws Exception {
        manager.setWatchSessionRoots(r -> true);
        FakeLanguageServer fake = open();
        Files.createDirectories(root.resolve(".venv"));
        Path module = Files.writeString(root.resolve("newmod.py"), "def nm() -> int:\n    return 2\n");
        String uri = module.toUri().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean seen = false;
        while (!seen && System.nanoTime() < deadline) {
            Thread.sleep(50);
            seen = onFx(() -> List.copyOf(fake.watchedFiles)).stream()
                    .flatMap(p -> p.getChanges().stream())
                    .anyMatch(e -> e.getUri().equals(uri) && e.getType() == org.eclipse.lsp4j.FileChangeType.Created);
        }
        assertTrue(seen, "a Created event for the new module: " + fake.watchedFiles);
        assertTrue(manager.watchedRoots().contains(root.toAbsolutePath().normalize()));
        manager.close();
        assertTrue(manager.watchedRoots().isEmpty(), "closed with the window");
    }

    /** Turning language servers off stops their folder watchers too (each one an inotify instance on Linux). */
    @Test
    void turningLanguageServersOffStopsTheFolderWatcher() throws Exception {
        manager.setWatchSessionRoots(r -> true);
        open();
        assertTrue(manager.watchedRoots().contains(root.toAbsolutePath().normalize()), "precondition: watched");
        manager.configure(false, null);
        assertTrue(manager.watchedRoots().isEmpty(), "nothing is watched for servers that are off");
    }

    /** Folders a server never reads are not watched: hidden ones, packages, caches, environments. */
    @Test
    void foldersNoServerReadsAreSkipped() throws Exception {
        Path venv = Files.createDirectories(root.resolve("env"));
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr\n");
        assertTrue(RootWatcher.skipped(venv));
        assertTrue(RootWatcher.skipped(root.resolve(".git")));
        assertTrue(RootWatcher.skipped(root.resolve("node_modules")));
        assertTrue(RootWatcher.skipped(root.resolve("__pycache__")));
        assertFalse(RootWatcher.skipped(Files.createDirectories(root.resolve("pkg"))));
    }
}
