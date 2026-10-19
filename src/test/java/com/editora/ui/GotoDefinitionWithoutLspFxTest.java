package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * B11: Go to Definition with LSP off (the default) used to say "LSP support is disabled" and not move, although
 * the server-free symbol index knew where the function was.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GotoDefinitionWithoutLspFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theCommandJumpsToADeclarationInTheSameScript(@TempDir Path dir) throws Exception {
        String sh =
                "#!/usr/bin/env bash\nusage() {\n    echo usage\n}\n\nmy-lib::main() {\n    usage\n}\n\nmy-lib::main \"$@\"\n";
        Path file = Files.writeString(dir.resolve("deploy.sh"), sh);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
            async.awaitFx();
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);

            FxTestSupport.runOnFx(() -> {
                b.getArea().moveTo(sh.lastIndexOf("my-lib::main") + 4); // on `lib`, inside the name
                registry.run("lsp.gotoDefinition");
            });
            async.awaitFx();
            assertEquals(
                    5, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "my-lib::main() is line 6");

            FxTestSupport.runOnFx(() -> {
                b.getArea().moveTo(sh.indexOf("    usage\n}\n\nmy") + 5);
                registry.run("lsp.gotoDefinition");
            });
            async.awaitFx();
            assertEquals(1, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "usage() is line 2");
        }
    }

    @Test
    void anotherFilesDeclarationComesFromTheProjectIndex(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("lib.sh"), "#!/bin/sh\n\nhelper() {\n    :\n}\n");
        String main = "#!/bin/sh\n. ./lib.sh\nhelper\n";
        Path mainFile = Files.writeString(project.resolve("main.sh"), main);
        List<Path> opened = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("shell");
            b.setContent(main);
            b.setPath(mainFile);
            b.getNode();
            b.getArea().moveTo(main.lastIndexOf("helper") + 2);
            return b;
        });
        com.editora.config.Settings settings = new com.editora.config.Settings();
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public com.editora.config.Settings settings() {
                return settings;
            }

            @Override
            public EditorBuffer activeBuffer() {
                return buffer;
            }
        };
        IndexCoordinator index = FxTestSupport.callOnFx(() -> new IndexCoordinator(host, new IndexCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return project;
            }

            @Override
            public void openAndGoto(Path file, int line, int column) {
                opened.add(file);
                lines.add(line);
            }

            @Override
            public boolean respectGitignore() {
                return false;
            }
        }));
        FxTestSupport.runOnFx(() -> index.gotoDefinitionAtCaret());
        for (int i = 0; i < 200 && opened.isEmpty(); i++) {
            Thread.sleep(25); // the project walk runs on the index worker
            FxTestSupport.runOnFx(() -> {});
        }
        assertEquals(List.of(project.resolve("lib.sh")), opened);
        assertEquals(List.of(2), lines);
        assertEquals(
                "flag", IndexCoordinator.nameAt("  --flag ", 3, "shell"), "the dashes of an option are not the name");
        assertNull(IndexCoordinator.nameAt("a = b", 2, "shell"), "the caret on no name");
        assertEquals("pkg::fn", IndexCoordinator.nameAt("x pkg::fn y", 4, "shell"));
        assertEquals("fn", IndexCoordinator.nameAt("x pkg::fn y", 8, "python"));
        assertEquals("$fetch", IndexCoordinator.nameAt("await $fetch(url)", 8, "javascript"));
        assertEquals("#listen", IndexCoordinator.nameAt("return this.#listen();", 15, "javascript"));
        assertEquals("fetch", IndexCoordinator.nameAt("await $fetch(url)", 8, "python"));
    }

    @Test
    void theNearestDeclarationAboveTheUseWins() {
        String js = "function a() {\n  const parse = (s) => s;\n}\nfunction b() {\n  const parse = (s) => s;\n"
                + "  return parse(x);\n}\n";
        var symbols = com.editora.index.DeclarationScanner.scan(js, "javascript");
        assertEquals(4, IndexCoordinator.nearestDeclaration(symbols, "parse", 5).line(), "b's own helper");
        assertEquals(1, IndexCoordinator.nearestDeclaration(symbols, "parse", 0).line(), "else the first below");
        assertNull(IndexCoordinator.nearestDeclaration(symbols, "nothing", 5));
    }
}
