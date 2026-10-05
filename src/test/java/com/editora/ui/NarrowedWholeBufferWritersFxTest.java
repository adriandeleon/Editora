package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.externaltool.ExternalTool;
import com.editora.mcp.McpBridge;
import com.editora.plugin.ActiveEditor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The features that read the whole file ({@link EditorBuffer#getContent()}) and write a whole-file result
 * back, each run against a <b>narrowed</b> buffer. Writing that result into the narrowed area leaves the
 * held-aside prefix and suffix around a full copy of the file — the duplicated document is then what Save
 * writes — so every one of them must replace the whole document (which widens first).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NarrowedWholeBufferWritersFxTest {

    private static final String DOC = "one\ntwo\nthree\nfour\nfive\n";

    @TempDir
    Path work;

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void fixingMarkdownLintReplacesTheWholeFile() throws Exception {
        String doc = "# Title\n\nIntro with trailing spaces   \n\n## Section A\n\nText A.\n\n## Section B\n\nText B.\n";
        EditorBuffer b = openNarrowed("doc.md", doc, 4, 7);
        assertTrue(FxTestSupport.callOnFx(() -> registry.run("markdownLint.fix")));
        assertWholeFileIs(doc.replace("spaces   \n", "spaces\n"), b);
    }

    @Test
    void aligningCsvReplacesTheWholeFile() throws Exception {
        String doc = "id,name,city\n1,Al,Rome\n22,Barbara,Oslo\n333,Cy,Lima\n";
        EditorBuffer b = openNarrowed("data.csv", doc, 1, 2);
        assertTrue(FxTestSupport.callOnFx(() -> registry.run("csv.align")));
        assertWholeFileIs(com.editora.csv.CsvAlign.align(doc, ','), b);
    }

    @Test
    void anMcpWholeBufferRewriteReplacesTheWholeFile() throws Exception {
        EditorBuffer b = openNarrowed("mcp.txt", DOC, 1, 2);
        String path = b.getPath().toString();
        McpBridge.BufferContent read = fx.controller.readBuffer(path);
        assertEquals(DOC, read.text(), "read_buffer is the whole file");
        assertNull(fx.controller.editBuffer(path, "", read.text().replace("three", "THREE"), false));
        assertWholeFileIs(DOC.replace("three", "THREE"), b);
    }

    @Test
    void anAgentFileWriteReplacesTheWholeFile() throws Exception {
        EditorBuffer b = openNarrowed("agent.txt", DOC, 1, 2);
        AgentCoordinator agent = FxTestSupport.field(fx.controller, "agentCoordinator");
        String path = b.getPath().toString();
        String read = agent.readTextFile(path, null, null);
        assertEquals(DOC, read, "fs/read_text_file is the whole file");
        agent.writeTextFile(path, read.replace("three", "THREE"));
        FxTestSupport.drainFx();
        assertWholeFileIs(DOC.replace("three", "THREE"), b);
    }

    @Test
    void aPluginSetTextReplacesTheWholeFile() throws Exception {
        EditorBuffer b = openNarrowed("plugin.txt", DOC, 1, 2);
        Object coordinator = FxTestSupport.field(fx.controller, "pluginCoordinator");
        var ctor = Class.forName("com.editora.ui.PluginCoordinator$ActiveEditorImpl")
                .getDeclaredConstructor(PluginCoordinator.class, EditorBuffer.class);
        ctor.setAccessible(true);
        ActiveEditor editor = (ActiveEditor) ctor.newInstance(coordinator, null); // the live active buffer
        FxTestSupport.runOnFx(() -> {
            String text = editor.text();
            assertEquals(DOC, text, "ActiveEditor.text() is the whole file");
            editor.setText(text.replace("three", "THREE"));
        });
        assertWholeFileIs(DOC.replace("three", "THREE"), b);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the tool is `tr`
    void anExternalToolReplacingTheBufferReplacesTheWholeFile() throws Exception {
        EditorBuffer b = openNarrowed("tool.txt", DOC, 1, 2);
        ExternalTool tool = new ExternalTool(
                "Upcase",
                "tr",
                "a-z A-Z",
                "",
                ExternalTool.StdinSource.BUFFER,
                ExternalTool.OutputTarget.REPLACE_BUFFER,
                true);
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setExternalTools(new ArrayList<>(List.of(tool)));
            FxTestSupport.call(fx.controller, "refreshExternalToolCommands", new Class<?>[] {});
        });
        long before = FxTestSupport.callOnFx(b::docVersion);
        assertTrue(FxTestSupport.callOnFx(() -> registry.run(ExternalTool.commandIdFor("Upcase"))));
        long deadline = System.currentTimeMillis() + 10_000;
        while (FxTestSupport.callOnFx(b::docVersion) == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        FxTestSupport.drainFx();
        assertWholeFileIs(DOC.toUpperCase(java.util.Locale.ROOT), b);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void assertWholeFileIs(String expected, EditorBuffer b) throws Exception {
        assertEquals(expected, FxTestSupport.callOnFx(b::getContent), "what Save writes holds the file once");
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed), "the whole-document write widened the buffer");
        assertEquals(expected, FxTestSupport.callOnFx(() -> b.getArea().getText()));
        FxTestSupport.runOnFx(b::markClean); // nothing to prompt about when the window is disposed
    }

    /** Opens a real file and narrows it to whole lines {@code first..last} (0-based, inclusive). */
    private EditorBuffer openNarrowed(String name, String content, int first, int last) throws Exception {
        Path file = work.resolve(name);
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        EditorBuffer[] opened = new EditorBuffer[1];
        assertTrue(
                waitFor(() -> {
                    EditorBuffer b =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    if (b == null || b.getPath() == null || b.isLoading() || !content.equals(b.getContent())) {
                        return false;
                    }
                    if (!b.getPath()
                            .toAbsolutePath()
                            .normalize()
                            .equals(file.toAbsolutePath().normalize())) {
                        return false;
                    }
                    opened[0] = b;
                    return true;
                }),
                "the file opened");
        FxTestSupport.drainFx();
        EditorBuffer b = opened[0];
        assertTrue(FxTestSupport.callOnFx(() -> {
            b.getArea().selectRange(lineStart(content, first), lineStart(content, last + 1));
            return registry.run("edit.narrowToRegion");
        }));
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed), "precondition: the buffer is narrowed");
        assertEquals(content, FxTestSupport.callOnFx(b::getContent));
        return b;
    }

    private static int lineStart(String text, int line) {
        int off = 0;
        for (int i = 0; i < line; i++) {
            off = text.indexOf('\n', off) + 1;
        }
        return off;
    }

    private static boolean waitFor(Callable<Boolean> onFx) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(FxTestSupport.callOnFx(onFx))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
