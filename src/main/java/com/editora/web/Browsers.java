package com.editora.web;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Detects installed desktop browsers and builds the per-OS command that opens a URL in a chosen one.
 *
 * <p>Known browsers are probed per OS: macOS app bundles; on Linux a binary on the PATH or a desktop launcher
 * ({@code .desktop} entry — native, Flatpak or Snap); on Windows the machine-wide and per-user install folders.
 * On Linux every other launcher that is a web browser (LibreWolf, Zen, a Flatpak-only browser…) is offered too,
 * launched through its own {@code Exec} line: one in the {@code WebBrowser} category, or handling both {@code
 * https} links and {@code text/html} — not every app that merely opens links (a chat app), not one whose {@code
 * TryExec} binary is gone, and not one meant for another desktop ({@code OnlyShowIn}/{@code NotShowIn}).
 *
 * <p>Detection + argv construction are <em>pure</em> (the OS, the filesystem/PATH probes, the environment and
 * the launchers are injected), so they're unit-tested; only {@link #detect()} and {@link #launchArgv(Browser,
 * String)} touch the real OS. The {@link #SYSTEM_DEFAULT} entry is special — the caller opens it via {@code
 * HostServices.showDocument} rather than launching a subprocess.
 */
public final class Browsers {

    /** A browser the user can pick: a stable {@code id} (used in argv lookup + the last-used setting) + name. */
    public record Browser(String id, String displayName) {}

    public enum Os {
        MAC,
        LINUX,
        WINDOWS
    }

    /** Sentinel id for "the OS default browser" — launched via HostServices, not a subprocess. */
    public static final String SYSTEM_DEFAULT = "default";

    /** Id prefix of a browser found only as a Linux launcher: {@code desktop:<file id>}. */
    static final String DESKTOP_PREFIX = "desktop:";

    /** A Linux desktop launcher that handles {@code http} links: its file id, {@code Name} and {@code Exec}. */
    record DesktopEntry(String fileId, String name, String exec) {}

    /** A known browser, with the per-OS hooks needed to detect it and build its launch command. */
    private record Spec(
            String id,
            String name,
            @Nullable String macApp, // "open -a <macApp>"; also the bundle base name "<macApp>.app"
            boolean macAlways, // Safari: present on every Mac, no bundle probe needed
            List<String> linuxBins, // candidate binaries on PATH, first match wins
            List<String> desktopIds, // its launchers (native, Flatpak, Snap), used when no binary is on PATH
            List<String> winPaths) {} // %VAR%-templated exe paths, first existing wins

    private static final String PF = "%ProgramFiles%\\";
    private static final String PF86 = "%ProgramFiles(x86)%\\";
    private static final String LAD = "%LOCALAPPDATA%\\";

    /** Known browsers in display order. Safari is macOS-only (and always present there); Arc is macOS-only. */
    private static final List<Spec> SPECS = List.of(
            new Spec("safari", "Safari", "Safari", true, List.of(), List.of(), List.of()),
            new Spec(
                    "chrome",
                    "Google Chrome",
                    "Google Chrome",
                    false,
                    List.of("google-chrome", "google-chrome-stable"),
                    List.of("google-chrome.desktop", "com.google.Chrome.desktop"),
                    List.of(
                            PF + "Google\\Chrome\\Application\\chrome.exe",
                            PF86 + "Google\\Chrome\\Application\\chrome.exe",
                            LAD + "Google\\Chrome\\Application\\chrome.exe")),
            new Spec(
                    "chromium",
                    "Chromium",
                    "Chromium",
                    false,
                    List.of("chromium", "chromium-browser"),
                    List.of(
                            "chromium.desktop",
                            "chromium-browser.desktop",
                            "org.chromium.Chromium.desktop",
                            "chromium_chromium.desktop"),
                    List.of(LAD + "Chromium\\Application\\chrome.exe")),
            new Spec(
                    "firefox",
                    "Firefox",
                    "Firefox",
                    false,
                    List.of("firefox", "firefox-esr"),
                    List.of(
                            "firefox.desktop",
                            "firefox-esr.desktop",
                            "org.mozilla.firefox.desktop",
                            "firefox_firefox.desktop"),
                    List.of(
                            PF + "Mozilla Firefox\\firefox.exe",
                            PF86 + "Mozilla Firefox\\firefox.exe",
                            LAD + "Mozilla Firefox\\firefox.exe")),
            new Spec(
                    "firefox-dev",
                    "Firefox Developer Edition",
                    "Firefox Developer Edition",
                    false,
                    List.of("firefox-developer-edition"),
                    List.of("firefox-developer-edition.desktop"),
                    List.of(PF + "Firefox Developer Edition\\firefox.exe")),
            new Spec(
                    "edge",
                    "Microsoft Edge",
                    "Microsoft Edge",
                    false,
                    List.of("microsoft-edge", "microsoft-edge-stable"),
                    List.of("microsoft-edge.desktop", "com.microsoft.Edge.desktop"),
                    List.of(
                            PF86 + "Microsoft\\Edge\\Application\\msedge.exe",
                            PF + "Microsoft\\Edge\\Application\\msedge.exe")),
            new Spec(
                    "brave",
                    "Brave",
                    "Brave Browser",
                    false,
                    List.of("brave-browser", "brave"),
                    List.of("brave-browser.desktop", "com.brave.Browser.desktop", "brave_brave.desktop"),
                    List.of(
                            PF + "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                            PF86 + "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                            LAD + "BraveSoftware\\Brave-Browser\\Application\\brave.exe")),
            new Spec(
                    "vivaldi",
                    "Vivaldi",
                    "Vivaldi",
                    false,
                    List.of("vivaldi", "vivaldi-stable"),
                    List.of("vivaldi-stable.desktop", "com.vivaldi.Vivaldi.desktop"),
                    List.of(LAD + "Vivaldi\\Application\\vivaldi.exe", PF + "Vivaldi\\Application\\vivaldi.exe")),
            new Spec(
                    "opera",
                    "Opera",
                    "Opera",
                    false,
                    List.of("opera"),
                    List.of("opera.desktop", "com.opera.Opera.desktop", "opera_opera.desktop"),
                    List.of(LAD + "Programs\\Opera\\opera.exe", PF + "Opera\\opera.exe")),
            new Spec("arc", "Arc", "Arc", false, List.of(), List.of(), List.of()));

    private Browsers() {}

    public static Os currentOs() {
        return switch (com.editora.Os.current()) {
            case MAC -> Os.MAC;
            case WINDOWS -> Os.WINDOWS;
            case LINUX -> Os.LINUX;
        };
    }

    /**
     * Detects installed browsers on the real OS, always appending the {@link #SYSTEM_DEFAULT} entry last so the
     * list is never empty.
     */
    public static List<Browser> detect() {
        String home = System.getProperty("user.home", "");
        Os os = currentOs();
        List<DesktopEntry> desktop = os == Os.LINUX ? desktopEntries(home, System::getenv) : List.of();
        return detect(os, Files::exists, Browsers::binOnPath, home, System::getenv, desktop);
    }

    /** Pure detection (no environment, no launchers). */
    static List<Browser> detect(Os os, Predicate<Path> fileExists, Predicate<String> binOnPath, String home) {
        return detect(os, fileExists, binOnPath, home, name -> null, List.of());
    }

    /**
     * Pure detection. {@code fileExists} probes bundle/exe paths; {@code binOnPath} tests a Linux binary name;
     * {@code env} expands the Windows {@code %VAR%} folders; {@code desktop} are the Linux launchers.
     */
    static List<Browser> detect(
            Os os,
            Predicate<Path> fileExists,
            Predicate<String> binOnPath,
            String home,
            Function<String, @Nullable String> env,
            List<DesktopEntry> desktop) {
        List<Browser> out = new ArrayList<>();
        for (Spec s : SPECS) {
            if (present(s, os, fileExists, binOnPath, home, env, desktop)) {
                out.add(new Browser(s.id(), s.name()));
            }
        }
        if (os == Os.LINUX) {
            List<DesktopEntry> others = new ArrayList<>();
            for (DesktopEntry e : desktop) {
                if (specForDesktopId(e.fileId()) == null
                        && !e.fileId().toLowerCase(Locale.ROOT).contains("editora")) {
                    others.add(e);
                }
            }
            others.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
            for (DesktopEntry e : others) {
                out.add(new Browser(DESKTOP_PREFIX + e.fileId(), e.name()));
            }
        }
        out.add(new Browser(SYSTEM_DEFAULT, "System Default")); // the UI localizes this label by id
        return out;
    }

    private static boolean present(
            Spec s,
            Os os,
            Predicate<Path> fileExists,
            Predicate<String> binOnPath,
            String home,
            Function<String, @Nullable String> env,
            List<DesktopEntry> desktop) {
        return switch (os) {
            case MAC -> {
                String app = s.macApp();
                yield app != null && (s.macAlways() || macBundleExists(app, fileExists, home));
            }
            case LINUX ->
                s.linuxBins().stream().anyMatch(binOnPath)
                        || desktop.stream().anyMatch(e -> s.desktopIds().contains(e.fileId()));
            case WINDOWS -> windowsExe(s, env, fileExists) != null;
        };
    }

    private static boolean macBundleExists(String app, Predicate<Path> fileExists, String home) {
        return fileExists.test(Path.of("/Applications", app + ".app"))
                || (!home.isEmpty() && fileExists.test(Path.of(home, "Applications", app + ".app")));
    }

    private static @Nullable String windowsExe(
            Spec s, Function<String, @Nullable String> env, Predicate<Path> fileExists) {
        for (String template : s.winPaths()) {
            String p = expandWindows(template, env);
            if (p != null && fileExists.test(Path.of(p))) {
                return p;
            }
        }
        return null;
    }

    /**
     * Expands {@code %ProgramFiles%}, {@code %ProgramFiles(x86)%} and {@code %LOCALAPPDATA%} (the first two
     * default to {@code C:\Program Files} and {@code C:\Program Files (x86)}); null when a variable is unknown.
     */
    static @Nullable String expandWindows(String template, Function<String, @Nullable String> env) {
        String out = template;
        for (String var : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String token = "%" + var + "%";
            if (!out.contains(token)) {
                continue;
            }
            String value = env.apply(var);
            if (value == null || value.isBlank()) {
                value = switch (var) {
                    case "ProgramFiles" -> "C:\\Program Files";
                    case "ProgramFiles(x86)" -> "C:\\Program Files (x86)";
                    default -> null;
                };
            }
            if (value == null) {
                return null;
            }
            String v = value.endsWith("\\") ? value.substring(0, value.length() - 1) : value;
            out = out.replace(token, v);
        }
        return out;
    }

    /**
     * The argv that opens {@code url} in {@code browser} on the real OS. Empty for {@link #SYSTEM_DEFAULT}
     * (the caller uses HostServices) or an undetectable browser (the caller falls back to the default).
     */
    public static List<String> launchArgv(Browser browser, String url) {
        Os os = currentOs();
        List<DesktopEntry> desktop =
                os == Os.LINUX ? desktopEntries(System.getProperty("user.home", ""), System::getenv) : List.of();
        return launchArgv(os, browser.id(), url, Browsers::resolveBin, Files::exists, System::getenv, desktop);
    }

    /** Pure argv builder without an environment or launchers. */
    static List<String> launchArgv(
            Os os, String id, String url, UnaryOperator<String> resolveBin, Predicate<Path> fileExists) {
        return launchArgv(os, id, url, resolveBin, fileExists, name -> null, List.of());
    }

    /**
     * Pure argv builder. {@code resolveBin} maps a Linux binary name to an absolute path (or returns it
     * unchanged when not found); {@code fileExists} probes Windows exe paths; {@code env} expands their
     * folders; {@code desktop} are the Linux launchers.
     */
    static List<String> launchArgv(
            Os os,
            String id,
            String url,
            UnaryOperator<String> resolveBin,
            Predicate<Path> fileExists,
            Function<String, @Nullable String> env,
            List<DesktopEntry> desktop) {
        if (SYSTEM_DEFAULT.equals(id)) {
            return List.of();
        }
        if (id.startsWith(DESKTOP_PREFIX)) {
            if (os != Os.LINUX) {
                return List.of();
            }
            String fileId = id.substring(DESKTOP_PREFIX.length());
            for (DesktopEntry e : desktop) {
                if (e.fileId().equals(fileId)) {
                    return execArgv(e.exec(), url);
                }
            }
            return List.of();
        }
        Spec s = specFor(id);
        if (s == null) {
            return List.of();
        }
        return switch (os) {
            case MAC -> {
                String app = s.macApp();
                yield app == null ? List.<String>of() : List.of("/usr/bin/open", "-a", app, url);
            }
            case LINUX -> {
                for (String bin : s.linuxBins()) {
                    String resolved = resolveBin.apply(bin);
                    if (resolved.indexOf('/') >= 0) { // resolved to an absolute path ⇒ present
                        yield List.of(resolved, url);
                    }
                }
                for (String desktopId : s.desktopIds()) { // a Flatpak or Snap install: its launcher's command
                    for (DesktopEntry e : desktop) {
                        if (e.fileId().equals(desktopId)) {
                            List<String> argv = execArgv(e.exec(), url);
                            if (!argv.isEmpty()) {
                                yield argv;
                            }
                        }
                    }
                }
                yield List.of();
            }
            case WINDOWS -> {
                String exe = windowsExe(s, env, fileExists);
                yield exe == null ? List.of() : List.of(exe, url);
            }
        };
    }

    /**
     * The program of browser {@code id} on the real OS, for a debugger to start with its own flags (not through
     * {@code open -a} or a desktop launcher); null when it is not found that way (a Flatpak or Snap install), or for
     * the system default.
     */
    public static @Nullable String executable(String id) {
        return executable(
                currentOs(),
                id,
                Browsers::resolveBin,
                Files::exists,
                System::getenv,
                System.getProperty("user.home", ""));
    }

    /** Pure {@link #executable(String)}. */
    static @Nullable String executable(
            Os os,
            String id,
            UnaryOperator<String> resolveBin,
            Predicate<Path> fileExists,
            Function<String, @Nullable String> env,
            String home) {
        Spec s = specFor(id);
        if (s == null) {
            return null;
        }
        return switch (os) {
            case MAC -> {
                String app = s.macApp();
                if (app == null) {
                    yield null;
                }
                List<Path> bundles = new ArrayList<>(List.of(Path.of("/Applications", app + ".app")));
                if (!home.isEmpty()) {
                    bundles.add(Path.of(home, "Applications", app + ".app"));
                }
                for (Path bundle : bundles) {
                    Path program = bundle.resolve("Contents").resolve("MacOS").resolve(app);
                    if (fileExists.test(program)) {
                        yield program.toString();
                    }
                }
                yield null;
            }
            case LINUX -> {
                for (String bin : s.linuxBins()) {
                    String resolved = resolveBin.apply(bin);
                    if (resolved.indexOf('/') >= 0) {
                        yield resolved;
                    }
                }
                yield null;
            }
            case WINDOWS -> windowsExe(s, env, fileExists);
        };
    }

    /** The display name of a known browser id (or of a launcher id, its file name), for a browser not found. */
    public static String knownName(String id) {
        Spec s = specFor(id);
        if (s != null) {
            return s.name();
        }
        if (id.startsWith(DESKTOP_PREFIX)) {
            String f = id.substring(DESKTOP_PREFIX.length());
            return f.endsWith(".desktop") ? f.substring(0, f.length() - ".desktop".length()) : f;
        }
        return id;
    }

    private static @Nullable Spec specFor(String id) {
        for (Spec s : SPECS) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    private static @Nullable Spec specForDesktopId(String fileId) {
        for (Spec s : SPECS) {
            if (s.desktopIds().contains(fileId)) {
                return s;
            }
        }
        return null;
    }

    // --- Linux desktop launchers -----------------------------------------------------------------

    /**
     * The launchers that handle {@code http} links, from the XDG application folders (user first, so a user's
     * launcher hides the system one of the same id) plus the Flatpak and Snap export folders.
     */
    static List<DesktopEntry> desktopEntries(String home, Function<String, @Nullable String> env) {
        return desktopEntries(home, env, Browsers::binOnPath);
    }

    /** {@link #desktopEntries(String, Function)} with the {@code TryExec} probe supplied (tests). */
    static List<DesktopEntry> desktopEntries(
            String home, Function<String, @Nullable String> env, Predicate<String> tryExecFound) {
        List<String> desktops = currentDesktops(env.apply("XDG_CURRENT_DESKTOP"));
        Map<String, DesktopEntry> byId = new LinkedHashMap<>();
        for (Path dir : applicationDirs(home, env)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.desktop")) {
                for (Path f : files) {
                    String fileId = String.valueOf(f.getFileName());
                    if (byId.containsKey(fileId)) {
                        continue;
                    }
                    try {
                        DesktopEntry e = parseDesktopEntry(fileId, Files.readAllLines(f), desktops, tryExecFound);
                        if (e != null) {
                            byId.put(fileId, e);
                        } else {
                            byId.put(fileId, new DesktopEntry(fileId, "", "")); // shadows a system copy
                        }
                    } catch (IOException | RuntimeException unreadable) {
                        // skip it
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // skip the folder
            }
        }
        return byId.values().stream().filter(e -> !e.exec().isEmpty()).toList();
    }

    /** {@code $XDG_DATA_HOME}, the user and system Flatpak exports, each of {@code $XDG_DATA_DIRS}, and Snap's. */
    static List<Path> applicationDirs(String home, Function<String, @Nullable String> env) {
        List<Path> dirs = new ArrayList<>();
        String dataHome = env.apply("XDG_DATA_HOME");
        if (dataHome != null && !dataHome.isBlank()) {
            dirs.add(Path.of(dataHome, "applications"));
        } else if (!home.isEmpty()) {
            dirs.add(Path.of(home, ".local", "share", "applications"));
        }
        if (!home.isEmpty()) {
            dirs.add(Path.of(home, ".local", "share", "flatpak", "exports", "share", "applications"));
        }
        dirs.add(Path.of("/var/lib/flatpak/exports/share/applications"));
        String dataDirs = env.apply("XDG_DATA_DIRS");
        String list = dataDirs == null || dataDirs.isBlank() ? "/usr/local/share:/usr/share" : dataDirs;
        for (String d : list.split(":")) {
            if (!d.isBlank()) {
                dirs.add(Path.of(d, "applications"));
            }
        }
        dirs.add(Path.of("/var/lib/snapd/desktop/applications"));
        return dirs.stream().distinct().toList();
    }

    /** {@code XDG_CURRENT_DESKTOP} ({@code "ubuntu:GNOME"}) as its list of names; empty when unset. */
    static List<String> currentDesktops(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(":"))
                .map(String::strip)
                .filter(d -> !d.isEmpty())
                .toList();
    }

    /** {@link #parseDesktopEntry(String, List, List, Predicate)} on no particular desktop, every binary present. */
    static @Nullable DesktopEntry parseDesktopEntry(String fileId, List<String> lines) {
        return parseDesktopEntry(fileId, lines, List.of(), bin -> true);
    }

    /**
     * Parses a {@code .desktop} file: an {@code Application} (the {@code Type} key is required) in the {@code
     * [Desktop Entry]} group that lists {@code x-scheme-handler/http} in its {@code MimeType} and is a browser — in
     * the {@code WebBrowser} category, or also handling {@code x-scheme-handler/https} and {@code text/html} — is
     * not {@code Hidden} or {@code NoDisplay}, is shown on one of {@code desktops} ({@code OnlyShowIn}/{@code
     * NotShowIn}; an empty list matches any), has a {@code TryExec} that {@code tryExecFound} finds (when it has
     * one), and an {@code Exec} that takes a URL ({@code %u}/{@code %U}, or no field code; a launcher that takes only
     * files, {@code %f}/{@code %F}, is not one); else null.
     */
    static @Nullable DesktopEntry parseDesktopEntry(
            String fileId, List<String> lines, List<String> desktops, Predicate<String> tryExecFound) {
        boolean inEntry = false;
        Map<String, String> keys = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                inEntry = line.equals("[Desktop Entry]");
                continue;
            }
            int eq = line.indexOf('=');
            if (inEntry && eq > 0) {
                keys.putIfAbsent(
                        line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        String type = keys.getOrDefault("Type", "");
        String exec = keys.getOrDefault("Exec", "");
        String name = keys.getOrDefault("Name", "");
        List<String> mime = List.of(keys.getOrDefault("MimeType", "").split(";"));
        boolean http = mime.contains("x-scheme-handler/http");
        boolean browser =
                List.of(keys.getOrDefault("Categories", "").split(";")).contains("WebBrowser")
                        || mime.contains("x-scheme-handler/https") && mime.contains("text/html");
        boolean hidden = "true".equalsIgnoreCase(keys.get("Hidden")) || "true".equalsIgnoreCase(keys.get("NoDisplay"));
        if (!type.equals("Application")
                || !http
                || !browser
                || hidden
                || exec.isBlank()
                || name.isBlank()
                || !shownOn(keys.get("OnlyShowIn"), keys.get("NotShowIn"), desktops)
                || !takesUrl(exec)) {
            return null;
        }
        String tryExec = keys.get("TryExec");
        if (tryExec != null && !tryExec.isBlank() && !tryExecFound.test(unescapeValue(tryExec))) {
            return null; // the launcher of an uninstalled program
        }
        return new DesktopEntry(fileId, unescapeValue(name), exec);
    }

    /** {@code OnlyShowIn}/{@code NotShowIn} against the current desktops (unknown desktops: everything shows). */
    private static boolean shownOn(@Nullable String only, @Nullable String not, List<String> desktops) {
        if (desktops.isEmpty()) {
            return true;
        }
        if (only != null && !only.isBlank()) {
            List<String> allowed = List.of(only.split(";"));
            if (desktops.stream().noneMatch(allowed::contains)) {
                return false;
            }
        }
        if (not != null && !not.isBlank()) {
            List<String> refused = List.of(not.split(";"));
            return desktops.stream().noneMatch(refused::contains);
        }
        return true;
    }

    /** Whether an {@code Exec} line can take a URL: it has {@code %u}/{@code %U}, or no file field code. */
    private static boolean takesUrl(String exec) {
        return exec.contains("%u") || exec.contains("%U") || !(exec.contains("%f") || exec.contains("%F"));
    }

    /**
     * The argv of a launcher's {@code Exec} line for {@code url} (Desktop Entry spec): quoted arguments, the
     * {@code %u}/{@code %U} field code replaced by the URL (appended when there is none), {@code %%} kept as
     * {@code %}, the other field codes — {@code %f}/{@code %F} too, which name local files, not URLs — and
     * Flatpak's {@code @@}/{@code @@u} markers dropped. Empty when the line cannot be parsed.
     */
    static List<String> execArgv(String exec, String url) {
        List<String> tokens = tokenizeExec(unescapeValue(exec));
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        boolean placed = false;
        for (String t : tokens) {
            if (t.equals("@@") || t.equals("@@u") || t.equals("@@f")) {
                continue;
            }
            if (!placed && (t.equals("%u") || t.equals("%U"))) {
                out.add(url);
                placed = true;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '%' && i + 1 < t.length()) {
                    char code = t.charAt(++i);
                    if (code == '%') {
                        sb.append('%');
                    } else if (!placed && (code == 'u' || code == 'U')) {
                        sb.append(url);
                        placed = true;
                    } // other codes (%i %c %k, deprecated ones) expand to nothing
                } else {
                    sb.append(c);
                }
            }
            if (!sb.isEmpty()) {
                out.add(sb.toString());
            }
        }
        if (out.isEmpty()) {
            return List.of();
        }
        if (!placed) {
            out.add(url);
        }
        return out;
    }

    /** The string-value escapes of a desktop file ({@code \s \n \t \r \\}). */
    private static String unescapeValue(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                switch (n) {
                    case 's' -> sb.append(' ');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    default -> sb.append('\\').append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Splits an Exec line into arguments: blanks separate, double quotes group, {@code \"\`\$\\} escape. */
    private static @Nullable List<String> tokenizeExec(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < s.length() && "\"`$\\".indexOf(s.charAt(i + 1)) >= 0) {
                    cur.append(s.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ' ' || c == '\t') {
                if (any || !cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (quoted) {
            return null; // an unterminated quote: not a command we can trust
        }
        if (any || !cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * True when the program {@code name} is installed: a path (a {@code TryExec=/opt/x/x.AppImage}) names an
     * executable file, and a bare name resolves on the augmented PATH. A path used to count as found unchecked, as
     * the PATH lookup hands a path back unchanged, so a removed AppImage's launcher stayed on offer (E203).
     */
    static boolean binOnPath(String name) {
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            try {
                Path p = Path.of(name);
                return Files.isRegularFile(p) && Files.isExecutable(p);
            } catch (java.nio.file.InvalidPathException notAPath) {
                return false;
            }
        }
        String resolved = ProcessRunner.resolveExecutable(List.of(name)).get(0);
        return resolved.indexOf('/') >= 0 || resolved.indexOf('\\') >= 0;
    }

    private static String resolveBin(String name) {
        return ProcessRunner.resolveExecutable(List.of(name)).get(0);
    }
}
