package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Go to Markdown Heading in Project lists every heading of the project's Markdown files. */
@Tag("fx")
class MarkdownHeadingPickerFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void scanReadsEveryMarkdownFileHonoursIgnoreRulesAndPrefersOpenText(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Files.writeString(root.resolve(".gitignore"), "build/\n");
        Path a = Files.writeString(root.resolve("a.md"), "# Alpha\n\n## Beta *b*\n\n```\n# code\n```\n");
        Files.writeString(Files.createDirectories(root.resolve("docs")).resolve("g.markdown"), "Guide\n=====\n");
        Files.writeString(Files.createDirectories(root.resolve("build")).resolve("out.md"), "# Built\n");
        Files.writeString(root.resolve("notes.txt"), "# Not Markdown\n");

        boolean[] truncated = {false};
        List<MarkdownHeadingPicker.Hit> hits = MarkdownHeadingPicker.scan(root, true, Map.of(), () -> false, truncated);
        assertEquals(
                List.of("a.md:  Beta b", "a.md:Alpha", "docs/g.markdown:Guide"),
                hits.stream()
                        .map(h -> h.rel() + ":" + MarkdownHeadingPicker.label(h))
                        .sorted()
                        .toList());
        assertEquals(
                2,
                hits.stream()
                        .filter(h -> h.rel().equals("a.md"))
                        .toList()
                        .get(1)
                        .heading()
                        .line());

        // Without the ignore rules the built file is there; an open buffer's unsaved text wins over the disk.
        List<MarkdownHeadingPicker.Hit> all =
                MarkdownHeadingPicker.scan(root, false, Map.of(a, "# Unsaved\n"), () -> false, truncated);
        List<String> titles =
                all.stream().map(h -> h.heading().title()).sorted().toList();
        assertEquals(List.of("Built", "Guide", "Unsaved"), titles);
        assertEquals(List.of(), MarkdownHeadingPicker.scan(root, true, Map.of(), () -> true, truncated), "cancelled");
    }

    @Test
    void theCommandScansOffThreadAndChoosingAHitOpensItsLine(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        Path a = Files.writeString(root.resolve("a.md"), "intro\n\n# Alpha\n");
        List<String> opened = new ArrayList<>();
        List<String> status = new ArrayList<>();
        Path[] projectRoot = {null};
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public void setStatus(String message) {
                status.add(message);
            }
        };
        IndexCoordinator.Ops ops = new IndexCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return projectRoot[0];
            }

            @Override
            public void openAndGoto(Path file, int line, int column) {
                opened.add(file.getFileName() + ":" + line + ":" + column);
            }

            @Override
            public boolean respectGitignore() {
                return true;
            }
        };
        MarkdownHeadingPicker picker = FxTestSupport.callOnFx(() -> new MarkdownHeadingPicker(host, ops));

        FxTestSupport.runOnFx(picker::show);
        assertEquals(1, status.size(), "no project: a message, no scan");

        projectRoot[0] = root;
        FxTestSupport.runOnFx(picker::show);
        List<MarkdownHeadingPicker.Hit> hits =
                FxTestSupport.callOnFx(() -> picker.lastRun).get(20, TimeUnit.SECONDS);
        assertEquals(1, hits.size());
        assertEquals(a, hits.get(0).file());
        assertEquals(2, hits.get(0).heading().line());

        Files.delete(a);
        FxTestSupport.runOnFx(picker::show);
        assertTrue(FxTestSupport.callOnFx(() -> picker.lastRun)
                .get(20, TimeUnit.SECONDS)
                .isEmpty());
        FxTestSupport.runOnFx(() -> {});
        assertEquals(2, status.size(), "nothing to list: said so");
        assertEquals(List.of(), opened);
    }
}
