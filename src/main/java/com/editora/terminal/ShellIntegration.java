package com.editora.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.process.TargetCache;

/**
 * The scripts that make bash and zsh tell the terminal where they are and where each prompt, command and exit
 * status is (OSC 7, OSC 133), and where they are installed: under Editora's config folder for shells on this
 * machine, in a target's {@code ~/.cache/editora} for shells elsewhere. Users' own startup files are never
 * edited: bash starts with {@code --init-file editora.bash}, which reads the login files itself; zsh starts with
 * {@code ZDOTDIR} pointing at wrappers that run the user's files first.
 *
 * <p>The folder is named by a hash of the scripts, so an updated Editora installs new ones beside the old ones
 * and a target's cache, which never overwrites, stays correct.
 */
public final class ShellIntegration {

    private ShellIntegration() {}

    /** The scripts, relative to the integration folder ({@code /}-separated). */
    static final List<String> FILES =
            List.of("editora.bash", "zsh/.zshenv", "zsh/.zprofile", "zsh/.zshrc", "zsh/.zlogin");

    private static final String RESOURCES = "/com/editora/terminal/shell-integration/";

    private static volatile Map<String, String> scripts;

    /** The scripts' text by relative name, read once. */
    static Map<String, String> scripts() {
        Map<String, String> known = scripts;
        if (known != null) {
            return known;
        }
        Map<String, String> read = new LinkedHashMap<>();
        for (String name : FILES) {
            try (InputStream in = ShellIntegration.class.getResourceAsStream(RESOURCES + name)) {
                if (in == null) {
                    throw new IllegalStateException("missing shell integration script " + name);
                }
                read.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read shell integration script " + name, e);
            }
        }
        scripts = Map.copyOf(read);
        return scripts;
    }

    /** The folder name: the first 12 hex digits of the scripts' SHA-256. */
    static String version() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String name : FILES) {
                sha.update(name.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(scripts().get(name).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Installs the scripts under {@code configDir} for shells on this machine; returns their folder. */
    public static Path installLocal(Path configDir) throws IOException {
        Path folder = configDir.resolve("terminal").resolve("shell-integration").resolve(version());
        for (String name : FILES) {
            Path file = folder.resolve(name);
            if (Files.isRegularFile(file)) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Path aside = Files.createTempFile(file.getParent(), ".editora-", ".tmp");
            Files.writeString(aside, scripts().get(name));
            Files.move(aside, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return folder;
    }

    /**
     * Installs the scripts in a target's cache for shells there; returns their folder as the target names it.
     * Asks the target: call it off the FX thread, or accept the wait.
     */
    public static String installOn(TargetCache cache) throws IOException {
        String base = "shell-integration/" + version();
        String bash = null;
        for (String name : FILES) {
            String written = cache.putText(base + "/" + name, scripts().get(name));
            if (name.equals("editora.bash")) {
                bash = written;
            }
        }
        return bash.substring(0, bash.length() - "/editora.bash".length());
    }

    /**
     * The path an OSC 7 report names: the path of its {@code file://host/path} URL, percent-decoded, or the text
     * itself when a shell sent a bare path. Null when it is neither.
     */
    static String pathOfCwdUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String text = url.strip();
        if (text.startsWith("/")) {
            return text;
        }
        if (!text.regionMatches(true, 0, "file:", 0, 5)) {
            return null;
        }
        try {
            String path = new URI(text.replace(" ", "%20")).getPath();
            return path == null || path.isEmpty() ? null : path;
        } catch (Exception e) {
            return null;
        }
    }
}
