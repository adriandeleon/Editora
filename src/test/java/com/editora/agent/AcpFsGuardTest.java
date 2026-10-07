package com.editora.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The agent's {@code fs/read_text_file} / {@code fs/write_text_file} are confined to the session folder: the
 * client does this I/O with the editor's privileges and no prompt, so the path is not the agent's to choose
 * freely.
 */
class AcpFsGuardTest {

    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }
    }

    @Test
    void readsAndWritesInsideTheSessionFolderAreAllowed(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path file = Files.writeString(root.resolve("a.txt"), "x");
        assertEquals(file, AcpFsGuard.checkRead(root, file.toString()));
        assertEquals(file, AcpFsGuard.checkRead(root, "a.txt"), "a relative path is relative to the session");
        assertEquals(file, AcpFsGuard.checkWrite(root, null, file.toString()));
        assertEquals(
                root.resolve("new/b.txt"),
                AcpFsGuard.checkWrite(root, null, root.resolve("new/b.txt").toString()));
        assertEquals(root.resolve("gone.txt"), AcpFsGuard.checkRead(root, "gone.txt"), "missing → the host reports it");
    }

    @Test
    void pathsOutsideTheSessionFolderAreRefusedWithAReason(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path secret = Files.writeString(tmp.resolve("id_rsa"), "KEY");
        IOException read = assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, secret.toString()));
        assertTrue(read.getMessage().contains("outside the session folder"), read.getMessage());
        assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, "../id_rsa"));
        IOException write = assertThrows(
                IOException.class,
                () -> AcpFsGuard.checkWrite(root, null, tmp.resolve("bashrc").toString()));
        assertTrue(write.getMessage().contains("outside the session folder"), write.getMessage());
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, null));
        assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, " "));
        assertThrows(IOException.class, () -> AcpFsGuard.checkRead(null, secret.toString()), "no root: nothing");
    }

    @Test
    void aSymlinkInTheProjectDoesNotWidenIt(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        symlink(root.resolve("vendor"), outside);
        symlink(root.resolve("notes.txt"), outside.resolve("secret.txt"));
        assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, "vendor/secret.txt"));
        assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, "notes.txt"));
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, "vendor/new.txt"));
        // a link that stays inside may be read through, but never written through
        Files.writeString(root.resolve("real.txt"), "r");
        symlink(root.resolve("alias.txt"), root.resolve("real.txt"));
        assertEquals(root.resolve("alias.txt"), AcpFsGuard.checkRead(root, "alias.txt"));
        IOException e = assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, "alias.txt"));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
    }

    @Test
    void readsAreRegularFilesWithinTheSizeCap(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Files.createDirectories(root.resolve("dir"));
        assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, "dir"));
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, "dir"));
        Path big = root.resolve("big.bin");
        try (var ch = Files.newByteChannel(
                big, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            ch.position(AcpFsGuard.MAX_READ_BYTES); // sparse: one byte past the cap
            ch.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        IOException e = assertThrows(IOException.class, () -> AcpFsGuard.checkRead(root, "big.bin"));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }

    @Test
    void writesIntoTheEditorsConfigDirectoryAreRefusedEvenInsideTheSessionFolder(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path config = Files.createDirectories(home.resolve(".editora"));
        IOException e = assertThrows(
                IOException.class,
                () -> AcpFsGuard.checkWrite(
                        home, config, config.resolve("settings.json").toString()));
        assertTrue(e.getMessage().contains("configuration directory"), e.getMessage());
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(home, config, ".editora/plugins/x/plugin.json"));
        symlink(home.resolve("cfg"), config);
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(home, config, "cfg/keymap.json"), "via a link");
        // reading it and writing next to it are still fine
        Files.writeString(config.resolve("settings.json"), "{}");
        assertEquals(config.resolve("settings.json"), AcpFsGuard.checkRead(home, ".editora/settings.json"));
        assertEquals(home.resolve("notes.md"), AcpFsGuard.checkWrite(home, config, "notes.md"));
    }

    @Test
    void writesIntoVersionControlMetadataAreRefused(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path git = Files.createDirectories(root.resolve(".git/hooks"));
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/master\n");
        Files.createDirectories(root.resolve("vendor/lib/.git"));
        for (String path : List.of(
                ".git/HEAD",
                ".git/hooks/pre-commit",
                ".git/new/dir/file",
                "vendor/lib/.git/config",
                ".hg/hgrc",
                ".svn/wc.db",
                ".jj/repo/store",
                ".GIT/config",
                root.resolve(".git/index").toString())) {
            IOException e = assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, path), path);
            assertTrue(e.getMessage().contains("version-control"), e.getMessage());
        }
        // a worktree's ".git" is a file naming the real repository: not the agent's to repoint either
        Path worktree = Files.createDirectories(root.resolve("wt"));
        Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere\n");
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, "wt/.git"));
        // a link does not make the metadata directory writable under another name
        symlink(root.resolve("meta"), git.getParent());
        assertThrows(IOException.class, () -> AcpFsGuard.checkWrite(root, null, "meta/config"), "via a link");
        // reading it, and writing the project's own VCS-related files, are still fine
        assertEquals(root.resolve(".git/HEAD"), AcpFsGuard.checkRead(root, ".git/HEAD"));
        assertEquals(root.resolve(".gitignore"), AcpFsGuard.checkWrite(root, null, ".gitignore"));
        assertEquals(
                root.resolve(".github/workflows/ci.yml"),
                AcpFsGuard.checkWrite(root, null, ".github/workflows/ci.yml"));
        assertEquals(root.resolve("src/git.txt"), AcpFsGuard.checkWrite(root, null, "src/git.txt"));
    }

    // --- through the client: a refused request never reaches the host and is answered with an error ---

    private final ObjectMapper mapper = new ObjectMapper();

    private final class RecordingHost implements AcpClient.Host {
        final List<String> reads = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        final Path protectedDir;

        RecordingHost(Path protectedDir) {
            this.protectedDir = protectedDir;
        }

        public void onUpdate(AcpJson.Update update) {}

        public void onExit(int code) {}

        public String readTextFile(String path, Integer line, Integer limit) {
            reads.add(path);
            return "content of " + path;
        }

        public void writeTextFile(String path, String content) {
            writes.add(path);
        }

        public CompletableFuture<String> requestPermission(String title, List<AcpJson.PermissionOption> options) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Path writeProtectedDirectory() {
            return protectedDir;
        }
    }

    private JsonNode request(AcpClient client, CapturingProcess process, String method, JsonNode params)
            throws Exception {
        Method handler =
                AcpClient.class.getDeclaredMethod("handleAgentRequest", JsonNode.class, String.class, JsonNode.class);
        handler.setAccessible(true);
        handler.invoke(client, mapper.getNodeFactory().numberNode(7), method, params);
        return process.take();
    }

    @Test
    void theClientRefusesFileRequestsOutsideTheSessionFolder(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path config = Files.createDirectories(root.resolve(".editora"));
        Path inside = Files.writeString(root.resolve("a.txt"), "x");
        Path secret = Files.writeString(tmp.resolve("id_rsa"), "KEY");
        RecordingHost host = new RecordingHost(config);
        CapturingProcess process = new CapturingProcess();
        AcpClient client = new AcpClient(List.of("unused"), root, host);
        Field processField = AcpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, process);
        // start() is what normally pairs the process with its ordered writer; this test attaches a fake.
        Field writerField = AcpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, process.writer);
        try {
            JsonNode ok = request(client, process, "fs/read_text_file", path(inside));
            assertEquals(
                    "content of " + inside, ok.path("result").path("content").asText());

            JsonNode refusedRead = request(client, process, "fs/read_text_file", path(secret));
            assertTrue(refusedRead.has("error"), refusedRead.toString());
            assertTrue(
                    refusedRead.path("error").path("message").asText().contains("outside the session folder"),
                    refusedRead.toString());
            assertEquals(List.of(inside.toString()), host.reads, "the host was never asked for the outside file");

            JsonNode refusedWrite = request(
                    client,
                    process,
                    "fs/write_text_file",
                    path(tmp.resolve("bashrc")).put("content", "evil"));
            assertTrue(refusedWrite.has("error"), refusedWrite.toString());
            JsonNode refusedConfig = request(
                    client,
                    process,
                    "fs/write_text_file",
                    path(config.resolve("settings.json")).put("content", "{}"));
            assertTrue(
                    refusedConfig.path("error").path("message").asText().contains("configuration directory"),
                    refusedConfig.toString());
            assertTrue(host.writes.isEmpty(), "no refused write reached the host");

            JsonNode okWrite = request(
                    client,
                    process,
                    "fs/write_text_file",
                    path(root.resolve("b.txt")).put("content", "fine"));
            assertFalse(okWrite.has("error"), okWrite.toString());
            assertEquals(List.of(root.resolve("b.txt").toString()), host.writes);

            // A session opened on another folder moves the boundary with it.
            Path other = Files.createDirectories(tmp.resolve("other"));
            client.newSession(other);
            process.take();
            assertTrue(
                    request(client, process, "fs/read_text_file", path(inside)).has("error"));
        } finally {
            processField.set(client, null); // fake transport has no operating-system process to reap
            client.dispose();
        }
    }

    /**
     * The guard resolves a relative path against the session folder. The host used to be handed the raw
     * string and resolved it again — against the editor's own working directory, which is rarely the project.
     */
    @Test
    void theHostIsHandedThePathTheGuardResolvedNeverTheRawString(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path inside = Files.writeString(root.resolve("a.txt"), "x");
        RecordingHost host = new RecordingHost(null);
        CapturingProcess process = new CapturingProcess();
        AcpClient client = new AcpClient(List.of("unused"), root, host);
        Field processField = AcpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, process);
        Field writerField = AcpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, process.writer);
        try {
            JsonNode read = request(
                    client,
                    process,
                    "fs/read_text_file",
                    mapper.createObjectNode().put("path", "a.txt"));
            assertFalse(read.has("error"), read.toString());
            assertEquals(List.of(inside.toString()), host.reads);

            JsonNode write = request(
                    client,
                    process,
                    "fs/write_text_file",
                    mapper.createObjectNode().put("path", "sub/../sub/todo.txt").put("content", "x"));
            assertFalse(write.has("error"), write.toString());
            assertEquals(List.of(root.resolve("sub/todo.txt").toString()), host.writes);
            for (String handed : host.writes) {
                assertTrue(Path.of(handed).isAbsolute(), handed);
            }
        } finally {
            processField.set(client, null);
            client.dispose();
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode path(Path p) {
        return mapper.createObjectNode().put("path", p.toString());
    }

    private final class CapturingProcess extends Process {
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final com.editora.lsp.AsyncPipeWriter writer =
                new com.editora.lsp.AsyncPipeWriter(sent, "acp-test-writer", () -> {});

        JsonNode take() throws Exception {
            // Messages are written by the client's writer thread, not by the call that sent them.
            assertTrue(writer.awaitDrained(10, java.util.concurrent.TimeUnit.SECONDS));
            JsonNode message = mapper.readTree(sent.toString(StandardCharsets.UTF_8));
            sent.reset();
            return message;
        }

        public OutputStream getOutputStream() {
            return sent;
        }

        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        public int waitFor() {
            return 0;
        }

        public int exitValue() {
            return 0;
        }

        public void destroy() {}

        public boolean isAlive() {
            return true;
        }
    }
}
