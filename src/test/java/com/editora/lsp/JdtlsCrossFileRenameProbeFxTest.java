package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in probe against a real jdtls: renaming a method that is used in a file with <em>no tab</em>. jdtls
 * answers with a null version for every document, so the old rule — refuse any unversioned edit to a file
 * the session does not manage — made this fail with "Rename failed". Run with
 * {@code ./mvnw test -Dtest=JdtlsCrossFileRenameProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class JdtlsCrossFileRenameProbeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static String jdtls() {
        for (String candidate : List.of(
                System.getProperty("user.home") + "/.editora/plugins/lsp/java/bin/jdtls",
                System.getProperty("user.home") + "/.editora-dev/plugins/lsp/java/bin/jdtls",
                "/opt/homebrew/bin/jdtls",
                "/usr/local/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "";
    }

    @Test
    void renamingASymbolUsedInAnUnopenedFileProducesAnApplicableEdit() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        Path root = Files.createTempDirectory("editora-lsp-rename-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path open = project.resolve("Greeter.java");
        Path closed = project.resolve("Caller.java");
        String greeter = "public class Greeter {\n    public String greet() { return \"hi\"; }\n}\n";
        Files.writeString(open, greeter);
        Files.writeString(closed, "public class Caller {\n    String run() { return new Greeter().greet(); }\n}\n");

        CountDownLatch projectReady = new CountDownLatch(1);
        List<List<Path>> blocked = new CopyOnWriteArrayList<>();
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {
            if ("ServiceReady".equals(type) && message != null) {
                projectReady.countDown(); // JDT's own language/status, sent once the project is imported
            }
        });
        manager.setOnEditBlocked(blocked::add);
        manager.setJdtlsWorkspaceBase(root.resolve("workspaces"));
        manager.configure(true, Map.of("java", jdtls()));
        try {
            manager.openDocument(open, project, "java", greeter);
            assertTrue(projectReady.await(120, TimeUnit.SECONDS), "jdtls never became ready");

            CompletableFuture<WorkspaceEditMapper.Mapped> renamed = new CompletableFuture<>();
            manager.previewRename(open, 1, 19, "salute", renamed::complete);
            WorkspaceEditMapper.Mapped mapped = renamed.get(60, TimeUnit.SECONDS);

            assertNotNull(mapped, "the rename must not be refused; blocked by " + blocked);
            assertEquals(2, mapped.edits().size(), "both the declaration and its use in the unopened file");
            var inClosed = mapped.edits().stream()
                    .filter(edit -> edit.file().getFileName().toString().equals("Caller.java"))
                    .findFirst()
                    .orElseThrow();
            assertNull(inClosed.version(), "jdtls sends no version for a document it does not have open");
            assertNotNull(inClosed.diskPreimageAt(), "the unopened file is validated against its disk state");
        } finally {
            manager.close();
        }
    }
}
