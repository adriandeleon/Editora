package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspDiagnostic;
import com.editora.editor.LspDiagnostic.Severity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for #470: the Problems diagnostics map must be keyed by the <em>canonical</em> path. A
 * language server reports diagnostics under whatever URI it chose (often the symlink path Editora sent), while
 * {@code setProblemsActiveFile} is given the canonical active path — so if the key isn't canonicalized, the
 * two never match and the active file's group never sorts to the top of Problems (and a tab-close clear can
 * miss). Here a file is opened through a real symlink; its diagnostics must land under the resolved real path.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspDiagnosticsCanonicalKeyFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        fx.shared.getSettings().setLspSupport(true); // lspFeatureEnabled() gates onDiagnostics
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void diagnosticsForASymlinkedFileAreKeyedByTheCanonicalPath() throws Exception {
        // A real symlink: link -> real. The file opened as link/x.txt resolves to real/x.txt.
        Path base = Files.createTempDirectory("editora-lsp-symlink");
        Path real = Files.createDirectory(base.resolve("real"));
        Path file = real.resolve("x.txt");
        Files.writeString(file, "hello\nworld\n");
        Path link = Files.createSymbolicLink(base.resolve("link"), real);
        Path opened = link.resolve("x.txt"); // the symlink path the editor/server sees
        Path canonical = opened.toRealPath(); // = real/x.txt

        // Sanity: the two forms really differ (else the test proves nothing).
        assertTrue(!opened.equals(canonical), "symlink path must differ from its real path");

        Object lsp = FxTestSupport.field(fx.controller, "lspCoordinator");

        // Open the file (so bufferForPath finds a buffer), then feed a diagnostic keyed by the symlink path —
        // exactly what a server that echoes the sent URI does.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class<?>[] {Path.class}, opened));
        List<LspDiagnostic> diags = List.of(new LspDiagnostic(1, 0, 1, 5, Severity.ERROR, "boom", "E1", "test"));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "onLspDiagnostics", new Class<?>[] {Path.class, List.class}, opened, diags));

        @SuppressWarnings("unchecked")
        Map<Path, List<LspDiagnostic>> problems =
                (Map<Path, List<LspDiagnostic>>) FxTestSupport.call(lsp, "problems", new Class<?>[] {});
        assertEquals(1, problems.size(), "one file has diagnostics");
        Path key = problems.keySet().iterator().next();
        assertEquals(canonical, key, "the diagnostics key must be the canonical (symlink-resolved) path");
    }
}
