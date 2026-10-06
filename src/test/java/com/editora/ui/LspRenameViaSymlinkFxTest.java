package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import com.editora.lsp.WorkspaceEditMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A workspace edit that moves a file must take the file's open tab with it even when the server spells the
 * path differently from the tab — jdtls answers with the symlink-resolved path for a project opened through
 * a symlink (or under {@code /tmp} on macOS). Driven through a real window, because the remap is the real
 * {@code MainController}'s: the old path no longer exists when it runs, so it cannot be canonicalised again.
 */
@Tag("fx")
class LspRenameViaSymlinkFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path work;

    @Test
    void theTabFollowsAFileTheServerMovedUnderItsRealPath() throws Exception {
        Path real = Files.createDirectories(work.resolve("real-project")).toRealPath();
        Path link = work.resolve("project-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }
        String text = "public class Foo {}\n";
        Files.writeString(real.resolve("Foo.java"), text);
        Path openedAs = link.resolve("Foo.java");
        Path serverFrom = real.resolve("Foo.java");
        Path serverTo = real.resolve("Baz.java");

        Path configDir = Files.createDirectories(work.resolve("config"));
        try (FxWindowFixture fx = FxWindowFixture.create(configDir, false, false, false, List.of(), true, c -> {})) {
            MainController controller = fx.controller;
            LspCoordinator coordinator = FxTestSupport.field(controller, "lspCoordinator");
            FxTestSupport.runOnFx(() -> controller.openAndNavigate(openedAs, 0));
            FxTestSupport.drainFx();
            EditorBuffer buffer = (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
            assertEquals(
                    openedAs, FxTestSupport.callOnFx(buffer::getPath), "the tab keeps the spelling it was opened with");

            // jdtls's answer to a class rename, in the server's (resolved) spelling.
            var mapped = new WorkspaceEditMapper.Mapped(
                    List.of(new WorkspaceEditMapper.FileEdit(
                            serverFrom, List.of(new LspTextEdit(0, 13, 0, 16, "Baz")), null, text)),
                    List.of(new WorkspaceEditMapper.FileRename(serverFrom, serverTo, false)));
            CompletableFuture<Boolean> applied = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsAsync(mapped, applied::complete));

            assertTrue(applied.get(10, TimeUnit.SECONDS));
            FxTestSupport.drainFx();
            assertTrue(Files.exists(serverTo));
            assertFalse(Files.exists(serverFrom));
            assertEquals(
                    link.resolve("Baz.java"),
                    FxTestSupport.callOnFx(buffer::getPath),
                    "the tab must follow the file, in the spelling it was opened with");
            assertEquals("public class Baz {}\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }
}
