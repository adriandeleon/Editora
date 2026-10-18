package com.editora.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder's default recipients, in a {@code .gpg-id} file: one key id or fingerprint per line, the format the
 * {@code pass} password manager uses, so a password store opened in Editora encrypts new entries for the same
 * people {@code pass} would. The nearest {@code .gpg-id} at or above a folder applies.
 */
public final class GpgIdFiles {

    private GpgIdFiles() {}

    public static final String FILE_NAME = ".gpg-id";

    /** The ids in the nearest {@code .gpg-id} at or above {@code dir}; empty when there is none. Blocking I/O. */
    public static List<String> find(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path file = d.resolve(FILE_NAME);
            if (Files.isRegularFile(file)) {
                try {
                    return parse(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    /** The ids in a {@code .gpg-id} file's text: one per line, blank lines and {@code #} comments skipped. */
    static List<String> parse(String text) {
        List<String> ids = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String id = line.strip();
            int hash = id.indexOf('#');
            if (hash >= 0) {
                id = id.substring(0, hash).strip();
            }
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Writes {@code ids} as {@code dir}'s {@code .gpg-id}. Blocking I/O. */
    public static void write(Path dir, List<String> ids) throws IOException {
        Files.writeString(dir.resolve(FILE_NAME), String.join("\n", ids) + "\n", StandardCharsets.UTF_8);
    }
}
