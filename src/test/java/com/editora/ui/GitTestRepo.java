package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/** A real Git repository in a temp directory, driven by the installed {@code git}, for integration tests. */
final class GitTestRepo {

    record Output(int exit, byte[] out, String err) {
        String text() {
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    final Path root;

    private GitTestRepo(Path root) {
        this.root = root;
    }

    /** {@code git init -b main} in a fresh {@code repo} directory under {@code parent}, with an identity. */
    static GitTestRepo init(Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("repo")).toRealPath();
        GitTestRepo repo = new GitTestRepo(root);
        repo.git("init", "-q", "-b", "main");
        repo.git("config", "user.email", "editora-test@example.invalid");
        repo.git("config", "user.name", "Editora Test");
        repo.git("config", "core.autocrlf", "false");
        repo.git("config", "commit.gpgsign", "false");
        return repo;
    }

    static boolean windows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    Path write(String name, String content) throws IOException {
        return write(name, content.getBytes(StandardCharsets.UTF_8));
    }

    Path write(String name, byte[] content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    void commitAll(String message) throws Exception {
        git("add", "-A");
        git("commit", "-q", "--no-verify", "-m", message);
    }

    /** Runs git and fails the test on a non-zero exit. */
    Output git(String... args) throws Exception {
        Output output = tryGit(args);
        if (output.exit() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output.err() + output.text());
        }
        return output;
    }

    Output tryGit(String... args) throws Exception {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Output(process.waitFor(), out, err);
    }

    /** Writes an executable {@code /bin/sh} script and returns its path. */
    static Path script(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /** Creates a named pipe; a shell script and the test use it to hand each other a turn without sleeping. */
    static Path fifo(Path file) throws Exception {
        Process process =
                new ProcessBuilder("mkfifo", file.toString()).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IOException("mkfifo failed for " + file);
        }
        return file;
    }
}
