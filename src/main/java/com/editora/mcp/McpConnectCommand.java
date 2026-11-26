package com.editora.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * The commands Settings → MCP Server copies for an agent. {@link #stdio} adds {@code editora mcp} to Claude Code
 * once ({@link McpStdio} relays to whichever server is running, so it survives restarts and holds no token);
 * {@link #http} is the direct endpoint, valid until the server's port or token changes.
 */
public final class McpConnectCommand {

    private McpConnectCommand() {}

    /** {@code claude mcp add} for this editor's own launcher and config folder. */
    public static String stdio(Path configDir) {
        boolean windows = com.editora.Os.isWindows();
        List<String> launcher = launcher(
                ProcessHandle.current().info().command(), System.getenv("APPIMAGE"), windows, Files::isRegularFile);
        Path home = Path.of(System.getProperty("user.home", "."));
        return stdio(launcher, configDirArg(configDir, home.resolve(".editora")), windows);
    }

    /** {@code claude mcp add} for the direct HTTP endpoint and its bearer token. */
    public static String http(String url, String token) {
        return "claude mcp add --transport http editora " + url + " --header \"Authorization: Bearer " + token + "\"";
    }

    static String stdio(List<String> launcher, @Nullable Path configDir, boolean windows) {
        List<String> words = new ArrayList<>(List.of("claude", "mcp", "add", "--scope", "user", "editora", "--"));
        words.addAll(launcher);
        words.add(McpStdio.COMMAND);
        if (configDir != null) {
            words.add("--config-dir");
            words.add(configDir.toString());
        }
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(quote(w, windows));
        }
        return b.toString();
    }

    /**
     * The program that starts {@code editora mcp}: the AppImage file itself when run from one (its mount point
     * changes every launch); on Windows the console launcher {@code editora-cli.exe} beside the window one (a
     * window program has no standard output for the relay to write to); else this process's own launcher. From
     * {@code java} (a source or fat-jar run) the launcher can't be told, so {@code editora}.
     */
    static List<String> launcher(
            Optional<String> processCommand, @Nullable String appImage, boolean windows, Predicate<Path> exists) {
        if (appImage != null && !appImage.isBlank()) {
            return List.of(appImage);
        }
        if (processCommand.isEmpty() || processCommand.get().isBlank()) {
            return List.of(windows ? "editora-cli" : "editora");
        }
        String command = processCommand.get();
        // By hand, not Path: a Windows path is split on both separators wherever this runs (and is tested).
        int slash = Math.max(command.lastIndexOf('/'), windows ? command.lastIndexOf('\\') : -1);
        String name = command.substring(slash + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.equals("java") || name.equals("javaw")) {
            return List.of(windows ? "editora-cli" : "editora");
        }
        if (windows) {
            if (name.equals("editora-cli")) {
                return List.of(command);
            }
            String cli = slash < 0 ? null : command.substring(0, slash + 1) + "editora-cli.exe";
            return List.of(cli != null && exists.test(Path.of(cli)) ? cli : "editora-cli");
        }
        return List.of(command);
    }

    /** {@code configDir} when it is not the default one, which {@code editora mcp} finds by itself. */
    static @Nullable Path configDirArg(Path configDir, Path defaultDir) {
        Path dir = configDir.toAbsolutePath().normalize();
        return dir.equals(defaultDir.toAbsolutePath().normalize()) ? null : dir;
    }

    /** {@code word} as one argument of a pasted command line: as is when it needs no quoting. */
    static String quote(String word, boolean windows) {
        if (!word.isEmpty() && word.chars().allMatch(c -> plain(c) || (windows && c == '\\'))) {
            return word;
        }
        if (windows) {
            return '"' + word.replace("\"", "\\\"") + '"';
        }
        return "'" + word.replace("'", "'\\''") + "'";
    }

    private static boolean plain(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || "-_./:=@%+,".indexOf(c) >= 0;
    }
}
