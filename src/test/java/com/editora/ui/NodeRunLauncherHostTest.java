package com.editora.ui;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.editora.run.NodeRuntimes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JS review C109: on a host (here a non-default file system standing in for SFTP) a Node program runs from its
 * package root, as it does locally — not from the file's own folder — and under the Node.js the host's
 * {@code .nvmrc}/{@code .node-version} names when the host's nvm has it.
 */
class NodeRunLauncherHostTest {

    @org.junit.jupiter.api.BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    @Test
    void theHostsPackageRootIsFoundThroughTheFilesOwnFileSystem(@TempDir Path tmp) throws Exception {
        URI uri = URI.create("jar:" + tmp.resolve("host.zip").toUri());
        try (FileSystem host = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path pkg = host.getPath("/srv/app");
            Files.createDirectories(pkg.resolve("src/lib"));
            Files.writeString(pkg.resolve("package.json"), "{}");
            Path file = Files.writeString(pkg.resolve("src/lib/main.js"), "");
            assertEquals(pkg, NodeRunLauncher.hostPackageRoot(file, null));
            assertEquals(pkg, NodeRunLauncher.hostPackageRoot(file, host.getPath("/srv")));
            Path loose = Files.writeString(host.getPath("/tmp-script.js"), "");
            assertEquals(loose.getParent(), NodeRunLauncher.hostPackageRoot(loose, null), "no package: its folder");
        }
    }

    private static final String NVM = "/home/me/.nvm/versions/node/";

    /** What the host's nvm listing returns, counting how often it was asked. */
    private static Supplier<List<String>> listing(AtomicInteger asked, String... versions) {
        return () -> {
            asked.incrementAndGet();
            return java.util.Arrays.stream(versions)
                    .map(v -> NVM + v + "/bin/node")
                    .toList();
        };
    }

    @Test
    void aHostsNvmrcPicksTheHostsNvmNode(@TempDir Path tmp) throws Exception {
        URI uri = URI.create("jar:" + tmp.resolve("host.zip").toUri());
        try (FileSystem host = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path repo = host.getPath("/srv/repo");
            Path pkg = repo.resolve("apps/web");
            Files.createDirectories(pkg.resolve("src"));
            Files.writeString(pkg.resolve("package.json"), "{}");
            Files.writeString(repo.resolve(".nvmrc"), "# pinned\nv20\n"); // a monorepo pins at the top
            Path file = Files.writeString(pkg.resolve("src/main.js"), "");
            AtomicInteger asked = new AtomicInteger();

            NodeRuntimes.NodeRuntime node = NodeRunLauncher.hostRuntime(
                    file, repo, "", null, listing(asked, "v18.20.4", "v20.9.0", "v20.11.1", "v22.3.0"));

            assertEquals(List.of(NVM + "v20.11.1/bin/node"), node.command(), "the newest 20.x the host has");
            assertEquals(NodeRuntimes.Source.VERSION_FILE, node.source());
            assertEquals(".nvmrc", node.versionFile());
            assertEquals(1, asked.get(), "one listing per run");
            assertNull(NodeRunLauncher.pinNotice(node));
            assertNotNull(NodeRunLauncher.source(node));
            assertTrue(NodeRunLauncher.source(node).contains(".nvmrc"), NodeRunLauncher.source(node));

            // With no project, the walk goes up to the package root, which holds a .node-version.
            Files.writeString(pkg.resolve(".node-version"), "22.3.0");
            assertEquals(
                    List.of(NVM + "v22.3.0/bin/node"),
                    NodeRunLauncher.hostRuntime(file, null, "", null, listing(asked, "v22.3.0"))
                            .command());
        }
    }

    @Test
    void aVersionTheHostsNvmLacksFallsBackAndSaysSo(@TempDir Path tmp) throws Exception {
        URI uri = URI.create("jar:" + tmp.resolve("host.zip").toUri());
        try (FileSystem host = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path pkg = host.getPath("/srv/app");
            Files.createDirectories(pkg);
            Files.writeString(pkg.resolve("package.json"), "{}");
            Files.writeString(pkg.resolve(".nvmrc"), "16");
            Path file = Files.writeString(pkg.resolve("main.js"), "");
            AtomicInteger asked = new AtomicInteger();

            NodeRuntimes.NodeRuntime tool =
                    NodeRunLauncher.hostRuntime(file, pkg, "", "/opt/node/bin/node", listing(asked, "v20.11.1"));
            assertEquals(List.of("/opt/node/bin/node"), tool.command(), "the host's Node.js setting");
            assertEquals(NodeRuntimes.Source.SETTING, tool.source());
            assertEquals("16", tool.unmetVersion());
            String notice = NodeRunLauncher.pinNotice(tool);
            assertNotNull(notice, "the console says the pin was not met, as locally");
            assertTrue(notice.contains(".nvmrc") && notice.contains("16"), notice);

            NodeRuntimes.NodeRuntime path = NodeRunLauncher.hostRuntime(file, pkg, "", null, List::of);
            assertEquals(List.of("node"), path.command(), "no nvm, no tool: node on the host's PATH");
            assertEquals(NodeRuntimes.Source.PATH, path.source());
            assertEquals("16", path.unmetVersion());
        }
    }

    @Test
    void theHostIsNotAskedWhenNothingCanBePicked(@TempDir Path tmp) throws Exception {
        URI uri = URI.create("jar:" + tmp.resolve("host.zip").toUri());
        try (FileSystem host = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path pkg = host.getPath("/srv/app");
            Files.createDirectories(pkg);
            Files.writeString(pkg.resolve("package.json"), "{}");
            Path file = Files.writeString(pkg.resolve("main.js"), "");
            AtomicInteger asked = new AtomicInteger();

            assertEquals(
                    List.of("node"),
                    NodeRunLauncher.hostRuntime(file, pkg, "", null, listing(asked, "v20.11.1"))
                            .command(),
                    "no version file");
            Files.writeString(pkg.resolve(".nvmrc"), "20");
            assertEquals(
                    List.of("/usr/bin/node", "--no-warnings"),
                    NodeRunLauncher.hostRuntime(file, pkg, "/usr/bin/node --no-warnings", null, listing(asked))
                            .command(),
                    "a run configuration's Interpreter beats the version file");
            Files.writeString(pkg.resolve(".nvmrc"), "lts/*");
            assertEquals(
                    "lts/*",
                    NodeRunLauncher.hostRuntime(file, pkg, "", null, listing(asked, "v20.11.1"))
                            .unmetVersion(),
                    "an alias is not resolved, as locally");
            Files.writeString(pkg.resolve(".nvmrc"), "20; rm -rf ~");
            NodeRuntimes.NodeRuntime odd = NodeRunLauncher.hostRuntime(file, pkg, "", null, listing(asked, "v20.11.1"));
            assertEquals(List.of("node"), odd.command(), "a version that is not one picks nothing");
            assertEquals(0, asked.get(), "the host was never asked");
        }
    }
}
