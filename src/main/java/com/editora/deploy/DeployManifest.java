package com.editora.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * What the last deploy left on the host: for each file Editora put there, the local file it came from (size,
 * modified time, SHA-256) and what the host's copy looked like afterwards (size, modified second). Kept on this
 * machine, one file per project and deployment target, so the next deploy sends only what changed, notices a
 * file changed on the host, and removes only files Editora put there.
 *
 * <p>A small line-based text file rather than JSON: it can hold tens of thousands of entries and is read and
 * written on every run. A file that cannot be read is an empty manifest — the next deploy compares every file
 * with the host and sends what differs, which is slower but never wrong.
 */
public final class DeployManifest {

    private static final String HEADER = "editora-deploy-manifest 1";

    /**
     * One file Editora put on the host — or, {@code keptOnHost}, one the user chose to keep as the host has it
     * (Keep the Host's): a deploy leaves that file alone, and never removes it, until the local file changes
     * again (then it asks again) or the host loses it (then it is sent).
     *
     * @param size the local file's length when it was sent (also the host copy's)
     * @param modified the local file's modified time when it was sent (or kept), in milliseconds
     * @param sha256 the local file's SHA-256 when it was sent (or kept), hex
     * @param remoteSeconds the host copy's modified time afterwards, in whole seconds
     * @param keptOnHost the host's version was kept over this local one
     */
    public record Entry(long size, long modified, String sha256, long remoteSeconds, boolean keptOnHost) {
        /** A file Editora sent. */
        public Entry(long size, long modified, String sha256, long remoteSeconds) {
            this(size, modified, sha256, remoteSeconds, false);
        }
    }

    /** How a kept file's hash is written, so the format stays five columns. */
    private static final String KEPT = "kept:";

    private final TreeMap<String, Entry> entries;

    public DeployManifest() {
        this(new TreeMap<>());
    }

    private DeployManifest(TreeMap<String, Entry> entries) {
        this.entries = entries;
    }

    public static DeployManifest of(Map<String, Entry> entries) {
        return new DeployManifest(new TreeMap<>(entries));
    }

    public @Nullable Entry get(String relative) {
        return entries.get(relative);
    }

    public Map<String, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Where the manifest of the deployment to {@code target} (an id of host and folder) from {@code project} lives. */
    public static Path fileFor(Path stateDir, Path project, String target) {
        String key = project.toAbsolutePath().normalize() + "\n" + target;
        return stateDir.resolve("deploy")
                .resolve(Hashing.sha256(key.getBytes(StandardCharsets.UTF_8)).substring(0, 24) + ".manifest");
    }

    /** The manifest in {@code file}; empty when it is absent or unreadable. */
    public static DeployManifest load(Path file) {
        TreeMap<String, Entry> entries = new TreeMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new DeployManifest(entries);
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
                return new DeployManifest(entries);
            }
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split("\t", 5);
                if (f.length == 5 && LocalFiles.acceptable(f[4])) {
                    boolean kept = f[2].startsWith(KEPT);
                    entries.put(
                            f[4],
                            new Entry(
                                    Long.parseLong(f[0]),
                                    Long.parseLong(f[1]),
                                    kept ? f[2].substring(KEPT.length()) : f[2],
                                    Long.parseLong(f[3]),
                                    kept));
                }
            }
        } catch (IOException | RuntimeException e) {
            return new DeployManifest(new TreeMap<>());
        }
        return new DeployManifest(entries);
    }

    /** Writes this manifest to {@code file}, aside and then moved in, so a crash never leaves half of one. */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        entries.forEach((relative, e) -> out.append(e.size())
                .append('\t')
                .append(e.modified())
                .append('\t')
                .append(e.keptOnHost() ? KEPT + e.sha256() : e.sha256())
                .append('\t')
                .append(e.remoteSeconds())
                .append('\t')
                .append(relative)
                .append('\n'));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, out, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
