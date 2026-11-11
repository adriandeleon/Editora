package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The order and the words of the Loaded Sources and Modules lists. */
class LoadedSourcesPickerTest {

    private static final Path BASE =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

    private static DapModels.LoadedSource file(Path p) {
        return new DapModels.LoadedSource(p.getFileName().toString(), p.toString(), p, 0, null);
    }

    @Test
    void theProjectsFilesComeFirstThenOtherFilesThenReferences() {
        Path root = BASE.resolve("app");
        DapModels.LoadedSource mine = file(root.resolve("src").resolve("b.js"));
        DapModels.LoadedSource also = file(root.resolve("a.js"));
        DapModels.LoadedSource lib = file(BASE.resolve("lib").resolve("aaa.js"));
        DapModels.LoadedSource internal = new DapModels.LoadedSource(
                "<node_internals>/fs", "<node_internals>/fs", null, 4, "Skipped by skipFiles");
        assertEquals(
                List.of(also, mine, lib, internal),
                LoadedSourcesPicker.sorted(List.of(internal, lib, mine, also), root));
        assertEquals(tr("debug.loadedSources.projectRoot"), LoadedSourcesPicker.sourceDetail(also, root, null));
        assertEquals("src", LoadedSourcesPicker.sourceDetail(mine, root, null));
        assertEquals(
                tr("debug.loadedSources.readOnly") + "  ·  Skipped by skipFiles",
                LoadedSourcesPicker.sourceDetail(internal, root, null));
    }

    @Test
    void aSourceIsHighlightedByItsName() {
        assertEquals(
                "javascript",
                LoadedSourcesPicker.languageOf(new DapModels.LoadedSource("fs", "<node_internals>/fs", null, 1, null)));
        assertEquals(
                "python",
                LoadedSourcesPicker.languageOf(new DapModels.LoadedSource("x.py", "/lib/x.py", null, 1, null)));
    }

    @Test
    void theUsersModulesComeFirstAndSayWhatTheAdapterSaid() {
        DapModels.ModuleInfo libc = new DapModels.ModuleInfo(
                "1", "libc.so.6", "/usr/lib/libc.so.6", null, "Public symbols only", null, null, null);
        DapModels.ModuleInfo app =
                new DapModels.ModuleInfo("2", "zapp", "/w/zapp", "1.2", "Symbols loaded.", true, true, "0x1-0x2");
        assertEquals(List.of(app, libc), LoadedSourcesPicker.sortedModules(List.of(libc, app)));
        assertEquals(
                String.join(
                        "  ·  ",
                        "Symbols loaded.",
                        tr("debug.modules.version", "1.2"),
                        tr("debug.modules.userCode"),
                        tr("debug.modules.optimized"),
                        "0x1-0x2",
                        "/w/zapp"),
                LoadedSourcesPicker.moduleDetail(app));
    }
}
