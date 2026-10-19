package com.editora.terminal;

import java.io.IOException;
import java.io.InputStream;
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
        CwdReport report = parseCwdUrl(url);
        return report == null ? null : report.path();
    }

    /** What an OSC 7 report says: the host it names ("" for none) and the folder's path. */
    record CwdReport(String host, String path) {}

    /**
     * Parses an OSC 7 report by hand: strips {@code file://host}, then percent-decodes the rest as UTF-8 bytes.
     * {@code URI} cut a path at {@code #} and {@code ?} and refused {@code [}, {@code "}, {@code |} and
     * {@code \}, so a folder named {@code dir #1} became {@code dir }. Null when it is not a folder report.
     */
    static CwdReport parseCwdUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String text = url.strip();
        if (text.startsWith("/")) {
            return new CwdReport("", text);
        }
        if (!text.regionMatches(true, 0, "file:", 0, 5)) {
            return null;
        }
        String rest = text.substring(5);
        String host = "";
        if (rest.startsWith("//")) {
            int slash = rest.indexOf('/', 2);
            if (slash < 0) {
                return null;
            }
            host = rest.substring(2, slash);
            rest = rest.substring(slash);
        }
        if (!rest.startsWith("/")) {
            return null;
        }
        String path = percentDecode(rest);
        return path == null || path.isEmpty() ? null : new CwdReport(host, path);
    }

    /** {@code %XX} escapes as UTF-8 bytes; a malformed escape is kept as it is. */
    static String percentDecode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * Whether a report's host is this machine: none, {@code localhost}, or this machine's name. A local tab takes
     * only those: after {@code ssh host}, the remote prompt's reports (or a printed file holding one) name another
     * machine's folder, and following them pointed relative links and New Terminal Here at the wrong place.
     */
    static boolean isThisMachine(String host) {
        if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost")) {
            return true;
        }
        String own = LocalHostName.NAME;
        if (own.isEmpty()) {
            return true; // cannot tell: as before
        }
        String shortHost = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
        String shortOwn = own.contains(".") ? own.substring(0, own.indexOf('.')) : own;
        return host.equalsIgnoreCase(own) || shortHost.equalsIgnoreCase(shortOwn);
    }

    /** This machine's name, asked once (and off the FX thread: reports arrive on the emulator's). */
    private static final class LocalHostName {
        static final String NAME = find();

        private static String find() {
            String env = System.getenv("HOSTNAME");
            if (env != null && !env.isBlank()) {
                return env.strip();
            }
            try {
                return java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.io.IOException | RuntimeException e) {
                return "";
            }
        }
    }
}
