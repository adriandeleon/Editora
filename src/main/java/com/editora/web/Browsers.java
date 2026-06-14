package com.editora.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.editora.process.ProcessRunner;

/**
 * Detects installed desktop browsers and builds the per-OS command that opens a URL in a chosen one.
 *
 * <p>Detection + argv construction are <em>pure</em> (the OS and the filesystem/PATH probes are injected),
 * so they're unit-tested; only {@link #detect()} and {@link #launchArgv(Browser, String)} touch the real OS.
 * The {@link #SYSTEM_DEFAULT} entry is special — the caller opens it via {@code HostServices.showDocument}
 * rather than launching a subprocess.
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

    /** A known browser, with the per-OS hooks needed to detect it and build its launch command. */
    private record Spec(
            String id,
            String name,
            String macApp, // "open -a <macApp>"; also the bundle base name "<macApp>.app"
            boolean macAlways, // Safari: present on every Mac, no bundle probe needed
            List<String> linuxBins, // candidate binaries on PATH, first match wins
            List<String> winPaths) {} // candidate absolute exe paths, first existing wins

    /** Known browsers in display order. Safari is macOS-only (and always present there). */
    private static final List<Spec> SPECS = List.of(
            new Spec("safari", "Safari", "Safari", true, List.of(), List.of()),
            new Spec(
                    "chrome",
                    "Google Chrome",
                    "Google Chrome",
                    false,
                    List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser"),
                    List.of(
                            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe")),
            new Spec(
                    "firefox",
                    "Firefox",
                    "Firefox",
                    false,
                    List.of("firefox"),
                    List.of(
                            "C:\\Program Files\\Mozilla Firefox\\firefox.exe",
                            "C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe")),
            new Spec(
                    "edge",
                    "Microsoft Edge",
                    "Microsoft Edge",
                    false,
                    List.of("microsoft-edge", "microsoft-edge-stable"),
                    List.of(
                            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe")));

    private Browsers() {}

    public static Os currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Os.MAC;
        }
        if (os.contains("win")) {
            return Os.WINDOWS;
        }
        return Os.LINUX;
    }

    /**
     * Detects installed browsers on the real OS (macOS app bundles, Linux PATH binaries, Windows Program
     * Files), always appending the {@link #SYSTEM_DEFAULT} entry last so the list is never empty.
     */
    public static List<Browser> detect() {
        return detect(currentOs(), Files::exists, Browsers::binOnPath, System.getProperty("user.home", ""));
    }

    /** Pure detection. {@code fileExists} probes bundle/exe paths; {@code binOnPath} tests a Linux binary name. */
    static List<Browser> detect(Os os, Predicate<Path> fileExists, Predicate<String> binOnPath, String home) {
        List<Browser> out = new ArrayList<>();
        for (Spec s : SPECS) {
            if (present(s, os, fileExists, binOnPath, home)) {
                out.add(new Browser(s.id(), s.name()));
            }
        }
        out.add(new Browser(SYSTEM_DEFAULT, "System Default")); // the UI localizes this label by id
        return out;
    }

    private static boolean present(
            Spec s, Os os, Predicate<Path> fileExists, Predicate<String> binOnPath, String home) {
        return switch (os) {
            case MAC -> s.macAlways() || macBundleExists(s.macApp(), fileExists, home);
            case LINUX -> s.linuxBins().stream().anyMatch(binOnPath);
            case WINDOWS -> s.winPaths().stream().anyMatch(p -> fileExists.test(Path.of(p)));
        };
    }

    private static boolean macBundleExists(String app, Predicate<Path> fileExists, String home) {
        return fileExists.test(Path.of("/Applications", app + ".app"))
                || (!home.isEmpty() && fileExists.test(Path.of(home, "Applications", app + ".app")));
    }

    /**
     * The argv that opens {@code url} in {@code browser} on the real OS. Empty for {@link #SYSTEM_DEFAULT}
     * (the caller uses HostServices) or an undetectable browser (the caller falls back to the default).
     */
    public static List<String> launchArgv(Browser browser, String url) {
        return launchArgv(currentOs(), browser.id(), url, Browsers::resolveBin, Files::exists);
    }

    /**
     * Pure argv builder. {@code resolveBin} maps a Linux binary name to an absolute path (or returns it
     * unchanged when not found); {@code fileExists} probes Windows exe paths.
     */
    static List<String> launchArgv(
            Os os, String id, String url, UnaryOperator<String> resolveBin, Predicate<Path> fileExists) {
        if (SYSTEM_DEFAULT.equals(id)) {
            return List.of();
        }
        Spec s = specFor(id);
        if (s == null) {
            return List.of();
        }
        return switch (os) {
            case MAC -> List.of("/usr/bin/open", "-a", s.macApp(), url);
            case LINUX -> {
                for (String bin : s.linuxBins()) {
                    String resolved = resolveBin.apply(bin);
                    if (resolved.indexOf('/') >= 0) { // resolved to an absolute path ⇒ present
                        yield List.of(resolved, url);
                    }
                }
                yield List.of();
            }
            case WINDOWS -> {
                for (String p : s.winPaths()) {
                    if (fileExists.test(Path.of(p))) {
                        yield List.of(p, url);
                    }
                }
                yield List.of();
            }
        };
    }

    private static Spec specFor(String id) {
        for (Spec s : SPECS) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** True when {@code name} resolves to an absolute path on the augmented PATH (i.e. the binary exists). */
    private static boolean binOnPath(String name) {
        String resolved = ProcessRunner.resolveExecutable(List.of(name)).get(0);
        return resolved.indexOf('/') >= 0 || resolved.indexOf('\\') >= 0;
    }

    private static String resolveBin(String name) {
        return ProcessRunner.resolveExecutable(List.of(name)).get(0);
    }
}
