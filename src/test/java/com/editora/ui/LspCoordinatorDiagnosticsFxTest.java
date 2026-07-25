package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LspCoordinator}'s diagnostics routing: which diagnostics are kept, how they are keyed, and when they
 * are cleared. Every rule here exists because of a specific failure:
 *
 * <ul>
 *   <li><b>open files only</b> — a server publishes project-wide (jdtls especially), so without scoping the
 *       Problems window fills with whole-workspace noise from a single open file;</li>
 *   <li><b>canonical keying (#470)</b> — a server reports under whatever URI it chose, and for a file reached
 *       through a symlink that is the resolved path, so plain {@code normalize()} silently dropped every
 *       diagnostic (no squiggles, empty Problems);</li>
 *   <li><b>compact-source noise</b> — a jdtls whose compliance predates JDK 25 flags a compact source file's
 *       implicit class, which is pure noise for a file the launcher runs fine;</li>
 *   <li><b>#469</b> — disabling a server must clear its diagnostics <em>before</em> shutdown, because
 *       afterwards nothing will ever publish an empty list to retract them.</li>
 * </ul>
 */
@Tag("fx")
class LspCoordinatorDiagnosticsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private FakeOps ops;

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

    private static final class FakeOps extends LspOpsStub {
        final Map<Path, EditorBuffer> open = new HashMap<>();
        boolean featureEnabled = true;

        @Override
        public boolean lspFeatureEnabled() {
            return featureEnabled;
        }

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return open.get(canonicalize(file));
        }

        /** Mirrors {@code MainController.canonicalPath}: symlink-resolved, falling back to normalize. */
        @Override
        public Path canonicalize(Path file) {
            if (file == null) {
                return null;
            }
            try {
                return file.toRealPath();
            } catch (Exception e) {
                return file.toAbsolutePath().normalize();
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls"));
        host = new FakeHost();
        ops = new FakeOps();
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    private EditorBuffer openJava(String name, String text) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(f);
            x.setContent(text);
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        ops.open.put(ops.canonicalize(f), b);
        return b;
    }

    private static List<LspDiagnostic> one(String message) {
        return List.of(new LspDiagnostic(0, 0, 0, 5, LspDiagnostic.Severity.ERROR, message, null, "jdtls"));
    }

    private void publish(Path file, List<LspDiagnostic> diags) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.onDiagnostics(file, diags));
    }

    private Map<Path, List<LspDiagnostic>> problems() throws Exception {
        return FxTestSupport.callOnFx(() -> coordinator.problems());
    }

    // --- open-files-only scoping ---------------------------------------------------------------------

    @Test
    void diagnosticsForAnOpenFileAreKept() throws Exception {
        EditorBuffer b = openJava("A.java", "class A {}\n");
        publish(b.getPath(), one("boom"));

        assertEquals(1, problems().size());
        assertTrue(problems().containsKey(ops.canonicalize(b.getPath())));
    }

    /** A server publishes project-wide; a file with no open tab must not reach the Problems window. */
    @Test
    void diagnosticsForAFileWithNoOpenTabAreDropped() throws Exception {
        openJava("A.java", "class A {}\n");
        Path unopened = root.resolve("Elsewhere.java");
        Files.writeString(unopened, "class Elsewhere {}");

        publish(unopened, one("project-wide noise"));

        assertTrue(problems().isEmpty(), "whole-workspace publishes must not fill the Problems window");
    }

    /** An empty list is a retraction — the entry must go, not linger. */
    @Test
    void anEmptyPublishRetractsTheFilesDiagnostics() throws Exception {
        EditorBuffer b = openJava("A.java", "class A {}\n");
        publish(b.getPath(), one("boom"));
        assertFalse(problems().isEmpty());

        publish(b.getPath(), List.of());

        assertTrue(problems().isEmpty(), "an empty publish retracts");
    }

    /** With the feature off, nothing is recorded at all. */
    @Test
    void nothingIsRecordedWhileTheFeatureIsDisabled() throws Exception {
        EditorBuffer b = openJava("A.java", "class A {}\n");
        ops.featureEnabled = false;

        publish(b.getPath(), one("boom"));

        assertTrue(problems().isEmpty());
    }

    // --- #470: canonical keying ----------------------------------------------------------------------

    /**
     * A server reports under the file's <em>real</em> path. Keying the map by that canonical form is what
     * lets the active-file sort and the tab-close clear match — plain {@code normalize()} does not resolve
     * symlinks, so every diagnostic for a symlink-reached file was silently dropped.
     */
    @Test
    void aSymlinkedPathAndItsRealPathShareOneEntry() throws Exception {
        Path realDir = root.resolve("real");
        Files.createDirectories(realDir);
        Path realFile = realDir.resolve("A.java");
        Files.writeString(realFile, "class A {}\n");

        Path linkDir = root.resolve("link");
        try {
            Files.createSymbolicLink(linkDir, realDir);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlink support here (e.g. Windows without privilege) — nothing to assert
        }
        Path viaLink = linkDir.resolve("A.java");

        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(viaLink);
            x.setContent("class A {}\n");
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        ops.open.put(ops.canonicalize(viaLink), b);

        // The server reports the resolved path; the buffer was opened through the link.
        publish(realFile, one("boom"));

        assertEquals(1, problems().size(), "the resolved-path publish must find the symlink-opened buffer");
        assertTrue(
                problems().containsKey(realFile.toRealPath()),
                "the entry must be keyed canonically so clear/sort can match it");
    }

    // --- compact-source noise ------------------------------------------------------------------------

    /**
     * A compact source file (JEP 512) run by the JDK 25 launcher is flagged by an older jdtls as an
     * "implicitly declared class" preview feature. That is pure noise; real errors must still surface.
     */
    @Test
    void compactSourceImplicitClassNoiseIsFilteredButRealErrorsSurvive() throws Exception {
        // A compact source file: a top-level `void main(` with no enclosing class.
        EditorBuffer b = openJava("Script.java", "void main() {\n    IO.println(\"hi\");\n}\n");
        assertTrue(FxTestSupport.callOnFx(b::isRunnable), "precondition: recognised as a compact source file");

        List<LspDiagnostic> mixed = new ArrayList<>();
        mixed.add(new LspDiagnostic(
                0,
                0,
                0,
                1,
                LspDiagnostic.Severity.ERROR,
                "Implicitly declared class is a preview feature",
                null,
                "jdtls"));
        mixed.add(
                new LspDiagnostic(1, 4, 1, 6, LspDiagnostic.Severity.ERROR, "cannot find symbol: IOO", null, "jdtls"));
        publish(b.getPath(), mixed);

        List<LspDiagnostic> kept = problems().get(ops.canonicalize(b.getPath()));
        assertEquals(1, kept.size(), "the implicit-class complaint should have been dropped");
        assertEquals("cannot find symbol: IOO", kept.get(0).message(), "a real error must survive");
    }

    /** The filter must not touch an ordinary (non-compact) java file. */
    @Test
    void theNoiseFilterDoesNotApplyToAnOrdinaryJavaFile() throws Exception {
        EditorBuffer b = openJava("Normal.java", "class Normal {\n    void x() {}\n}\n");
        assertFalse(FxTestSupport.callOnFx(b::isRunnable), "precondition: not a compact source file");

        publish(b.getPath(), one("Implicitly declared class is a preview feature"));

        assertEquals(1, problems().size(), "only compact source files get the noise filter");
    }

    // --- clearing ------------------------------------------------------------------------------------

    @Test
    void clearDiagnosticsRemovesJustThatFile() throws Exception {
        EditorBuffer a = openJava("A.java", "class A {}\n");
        EditorBuffer c = openJava("C.java", "class C {}\n");
        publish(a.getPath(), one("a"));
        publish(c.getPath(), one("c"));
        assertEquals(2, problems().size());

        FxTestSupport.runOnFx(() -> coordinator.clearDiagnostics(a.getPath()));

        assertEquals(1, problems().size());
        assertTrue(problems().containsKey(ops.canonicalize(c.getPath())));
    }

    @Test
    void clearAllDiagnosticsEmptiesTheMap() throws Exception {
        EditorBuffer a = openJava("A.java", "class A {}\n");
        publish(a.getPath(), one("a"));

        FxTestSupport.runOnFx(() -> coordinator.clearAllDiagnostics());

        assertTrue(problems().isEmpty());
    }

    /**
     * #469: turning a server off must clear its diagnostics. After the shutdown {@code isManaged} is false,
     * so {@code syncBuffer}'s clear is skipped — and with no server left to publish an empty list, the
     * Problems window would strand that server's diagnostics forever.
     */
    @Test
    void disablingAServerClearsItsDiagnostics() throws Exception {
        EditorBuffer b = openJava("A.java", "class A {}\n");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(b));
        publish(b.getPath(), one("boom"));
        assertFalse(problems().isEmpty());

        host.settings.setJavaLspEnabled(false);
        FxTestSupport.runOnFx(() -> coordinator.applySupport());

        assertTrue(problems().isEmpty(), "a disabled server's diagnostics must not be stranded (#469)");
        assertFalse(FxTestSupport.callOnFx(b::isLspActive), "and its squiggles must go too");
    }
}
