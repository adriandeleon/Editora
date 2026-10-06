package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a Java debug session is actually started with: {@link DapManager} driven end to end against a jdtls
 * that answers the java-debug commands the way the real one does (shapes recorded from jdtls + java-debug
 * 0.53.2) and a debug adapter that records the {@code launch}/{@code attach} it receives.
 *
 * <p>Each case is a launch that reached the adapter with the wrong request: no project to evaluate against
 * (so every breakpoint condition counted as met), an empty class path, the wrong working directory, no
 * {@code --enable-preview}.
 */
@Tag("fx")
class JavaDebugLaunchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<ExecuteCommandParams> commands = new CopyOnWriteArrayList<>();
    /** Replies by command; a missing one answers null, like a command with nothing to report. */
    private final Map<String, Function<ExecuteCommandParams, Object>> replies = new java.util.HashMap<>();

    private final DapManager.MainClassPicker noPicker = (options, chosen) -> {
        errors.add("the main-class picker was shown with " + options.size() + " options");
        chosen.accept(null);
    };

    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeDebugAdapter(false);
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(lsp);
        lsp.configure(true, Map.of("java", "jdtls"));
        dap = new DapManager(lsp);
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString());
            dap.setServerProvidesJavaDebug(true); // as a jdtls that ships java-debug itself
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

                @Override
                public void onOutput(String text, String category) {}

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
        });
        replies.put("vscode.java.startDebugSession", p -> adapter.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread ran the action");
    }

    /** Opens {@code file} on the fake jdtls, as a tab does. */
    private void open(Path file, Path root, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        lsp.openDocument(file, root, "java", source);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                commands.add(params);
                Function<ExecuteCommandParams, Object> reply = replies.get(params.getCommand());
                return reply == null ? null : reply.apply(params);
            };
        }
    }

    /** The {@code launch}/{@code attach} request the adapter received. */
    private Map<String, Object> request(String kind) throws Exception {
        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            throw new AssertionError(
                    "no debug session was started; errors=" + errors + " commands=" + commandNames(), e);
        }
        session.awaitRequest(kind);
        assertEquals(List.of(), errors);
        return session.launchArgs;
    }

    private List<String> commandNames() {
        return commands.stream().map(ExecuteCommandParams::getCommand).toList();
    }

    private ExecuteCommandParams command(String name) {
        return commands.stream()
                .filter(c -> name.equals(c.getCommand()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " was never sent; sent " + commandNames()));
    }

    private static Map<String, Object> mainClass(String mainClass, String project, Path file) {
        return Map.of("mainClass", mainClass, "projectName", project, "filePath", file.toString());
    }

    private static String javaExec() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    // --- attach ---------------------------------------------------------------------------------------

    /**
     * Debug Test attaches through a test class, which has no {@code main}: the project comes from the element
     * at its type declaration. Without a project java-debug evaluates nothing — and stops on every hit of a
     * conditional breakpoint.
     */
    @Test
    void anAttachNamesTheProjectOfTheFileItIsRoutedThrough() throws Exception {
        Path test = dir.resolve("src/test/java/demo/LoopTest.java");
        open(test, dir, "package demo;\n\n/** class Fake {} */\npublic class LoopTest {\n}\n");
        replies.put("vscode.java.resolveMainMethod", p -> List.of());
        replies.put(
                "vscode.java.resolveElementAtSelection",
                p -> Map.of("declaringType", "demo.LoopTest", "projectName", "myproj", "hasMainMethod", false));

        onFx(() -> dap.startAttach(test, "localhost", 5005));

        Map<String, Object> attach = request("attach");
        assertEquals("myproj", attach.get("projectName"));
        assertEquals("localhost", attach.get("hostName"));
        List<Object> at = command("vscode.java.resolveElementAtSelection").getArguments();
        assertEquals(test.toUri().toString(), String.valueOf(at.get(0)).replace("\"", ""));
        assertEquals("3", String.valueOf(at.get(1)), "the line of the type declaration, not of the comment");
        assertEquals("13", String.valueOf(at.get(2)), "the column of the type's name");
    }

    /** A jdtls that cannot name the project still attaches — as before, without one. */
    @Test
    void anAttachWithNoResolvableProjectStillAttaches() throws Exception {
        Path file = dir.resolve("Loose.java");
        open(file, dir, "void main() {}\n");

        onFx(() -> dap.startAttach(file, "example.test", 8000));

        Map<String, Object> attach = request("attach");
        assertFalse(attach.containsKey("projectName"));
        assertEquals("example.test", attach.get("hostName"));
    }

    // --- loose and compact files ---------------------------------------------------------------------

    /**
     * jdtls lists a loose file's {@code main} from an invisible project whose output folder stays empty
     * (autobuild is off): launching on that class path was a guaranteed {@code ClassNotFoundException}.
     */
    @Test
    void aLooseFileWithAMainIsCompiledWhenJdtlsHasNoClassFileForIt() throws Exception {
        Path loose = Files.createDirectories(dir.resolve("loose"));
        Path file = loose.resolve("Second.java");
        open(file, loose, "public class Second {\n    public static void main(String[] args) {}\n}\n");
        Path emptyBin = Files.createDirectories(dir.resolve("jdtls-ws/loose_1a2b/bin"));
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("Second", "loose_1a2b", file)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(emptyBin.toString())));
        replies.put("vscode.java.resolveJavaExecutable", p -> javaExec());
        replies.put(
                "vscode.java.resolveMainMethod",
                p -> List.of(Map.of("mainClass", "Second", "projectName", "loose_1a2b")));

        onFx(() -> dap.startLaunch(file, "java", noPicker, javaExec(), null));

        Map<String, Object> launch = request("launch");
        assertEquals("Second", launch.get("mainClass"));
        List<?> classPaths = (List<?>) launch.get("classPaths");
        assertEquals(1, classPaths.size());
        assertTrue(
                Files.isRegularFile(Path.of(String.valueOf(classPaths.get(0))).resolve("Second.class")),
                "the class path holds the compiled class: " + classPaths);
        assertEquals("loose_1a2b", launch.get("projectName"), "so conditions and Evaluate have a project");
        assertEquals(loose.toString(), launch.get("cwd"));
    }

    /** A loose file whose class jdtls <em>does</em> have (an Eclipse project it built) launches as resolved. */
    @Test
    void aLooseFileWithACompiledClassLaunchesOnTheResolvedClassPath() throws Exception {
        Path loose = Files.createDirectories(dir.resolve("eclipse"));
        Path file = loose.resolve("Second.java");
        open(file, loose, "public class Second {\n    public static void main(String[] args) {}\n}\n");
        Path bin = Files.createDirectories(loose.resolve("bin"));
        Files.writeString(bin.resolve("Second.class"), "compiled");
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("Second", "eclipse", file)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(bin.toString())));

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", null));

        assertEquals(List.of(bin.toString()), request("launch").get("classPaths"));
    }

    /** A compact source is compiled to a temp dir; the project still has to be named for evaluation. */
    @Test
    void aCompactSourceLaunchNamesTheProjectJdtlsKeepsItIn() throws Exception {
        assumeTrue(Runtime.version().feature() >= 25);
        Path file = dir.resolve("Tiny.java");
        open(file, dir, "void main() {\n    for (int i = 0; i < 3; i++) {\n        IO.println(i);\n    }\n}\n");
        replies.put(
                "vscode.java.resolveMainMethod", p -> List.of(Map.of("mainClass", "Tiny", "projectName", "tiny_9f8e")));

        onFx(() -> dap.startCompactSource(file, javaExec()));

        Map<String, Object> launch = request("launch");
        assertEquals("Tiny", launch.get("mainClass"));
        assertEquals("tiny_9f8e", launch.get("projectName"));
    }

    // --- project files ---------------------------------------------------------------------------------

    private Path projectFile(String name) throws Exception {
        Path file = dir.resolve("proj/src/main/java/demo/" + name + ".java");
        open(file, dir.resolve("proj"), "package demo;\npublic class " + name + " {\n}\n");
        Path classes = Files.createDirectories(dir.resolve("proj/target/classes"));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(classes.toString())));
        return file;
    }

    /** Debug on a project file ran in the source package folder; Run and the gutter use the project root. */
    @Test
    void aProjectFileRunsInTheProjectRoot() throws Exception {
        Path file = projectFile("Args");
        Path root = dir.resolve("proj");
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("demo.Args", "proj", file)));

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        Map<String, Object> launch = request("launch");
        assertEquals(root.toString(), launch.get("cwd"));
        assertEquals("proj", launch.get("projectName"));
        assertFalse(launch.containsKey("vmArgs"), "nothing is added for a project without preview features");
        assertFalse(launch.containsKey("shortenCommandLine"));
    }

    /** A project built with {@code --enable-preview} needs the flag to load its own classes. */
    @Test
    void aProjectCompiledWithPreviewFeaturesIsLaunchedWithTheFlag() throws Exception {
        Path file = projectFile("Prev");
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("demo.Prev", "proj", file)));
        replies.put("vscode.java.checkProjectSettings", p -> Boolean.TRUE);

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", dir.resolve("proj")));

        assertEquals("--enable-preview", request("launch").get("vmArgs"));
        Object query =
                command("vscode.java.checkProjectSettings").getArguments().get(0);
        String json = query instanceof com.google.gson.JsonPrimitive s ? s.getAsString() : String.valueOf(query);
        var parsed = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertEquals("demo.Prev", parsed.get("className").getAsString(), "java-debug takes one JSON string");
        assertEquals("proj", parsed.get("projectName").getAsString());
    }

    /**
     * jdtls reports real paths. A project opened through a symlink never matched its own file, so Debug showed
     * the main-class picker — or silently launched the project's only other main class.
     */
    @Test
    void aFileReachedThroughASymlinkStillLaunchesItsOwnMainClass() throws Exception {
        Path real = Files.createDirectories(dir.resolve("real"));
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (Exception e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }
        Path file = link.resolve("src/main/java/demo/Loop.java");
        open(file, link, "package demo;\npublic class Loop {\n}\n");
        Path realFile = real.resolve("src/main/java/demo/Loop.java");
        Path classes = Files.createDirectories(real.resolve("target/classes"));
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(
                        mainClass("demo.Args", "proj", real.resolve("src/main/java/demo/Args.java")),
                        mainClass("demo.Loop", "proj", realFile)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(classes.toString())));

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", link));

        assertEquals("demo.Loop", request("launch").get("mainClass"));
    }

    /** The module-qualified name jdtls answers is what the launch needs; only matching uses the class part. */
    @Test
    void aMainClassOfANamedModuleIsMatchedByItsClassName() {
        var option = new DapManager.MainClassOption("app.core/app.core.ModMain", "modproj", null);
        assertEquals("app.core.ModMain", option.className());
        assertEquals("app.core/app.core.ModMain", option.mainClass());
        assertEquals("demo.Args", new DapManager.MainClassOption("demo.Args", "p", null).className());
        assertNull(new DapManager.MainClassOption(null, "p", null).className());
    }
}
