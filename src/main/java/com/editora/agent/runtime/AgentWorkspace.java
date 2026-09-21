package com.editora.agent.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

import com.editora.vfs.Vfs;

/** Workspace capability boundary. Native file tools do not traverse symlinks or known credential stores. */
public final class AgentWorkspace {
    public static final int MAX_FILE_CHARS = 1_000_000;
    private final Path root;

    public AgentWorkspace(Path root) throws IOException {
        if (root == null || !Vfs.isLocal(root) || !Files.isDirectory(root)) {
            throw new IOException("The built-in agent requires a local workspace directory");
        }
        this.root = root.toRealPath();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("A workspace path is required");
        }
        Path candidate = root.resolve(raw).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Path is outside the agent workspace");
        }
        Path part = root;
        for (Path name : root.relativize(candidate)) {
            String component = name.toString();
            if (sensitive(component)) {
                throw new IOException("Credential or internal path is not available to native tools");
            }
            part = part.resolve(name);
            if (Files.isSymbolicLink(part)) {
                throw new IOException("Native tools do not traverse symbolic links");
            }
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && !part.toRealPath().startsWith(root)) {
                throw new IOException("A workspace path component resolved outside the workspace");
            }
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                && !candidate.toRealPath().startsWith(root)) {
            throw new IOException("Resolved path escaped the workspace");
        }
        return candidate;
    }

    public boolean allows(Path path) {
        try {
            return Vfs.isLocal(path)
                    && resolve(path.toString()).equals(path.toAbsolutePath().normalize());
        } catch (IOException | RuntimeException denied) {
            return false;
        }
    }

    private static boolean sensitive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals(".git")
                || n.equals(".ssh")
                || n.equals(".aws")
                || n.equals(".azure")
                || n.equals(".gnupg")
                || n.equals(".codex")
                || n.equals(".env")
                || n.startsWith(".env.")
                || n.equals(".npmrc")
                || n.equals(".netrc")
                || n.equals("credentials.json")
                || n.equals("id_rsa")
                || n.equals("id_ed25519")
                || n.endsWith(".pem")
                || n.endsWith(".p12")
                || n.endsWith(".key");
    }

    /** Project instructions are bounded context data, never a permission grant. No ancestor-home scanning. */
    public String instructions(AgentCancellation cancellation) throws IOException {
        Path file = resolve("AGENTS.md");
        cancellation.check();
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try (var reader = Files.newBufferedReader(file)) {
            char[] text = new char[12_001];
            int size = reader.read(text);
            return size < 0 ? "" : AgentContext.bounded(new String(text, 0, size), 12_000);
        }
    }
}
