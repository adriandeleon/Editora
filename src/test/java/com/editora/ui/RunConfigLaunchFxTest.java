package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.config.RunConfiguration;
import com.editora.editor.EditorBuffer;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.StackTraceLinks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Launching a <em>saved</em> Java run configuration must resolve its classpath through the entry jdtls itself
 * enumerates, not a hand-built one.
 *
 * <p>This is the regression that made a saved configuration fail where the identical gutter ▶ worked:
 * {@code resolveClasspath} takes {@code (mainClass, projectName)}, a saved configuration usually carries a
 * <b>blank</b> projectName, and jdtls answers an unmatched project with an <b>empty classpath and no
 * error</b> — reported to the user as "make sure the Java project has finished importing" for a project that
 * had imported perfectly.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunConfigLaunchFxTest {

    private FxWindowFixture fx;
    private Path root;
    private Path appFile;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        root = Files.createTempDirectory("editora-runcfg");
        appFile = root.resolve("src/main/java/com/example/demo/App.java");
        Files.createDirectories(appFile.getParent());
        Files.writeString(
                appFile,
                "package com.example.demo;\n\npublic class App\n{\n"
                        + "    public static void main( String[] args )\n    {\n    }\n}\n");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** Records the {@link JavaMainClass} the launch resolves with; never actually runs anything. */
    private final class RecordingOps implements RunCoordinator.Ops {
        final AtomicReference<JavaMainClass> resolvedWith = new AtomicReference<>();
        /** What jdtls would answer for this project — note the real project name. */
        List<JavaMainClass> enumerated =
                List.of(new JavaMainClass("com.example.demo.App", "demo", "/tmp/does-not-matter/App.java"));

        @Override
        public void openToolWindow() {}

        @Override
        public void onRunStateChanged() {}

        @Override
        public void editConfiguration(String name) {}

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return true;
        }

        @Override
        public String programArgs(Path path) {
            return "";
        }

        @Override
        public void setProgramArgs(Path path, String args) {}

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public Path javaProjectRoot(Path file) {
            return root;
        }

        @Override
        public boolean javaLaunchAvailable() {
            return true;
        }

        List<RunConfiguration> configs = List.of();
        String selected = "";
        boolean maven = true;

        @Override
        public List<RunConfiguration> runConfigurations() {
            return configs;
        }

        @Override
        public String selectedRunConfigName() {
            return selected;
        }

        @Override
        public void resolveJavaMainClasses(Path routingFile, Consumer<List<JavaMainClass>> cb) {
            cb.accept(enumerated);
        }

        @Override
        public void resolveJavaLaunch(Path routingFile, JavaMainClass mainClass, Consumer<JavaLaunchInfo> cb) {
            resolvedWith.set(mainClass);
            cb.accept(null); // stop before actually launching
        }

        @Override
        public boolean mavenProjectAt(Path r) {
            return maven;
        }

        @Override
        public boolean gradleProjectAt(Path r) {
            return false;
        }

        @Override
        public void resolveMavenClasspath(Path r, Consumer<List<String>> cb) {
            cb.accept(List.of());
        }

        @Override
        public void runGradleRunTask(Path r) {}
    }

    /** Runs {@code cfg} through a coordinator wired to {@code ops}, with App.java open so routing resolves. */
    private void launch(RecordingOps ops, RunConfiguration cfg) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, appFile));
        RunCoordinator coordinator = new RunCoordinator(FxTestSupport.field(fx.controller, "coordinatorHost"), ops);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(coordinator, "runJavaConfig", new Class[] {RunConfiguration.class}, cfg));
    }

    @Test
    void aBlankProjectNameIsFilledInFromJdtlsOwnEnumeration() throws Exception {
        RecordingOps ops = new RecordingOps();
        launch(ops, new RunConfiguration("demo", "run", "com.example.demo.App", "", "", "", root.toString()));

        JavaMainClass used = ops.resolvedWith.get();
        assertNotNull(used, "the launch reached the classpath resolution");
        assertEquals("com.example.demo.App", used.fqn());
        assertEquals("demo", used.projectName(), "jdtls's project name, not the configuration's blank one");
    }

    @Test
    void fallsBackToTheConfigurationWhenJdtlsEnumeratesNothing() throws Exception {
        // A project jdtls cannot enumerate must still try, rather than silently do nothing.
        RecordingOps ops = new RecordingOps();
        ops.enumerated = List.of();
        launch(ops, new RunConfiguration("demo", "run", "com.example.demo.App", "myproj", "", "", root.toString()));

        JavaMainClass used = ops.resolvedWith.get();
        assertNotNull(used);
        assertEquals("myproj", used.projectName(), "the configuration's own value is the fallback");
    }

    private static RunConfiguration cfg(String name, String mainClass) {
        return new RunConfiguration(name, "run", "java", "", mainClass, name, "", "", "/tmp", "", "");
    }

    /**
     * The new decision function directly. The launch plumbing it delegates to ({@code runConfig} →
     * {@code runJavaConfig}) is already covered by the two tests above, so this pins only what was added:
     * WHICH configuration a gutter ▶ picks.
     */
    private RunConfiguration choose(RecordingOps ops, String fqn) throws Exception {
        RunCoordinator c = new RunCoordinator(FxTestSupport.field(fx.controller, "coordinatorHost"), ops);
        java.lang.reflect.Method m =
                RunCoordinator.class.getDeclaredMethod("configurationForGutterRun", String.class, Path.class);
        m.setAccessible(true);
        return (RunConfiguration) m.invoke(c, fqn, root);
    }

    @Test
    void theGutterPlayButtonPrefersTheConfigurationForThatClass() throws Exception {
        // The ad-hoc path carries no before-launch build, args or env — which is why a generated project's
        // gutter ▶ launched against an empty target/classes.
        RecordingOps ops = new RecordingOps();
        ops.configs = List.of(cfg("other", "com.example.Other"), cfg("demo", "com.example.demo.App"));
        ops.selected = "other";
        assertEquals(
                "demo",
                choose(ops, "com.example.demo.App").name(),
                "the configuration for the clicked class wins over the toolbar selection");
    }

    @Test
    void withNoMatchingConfigurationItFallsBackToTheSelectedOne() throws Exception {
        RecordingOps ops = new RecordingOps();
        ops.configs = List.of(cfg("demo", "com.example.demo.App"));
        ops.selected = "demo";
        assertEquals("demo", choose(ops, "com.example.Unconfigured").name());
    }

    @Test
    void outsideAMavenOrGradleProjectTheAdHocPathIsKept() throws Exception {
        // No build means nothing for a before-launch step to run; the plain main-class run is the whole story.
        RecordingOps ops = new RecordingOps();
        ops.configs = List.of(cfg("demo", "com.example.demo.App"));
        ops.selected = "demo";
        ops.maven = false;
        assertNull(choose(ops, "com.example.demo.App"));
    }

    @Test
    void withNoConfigurationsAtAllTheAdHocPathIsKept() throws Exception {
        assertNull(choose(new RecordingOps(), "com.example.demo.App"));
    }
}
