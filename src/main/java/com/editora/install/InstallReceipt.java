package com.editora.install;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/**
 * What an in-app install put in its folder: the step, the version and when. Written beside the files of every
 * install whose version Editora pins ({@link PinnedVersions}), so that once a release of Editora moves a pin,
 * the copy installed under the old one can be told apart and Doctor offers to update it — a folder alone says
 * that something is installed, not which release.
 *
 * <p>The file is {@value #FILE_NAME}, a small properties file: a dot file, and not a jar, so neither a launcher's
 * {@code <dir>/*} classpath nor a search for the server's binary picks it up.
 */
public record InstallReceipt(
        String id, String version, @Nullable Instant installedAt) {

    /** The receipt's file name inside an install's folder. */
    public static final String FILE_NAME = ".editora-install";

    /** The receipt's text. Pure. */
    public String format() {
        StringBuilder out = new StringBuilder("# Written by Editora when it installed this folder.\n");
        out.append("id=").append(id).append('\n');
        out.append("version=").append(version).append('\n');
        if (installedAt != null) {
            out.append("installed=").append(installedAt).append('\n');
        }
        return out.toString();
    }

    /** The receipt {@code text} holds, or null when it names no step or version. Pure. */
    public static @Nullable InstallReceipt parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        String id = p.getProperty("id", "").strip();
        String version = p.getProperty("version", "").strip();
        if (id.isEmpty() || version.isEmpty()) {
            return null;
        }
        Instant at = null;
        try {
            String when = p.getProperty("installed", "").strip();
            at = when.isEmpty() ? null : Instant.parse(when);
        } catch (DateTimeParseException e) {
            // an unreadable date does not make the version unknown
        }
        return new InstallReceipt(id, version, at);
    }

    /** Writes this receipt into the install folder {@code dir}. */
    public void write(Path dir) throws IOException {
        Files.writeString(dir.resolve(FILE_NAME), format(), StandardCharsets.UTF_8);
    }

    /** The receipt in the install folder {@code dir}, or null when it has none (an install from before receipts). */
    public static @Nullable InstallReceipt read(@Nullable Path dir) {
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 4096) {
                return null;
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
