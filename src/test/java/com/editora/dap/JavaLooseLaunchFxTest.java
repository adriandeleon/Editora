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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java files outside any Maven/Gradle project, launched the way the real jdtls answers for them (shapes
 * recorded from jdtls 1.61 + java-debug 0.53.2): what the adapter is asked to run, what jdtls is told about
 * the source folder, and what the user is shown when the file does not compile.
 */
@Tag("fx")
class JavaLooseLaunchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
        com.editora.i18n.Messages.init("en");
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final List<ExecuteCommandParams> commands = new CopyOnWriteArrayList<>();
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
            dap.setServerProvidesJavaDebug(true);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

                @Override
                public void onOutput(String text, String category) {
                    console.add(category + ":" + text);
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
        });
        replies.put("vscode.java.startDebugSession", p -> adapter.port());
        replies.put("vscode.java.resolveJavaExecutable", p -> javaExec());
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "the FX thread ran the action");
    }

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

    private Map<String, Object> launch() throws Exception {
        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            throw new AssertionError("no debug session was started; errors=" + errors + " commands=" + names(), e);
        }
        session.awaitRequest("launch");
        assertEquals(List.of(), errors);
        return session.launchArgs;
    }

    private List<String> names() {
        return commands.stream().map(ExecuteCommandParams::getCommand).toList();
    }

    private List<Object> args(String command) {
        return commands.stream()
                .filter(c -> command.equals(c.getCommand()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(command + " was never sent; sent " + names()))
                .getArguments();
    }

    private static String arg(Object o) {
        return String.valueOf(o).replace("\"", "");
    }

    private static Map<String, Object> mainClass(String mainClass, String project, Path file) {
        return Map.of("mainClass", mainClass, "projectName", project, "filePath", file.toString());
    }

    private static String javaExec() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(20);
        }
    }

    /** The session's class folder holds the class file of {@code fqn}. */
    private static boolean holdsClass(Map<String, Object> launch, String fqn) {
        List<?> classPaths = (List<?>) launch.get("classPaths");
        return classPaths.stream()
                .anyMatch(
                        p -> Files.isRegularFile(Path.of(String.valueOf(p)).resolve(fqn.replace('.', '/') + ".class")));
    }

    // --- A1: a packaged class in a plain folder ------------------------------------------------------

    /**
     * jdtls guessed {@code src/demo} as the source folder of {@code src/demo/Main.java} (package {@code demo})
     * and named the class {@code Main}: the launch asked for a class that does not exist. Now jdtls is told the
     * right folder, and the class is launched by the package the file declares.
     */
    @Test
    void aPackagedClassInAPlainFolderLaunchesByItsPackageAndFixesJdtlsSourceFolder() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path file = project.resolve("src/demo/Main.java");
        Path helper = project.resolve("src/demo/Helper.java");
        Files.createDirectories(helper.getParent());
        Files.writeString(helper, "package demo;\nclass Helper { static int one() { return 1; } }\n");
        open(
                file,
                project,
                "package demo;\n\npublic class Main {\n    public static void main(String[] args) {\n"
                        + "        System.out.println(Helper.one());\n    }\n}\n");
        Path emptyBin = Files.createDirectories(dir.resolve("ws/project_1/bin"));
        replies.put(
                "java.project.listSourcePaths",
                p -> Map.of(
                        "status",
                        true,
                        "data",
                        List.of(Map.of("path", file.getParent().toString()))));
        replies.put("java.project.addToSourcePath", p -> Map.of("status", true));
        replies.put("java.project.removeFromSourcePath", p -> Map.of("status", true));
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("Main", "project_1", file)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(emptyBin.toString())));

        onFx(() -> dap.startLaunch(file, "java", noPicker, javaExec(), null));

        Map<String, Object> launch = launch();
        assertEquals("demo.Main", launch.get("mainClass"), "the class the file declares, not jdtls's guess");
        assertTrue(holdsClass(launch, "demo.Main"), "compiled with its package's other classes: " + launch);
        assertEquals(
                project.resolve("src").toUri().toString(),
                arg(args("java.project.addToSourcePath").get(0)));
        assertEquals(
                file.getParent().toUri().toString(),
                arg(args("java.project.removeFromSourcePath").get(0)));
        List<String> order = names();
        assertTrue(
                order.indexOf("java.project.removeFromSourcePath") < order.indexOf("vscode.java.resolveMainClass"),
                "jdtls knows the right folder before it is asked for the main class: " + order);
    }

    /**
     * In a No Project window jdtls's workspace is the file's own folder: the right source folder is outside it
     * and cannot be added. The wrong one must then stay (removing it left jdtls with no folder at all), and the
     * launch still runs the declared class.
     */
    @Test
    void aSourceFolderJdtlsCannotTakeLeavesItsGuessAlone() throws Exception {
        Path file = dir.resolve("loose/src/demo/Main.java");
        open(
                file,
                file.getParent(),
                "package demo;\npublic class Main {\n    public static void main(String[] a) {}\n}\n");
        Path emptyBin = Files.createDirectories(dir.resolve("ws/demo_1/bin"));
        replies.put(
                "java.project.listSourcePaths",
                p -> Map.of(
                        "status",
                        true,
                        "data",
                        List.of(Map.of("path", file.getParent().toString()))));
        replies.put("java.project.addToSourcePath", p -> Map.of("status", false, "message", "not in a workspace"));
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("Main", "demo_1", file)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(emptyBin.toString())));

        onFx(() -> dap.startLaunch(file, "java", noPicker, javaExec(), null));

        Map<String, Object> launch = launch();
        assertEquals("demo.Main", launch.get("mainClass"));
        assertFalse(names().contains("java.project.removeFromSourcePath"), names().toString());
    }

    // --- A3: the file's own main ------------------------------------------------------------------------

    /** A script beside a Maven project, with its own main: the project's only main class used to run instead. */
    @Test
    void aFileWithItsOwnMainOutsideTheSourceFoldersDebugsThatFile() throws Exception {
        Path project = Files.createDirectories(dir.resolve("proj"));
        Path tool = project.resolve("scripts/Tool.java");
        open(tool, project, "public class Tool {\n    public static void main(String[] args) {}\n}\n");
        Path appMain = project.resolve("src/main/java/app/Main.java");
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("app.Main", "app", appMain)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(classes.toString())));

        onFx(() -> dap.startLaunch(tool, "java", noPicker, javaExec(), project));

        Map<String, Object> launch = launch();
        assertEquals("Tool", launch.get("mainClass"));
        assertTrue(holdsClass(launch, "Tool"));
        assertEquals(project.toString(), launch.get("cwd"), "a project's program runs in the project root");
    }

    /**
     * Debug from a plain-folder file with no main: the folder's only main class is debugged — compiled first
     * (jdtls never builds it), and said so — where it used to end in ClassNotFoundException.
     */
    @Test
    void aFileWithoutMainDebugsTheFoldersOnlyMainCompiledAndSaysSo() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("plain"));
        Path main = folder.resolve("Main.java");
        Files.writeString(main, "public class Main {\n    public static void main(String[] args) {}\n}\n");
        Path util = folder.resolve("Util.java");
        open(util, folder, "public class Util {\n    static int one() { return 1; }\n}\n");
        Path emptyBin = Files.createDirectories(dir.resolve("ws/plain_1/bin"));
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("Main", "plain_1", main)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(emptyBin.toString())));

        onFx(() -> dap.startLaunch(util, "java", noPicker, javaExec(), null));

        Map<String, Object> launch = launch();
        assertEquals("Main", launch.get("mainClass"));
        assertTrue(holdsClass(launch, "Main"), "Main was compiled: " + launch);
        assertTrue(
                console.stream()
                        .anyMatch(l -> l.startsWith("console:") && l.contains("Util.java") && l.contains("Main")),
                "the console says which program runs: " + console);
    }

    // --- A5: compile errors ---------------------------------------------------------------------------

    /** javac's report goes to the Debug console, where its file:line opens the file; the status says where. */
    @Test
    void aCompileErrorIsShownInTheConsole() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("broken"));
        Path file = folder.resolve("Broken.java");
        open(
                file,
                folder,
                "public class Broken {\n    public static void main(String[] args) {\n        int x = \"no\";\n    }\n}\n");

        onFx(() -> dap.startLaunch(file, "java", noPicker, javaExec(), null));

        awaitTrue(() -> !errors.isEmpty(), "the launch failed");
        String report = String.join("", console);
        assertTrue(report.contains("stderr:"), "as error output: " + console);
        assertTrue(report.contains(file + ":3: error"), "javac's file:line is in the console: " + report);
        assertTrue(
                com.editora.run.StackTraceLinks.parse(file + ":3: error: incompatible types") != null, "and is a link");
        assertTrue(errors.get(0).contains("Broken.java"), errors.toString());
        assertFalse(errors.get(0).contains("incompatible"), "the status line says where, not the whole report");
    }

    // --- D6: overlapping launches ---------------------------------------------------------------------

    /**
     * A launch that already compiled its classes, but has no adapter yet, is replaced by another: its folder
     * used to be forgotten and left behind in the temp folder.
     */
    @Test
    void aSecondLaunchReleasesTheFirstLaunchsCompiledClasses() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("two"));
        Path first = folder.resolve("FirstOne.java");
        Path second = folder.resolve("SecondOne.java");
        Files.writeString(second, "public class SecondOne {\n    public static void main(String[] a) {}\n}\n");
        open(first, folder, "public class FirstOne {\n    public static void main(String[] a) {}\n}\n");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        lsp.openDocument(second, folder, "java", Files.readString(second));

        // The first launch compiles (no main class from jdtls: the compile path). The FX thread is then held
        // until it has kept its folder, so the second launch starts before the first one reaches its adapter.
        onFx(() -> dap.startLaunch(first, "java", noPicker, javaExec(), null));
        awaitTrue(() -> names().contains("vscode.java.resolveMainClass"), "the first launch asked jdtls");
        onFx(() -> {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (FxlessAccess.compilationDirectory(dap) == null && System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            Path kept = FxlessAccess.compilationDirectory(dap);
            assertTrue(kept != null && Files.isRegularFile(kept.resolve("FirstOne.class")), "first compiled");
            firstDir[0] = kept;
            dap.startLaunch(second, "java", noPicker, javaExec(), null);
        });

        assertEquals("SecondOne", launch().get("mainClass"));
        awaitTrue(() -> !Files.exists(firstDir[0]), "the first launch's classes are removed: " + firstDir[0]);
        assertTrue(firstDir[0].startsWith(tmp) || Files.notExists(firstDir[0]));
    }

    private final Path[] firstDir = new Path[1];
}
