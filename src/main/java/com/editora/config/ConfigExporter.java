package com.editora.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /**
     * Zips every regular file under {@code configDir} (recursively, entries relative to it with
     * forward-slash separators) into {@code destinationDir/<zipName>} and returns the created file.
     * A missing/empty config dir yields a valid empty zip. {@code destinationDir} must exist.
     *
     * <p>The zip is created <b>owner-only</b>: it contains a copy of {@code settings.toml} — and so of the AI
     * provider's API key — plus the user's private notes, and it lands in their home directory, which is
     * world-traversable. Locking down the config dir's own files while writing a world-readable archive of
     * them next door would protect nothing.
     *
     * @throws IOException if the destination can't be written or a file can't be read
     */
    public static Path export(Path configDir, Path destinationDir, String version, String userName, LocalDateTime now)
            throws IOException {
        Path zip = destinationDir.resolve(zipName(configDir, version, userName, now));
        ConfigWriter.createOwnerOnly(zip); // newOutputStream truncates it and keeps the mode
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            if (Files.isDirectory(configDir)) {
                try (Stream<Path> walk = Files.walk(configDir)) {
                    List<Path> files =
                            walk.filter(Files::isRegularFile).sorted().toList();
                    for (Path file : files) {
                        String entry = configDir.relativize(file).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
        return zip;
    }
}
