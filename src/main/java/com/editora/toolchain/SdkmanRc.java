package com.editora.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A project's {@code .sdkmanrc}: {@code candidate=identifier} lines ({@code java=21.0.9-tem},
 * {@code maven=3.9.11}, {@code gradle=8.14.3}), {@code #} comments and blank lines, as {@code sdk env init}
 * writes it and {@code sdk env} reads it. Reading and editing are pure; {@link #read} and {@link #write} touch
 * the disk (off the FX thread).
 */
public final class SdkmanRc {

    public static final String FILE = ".sdkmanrc";

    /** What {@code sdk env init} puts at the top of a new file. */
    static final String HEADER =
            "# Enable auto-env through the sdkman_auto_env config\n" + "# Add key=value pairs of SDKs to use below\n";

    private SdkmanRc() {}

    /** The file in {@code root}. */
    public static Path fileFor(Path root) {
        return root.resolve(FILE);
    }

    /** {@code text}'s entries in file order; a later line for the same candidate wins, as SDKMAN reads it. */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** {@code root}'s entries; empty when it has no readable file. */
    public static Map<String, String> read(Path root) {
        Path file = fileFor(root);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (Files.size(file) > 64 * 1024) {
                return Map.of(); // not an .sdkmanrc anyone wrote by hand
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * {@code text} with {@code candidate} set to {@code identifier}: the existing line changed in place (its
     * comment kept), else a line added at the end; every other line as it was. A blank {@code text} becomes a
     * new file with SDKMAN's header.
     */
    public static String with(String text, String candidate, String identifier) {
        String entry = candidate + "=" + identifier;
        if (text.isBlank()) {
            return HEADER + entry + "\n";
        }
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String body = line;
            String comment = "";
            int hash = line.indexOf('#');
            if (hash >= 0) {
                body = line.substring(0, hash);
                comment = line.substring(hash);
            }
            int eq = body.indexOf('=');
            if (!replaced && eq > 0 && body.substring(0, eq).strip().equals(candidate)) {
                line = entry + (comment.isEmpty() ? "" : " " + comment);
                replaced = true;
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (!replaced) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(entry).append('\n');
        }
        return out.toString();
    }

    /** {@code text} without its {@code candidate=} lines; every other line as it was. */
    public static String without(String text, String candidate) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("(?<=\\n)")) {
            String body = line;
            int hash = body.indexOf('#');
            if (hash >= 0) {
                body = body.substring(0, hash);
            }
            int eq = body.indexOf('=');
            if (eq > 0 && body.substring(0, eq).strip().equals(candidate)) {
                continue;
            }
            out.append(line);
        }
        return out.toString();
    }

    /** Removes {@code candidate} from {@code root}'s file, when it has one. */
    public static void remove(Path root, String candidate) throws IOException {
        Path file = fileFor(root);
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String next = without(text, candidate);
        if (!next.equals(text)) {
            replace(root, next);
        }
    }

    private static void replace(Path root, String text) throws IOException {
        Path tmp = Files.createTempFile(root, ".sdkmanrc", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    fileFor(root),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Sets {@code candidate=identifier} in {@code root}'s file, creating it when there is none. */
    public static void write(Path root, String candidate, String identifier) throws IOException {
        Path file = fileFor(root);
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        replace(root, with(text, candidate, identifier));
    }
}
