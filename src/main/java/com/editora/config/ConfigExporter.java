package com.editora.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a config directory to a timestamped {@code .zip}. Used by the Settings → Advanced
 * "Export configuration" button to back up the active config dir ({@code ~/.editora},
 * {@code ~/.editora-dev}, or a {@code --config-dir} override) into the user's home directory.
 *
 * <p>The pure helpers ({@link #zipName} and {@link #export} with an injected timestamp + destination)
 * are unit-tested; {@link ConfigManager#exportConfig()} wires them to the live config dir, home dir,
 * and clock.
 */
public final class ConfigExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private ConfigExporter() {}

    /**
     * Builds the export file name for {@code configDir} at {@code now}, embedding the app
     * {@code version} and {@code userName}, e.g. {@code .editora} with version {@code 1.0.0} and user
     * {@code adriandeleon} → {@code editora-config-1.0.0-adriandeleon-2026-06-04_153012.zip}
     * ({@code .editora-dev} → an {@code editora-dev-…} prefix). A leading dot is dropped so the name
     * reads cleanly and the dir name distinguishes a dev backup from a production one; version and
     * user are filename-sanitized (see {@link #sanitize}). Pure.
     */
    static String zipName(Path configDir, String version, String userName, LocalDateTime now) {
        Path name = configDir.getFileName();
        String base = name == null ? "editora" : name.toString();
        if (base.startsWith(".")) {
            base = base.substring(1);
        }
        if (base.isBlank()) {
            base = "editora";
        }
        return base + "-config-" + sanitize(version) + "-" + sanitize(userName) + "-" + now.format(STAMP) + ".zip";
    }

    /**
     * Makes {@code s} safe to embed in a file name: keeps letters/digits/{@code . _ -}, replaces any
     * other run (spaces, slashes, etc.) with a single {@code _}, trims leading/trailing separators,
     * and falls back to {@code "unknown"} when nothing usable remains. Pure.
     */
    static String sanitize(String s) {
        if (s == null) {
            return "unknown";
        }
        String cleaned = s.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[_.-]+|[_.-]+$", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /** Top-level files that describe the running process rather than the user's configuration. */
    private static final Set<String> RUNTIME_FILES = Set.of(
            "instance.lock",
            "stores.lock",
            "instance.properties",
            "mcp-endpoint.json",
            "spawned-servers.txt",
            "editora-session.log");

    /**
     * Whether the file at {@code relative} (its path inside the config dir, forward-slash separated) belongs
     * in an export. Left out, because none of it is configuration and all of it is recreated on demand:
     *
     * <ul>
     *   <li>{@code plugins/lsp/} and {@code plugins/dap/} — downloaded language servers and debug adapters,
     *       hundreds of megabytes of binaries (a plugin the user installed, {@code plugins/<id>/}, is kept);
     *   <li>{@code jdtls-workspaces/} — the Java language server's index cache;
     *   <li>{@code sync/} — settings sync's clone of the user's repository and its backup copies of files
     *       that are in the export anyway;
     *   <li>the instance lock, the single-instance and MCP endpoint files (the latter holds a live access
     *       token), the spawned-process ledger and the session log;
     *   <li>a staging file of an atomic write that is in flight ({@code .<name>-<random>.tmp}).
     * </ul>
     *
     * Pure.
     */
    static boolean included(String relative) {
        if (relative.startsWith("plugins/lsp/")
                || relative.startsWith("plugins/dap/")
                || relative.startsWith("jdtls-workspaces/")
                || relative.startsWith("sync/")) {
            return false;
        }
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        if (name.startsWith(".") && name.endsWith(".tmp")) {
            return false;
        }
        return relative.contains("/") || !RUNTIME_FILES.contains(relative);
    }

    /**
     * Zips the configuration under {@code configDir} — every regular file {@link #included} accepts,
     * recursively, entries relative to it with forward-slash separators — into
     * {@code destinationDir/<zipName>} and returns the created file. A missing/empty config dir yields a valid
     * empty zip. {@code destinationDir} must exist.
     *
     * <p>The zip is created <b>owner-only</b>: it contains a copy of {@code settings.json} — and so of the AI
     * provider's API key — plus the user's private notes, and it lands in their home directory, which is
     * world-traversable. Locking down the config dir's own files while writing a world-readable archive of
     * them next door would protect nothing.
     *
     * <p>A failed export leaves nothing behind: the half-written zip is deleted, so a truncated archive is
     * never mistaken for a backup. A file that disappears while the directory is being read (a Local History
     * blob collected mid-walk) is skipped rather than failing the export.
     *
     * @throws IOException if the destination can't be written or a file can't be read
     */
    public static Path export(Path configDir, Path destinationDir, String version, String userName, LocalDateTime now)
            throws IOException {
        Path zip = destinationDir.resolve(zipName(configDir, version, userName, now));
        ConfigWriter.createOwnerOnly(zip); // newOutputStream truncates it and keeps the mode
        boolean complete = false;
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            if (Files.isDirectory(configDir)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(configDir)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : files) {
                    String entry = configDir.relativize(file).toString().replace('\\', '/');
                    if (!included(entry) || file.equals(zip)) {
                        continue;
                    }
                    try (InputStream in = Files.newInputStream(file)) {
                        zos.putNextEntry(new ZipEntry(entry));
                        in.transferTo(zos);
                        zos.closeEntry();
                    } catch (NoSuchFileException vanished) {
                        // deleted since the walk listed it — nothing to back up
                    }
                }
            }
            zos.finish();
            complete = true;
        } catch (UncheckedIOException walkFailure) {
            throw walkFailure.getCause();
        } finally {
            if (!complete) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException ignored) {
                    // the original failure is the one worth reporting
                }
            }
        }
        return zip;
    }
}
