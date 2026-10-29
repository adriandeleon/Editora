package com.editora.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.editora.web.Browsers.Browser;
import com.editora.web.Browsers.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Browsers}' pure detection + argv construction (OS + probes injected). */
class BrowsersTest {

    private static List<String> ids(List<Browser> browsers) {
        return browsers.stream().map(Browser::id).toList();
    }

    @Test
    void macDetectsSafariAlwaysAndBundlesThatExist() {
        Set<Path> present = Set.of(
                Path.of("/Applications", "Google Chrome.app"), Path.of("/Users/me", "Applications", "Firefox.app"));
        List<Browser> got = Browsers.detect(Os.MAC, present::contains, name -> false, "/Users/me");
        // Safari is always present on macOS; Chrome via /Applications; Firefox via ~/Applications; no Edge.
        assertEquals(List.of("safari", "chrome", "firefox", "default"), ids(got));
    }

    @Test
    void macIncludesOnlySafariAndDefaultWhenNoneInstalled() {
        List<Browser> got = Browsers.detect(Os.MAC, p -> false, name -> false, "/Users/me");
        assertEquals(List.of("safari", "default"), ids(got));
    }

    @Test
    void linuxDetectsBrowsersOnPath() {
        Predicate<String> onPath = Set.of("firefox", "google-chrome")::contains;
        List<Browser> got = Browsers.detect(Os.LINUX, p -> false, onPath, "/home/me");
        // No Safari on Linux; Chrome + Firefox detected; no Edge; default appended.
        assertEquals(List.of("chrome", "firefox", "default"), ids(got));
    }

    @Test
    void windowsDetectsBrowsersByExePath() {
        Set<Path> present = Set.of(Path.of("C:\\Program Files\\Mozilla Firefox\\firefox.exe"));
        List<Browser> got = Browsers.detect(Os.WINDOWS, present::contains, name -> false, "C:\\Users\\me");
        assertEquals(List.of("firefox", "default"), ids(got));
    }

    @Test
    void macArgvUsesOpenDashA() {
        List<String> argv = Browsers.launchArgv(
                Os.MAC, "chrome", "http://127.0.0.1:8080/i.html", UnaryOperator.identity(), p -> false);
        assertEquals(List.of("/usr/bin/open", "-a", "Google Chrome", "http://127.0.0.1:8080/i.html"), argv);
    }

    @Test
    void linuxArgvUsesTheFirstResolvedBinary() {
        // google-chrome not found, google-chrome-stable resolves to an absolute path → that wins.
        UnaryOperator<String> resolve =
                name -> name.equals("google-chrome-stable") ? "/usr/bin/google-chrome-stable" : name;
        List<String> argv = Browsers.launchArgv(Os.LINUX, "chrome", "http://x/y", resolve, p -> false);
        assertEquals(List.of("/usr/bin/google-chrome-stable", "http://x/y"), argv);
    }

    @Test
    void chromiumIsItsOwnBrowserNotGoogleChrome() {
        List<Browser> got = Browsers.detect(Os.LINUX, p -> false, Set.of("chromium")::contains, "/home/me");
        assertEquals(List.of("chromium", "default"), ids(got));
        assertEquals("Chromium", got.get(0).displayName());
        UnaryOperator<String> resolve = name -> name.equals("chromium") ? "/usr/bin/chromium" : name;
        assertTrue(
                Browsers.launchArgv(Os.LINUX, "chrome", "http://x", resolve, p -> false)
                        .isEmpty(),
                "Chromium is not launched as Chrome");
        assertEquals(
                List.of("/usr/bin/chromium", "http://x"),
                Browsers.launchArgv(Os.LINUX, "chromium", "http://x", resolve, p -> false));
    }

    // --- Linux desktop launchers (Flatpak, Snap, other browsers) --------------------------------

    private static final List<String> LIBREWOLF = List.of(
            "[Desktop Entry]",
            "Version=1.0",
            "Name=LibreWolf",
            "Name[de]=LibreWolf",
            "Exec=/usr/bin/flatpak run --branch=stable --arch=x86_64 --command=librewolf --file-forwarding"
                    + " io.gitlab.librewolf-community @@u %u @@",
            "Type=Application",
            "Categories=Network;WebBrowser;",
            "MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;",
            "",
            "[Desktop Action new-window]",
            "Name=Open a New Window",
            "Exec=/usr/bin/flatpak run io.gitlab.librewolf-community --new-window");

    @Test
    void parsesAnHttpHandlerLauncherAndSkipsOthers() {
        Browsers.DesktopEntry e = Browsers.parseDesktopEntry("io.gitlab.librewolf-community.desktop", LIBREWOLF);
        assertEquals("LibreWolf", e.name());
        assertTrue(e.exec().startsWith("/usr/bin/flatpak run"), "the [Desktop Entry] Exec, not an action's");
        assertNull(Browsers.parseDesktopEntry(
                "gimp.desktop", List.of("[Desktop Entry]", "Name=GIMP", "Exec=gimp %U", "MimeType=image/png;")));
        assertNull(Browsers.parseDesktopEntry(
                "x.desktop",
                List.of(
                        "[Desktop Entry]",
                        "Name=X",
                        "Exec=x %u",
                        "NoDisplay=true",
                        "MimeType=x-scheme-handler/http;")));
    }

    @Test
    void execLinesBecomeArgvWithTheUrl() {
        assertEquals(
                List.of(
                        "/usr/bin/flatpak",
                        "run",
                        "--branch=stable",
                        "--arch=x86_64",
                        "--command=librewolf",
                        "--file-forwarding",
                        "io.gitlab.librewolf-community",
                        "http://x/y"),
                Browsers.execArgv(LIBREWOLF.get(4).substring("Exec=".length()), "http://x/y"),
                "Flatpak's @@u … @@ markers dropped, %u replaced");
        assertEquals(
                List.of("/opt/zen/zen", "--name", "zen", "http://x"),
                Browsers.execArgv("/opt/zen/zen --name zen %U", "http://x"));
        assertEquals(
                List.of("/opt/My Browser/browser", "--class=b", "http://x"),
                Browsers.execArgv("\"/opt/My Browser/browser\" --class=b %i %c", "http://x"),
                "quoted program, dropped field codes, URL appended when there is no %u");
        assertEquals(List.of("b", "--pct=100%", "http://x"), Browsers.execArgv("b --pct=100%% %u", "http://x"));
        assertTrue(Browsers.execArgv("\"unterminated %u", "http://x").isEmpty());
    }

    @Test
    void linuxOffersFlatpakKnownBrowsersAndOtherHttpHandlers() {
        List<Browsers.DesktopEntry> desktop = List.of(
                new Browsers.DesktopEntry(
                        "org.mozilla.firefox.desktop",
                        "Firefox",
                        "/usr/bin/flatpak run --command=firefox org.mozilla.firefox @@u %u @@"),
                Browsers.parseDesktopEntry("io.gitlab.librewolf-community.desktop", LIBREWOLF),
                new Browsers.DesktopEntry("editora.desktop", "Editora", "editora %U"));
        List<Browser> got = Browsers.detect(Os.LINUX, p -> false, name -> false, "/home/me", name -> null, desktop);
        assertEquals(List.of("firefox", "desktop:io.gitlab.librewolf-community.desktop", "default"), ids(got));
        assertEquals(
                List.of("/usr/bin/flatpak", "run", "--command=firefox", "org.mozilla.firefox", "http://x"),
                Browsers.launchArgv(
                        Os.LINUX, "firefox", "http://x", UnaryOperator.identity(), p -> false, n -> null, desktop),
                "a Flatpak-only Firefox launches through its launcher");
        assertEquals(
                "/usr/bin/flatpak",
                Browsers.launchArgv(
                                Os.LINUX,
                                "desktop:io.gitlab.librewolf-community.desktop",
                                "http://x",
                                UnaryOperator.identity(),
                                p -> false,
                                n -> null,
                                desktop)
                        .get(0));
        assertTrue(
                Browsers.launchArgv(
                                Os.LINUX,
                                "desktop:gone.desktop",
                                "http://x",
                                UnaryOperator.identity(),
                                p -> false,
                                n -> null,
                                desktop)
                        .isEmpty(),
                "an uninstalled launcher: the caller falls back to the default browser");
    }

    @Test
    void applicationDirsFollowXdgAndIncludeFlatpakAndSnap() {
        List<Path> dirs = Browsers.applicationDirs("/home/me", name -> null);
        assertEquals(Path.of("/home/me/.local/share/applications"), dirs.get(0));
        assertTrue(dirs.contains(Path.of("/home/me/.local/share/flatpak/exports/share/applications")));
        assertTrue(dirs.contains(Path.of("/var/lib/flatpak/exports/share/applications")));
        assertTrue(dirs.contains(Path.of("/usr/share/applications")));
        assertTrue(dirs.contains(Path.of("/var/lib/snapd/desktop/applications")));
        List<Path> custom = Browsers.applicationDirs(
                "/home/me",
                name -> name.equals("XDG_DATA_DIRS") ? "/opt/share" : name.equals("XDG_DATA_HOME") ? "/data" : null);
        assertEquals(Path.of("/data/applications"), custom.get(0));
        assertTrue(custom.contains(Path.of("/opt/share/applications")));
        assertFalse(custom.contains(Path.of("/usr/share/applications")));
    }

    @Test
    void desktopEntriesAreReadFromTheFoldersUserFirst(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path user = java.nio.file.Files.createDirectories(tmp.resolve("data/applications"));
        Path system = java.nio.file.Files.createDirectories(tmp.resolve("sys/applications"));
        java.nio.file.Files.write(system.resolve("io.gitlab.librewolf-community.desktop"), LIBREWOLF);
        java.nio.file.Files.write(
                system.resolve("brave-browser.desktop"),
                List.of("[Desktop Entry]", "Name=Brave", "Exec=brave-browser %U", "MimeType=x-scheme-handler/http;"));
        java.nio.file.Files.write(
                user.resolve("brave-browser.desktop"),
                List.of("[Desktop Entry]", "Name=Brave", "Hidden=true")); // the user removed it
        List<Browsers.DesktopEntry> got = Browsers.desktopEntries("/nonexistent-home", name -> switch (name) {
            case "XDG_DATA_HOME" -> tmp.resolve("data").toString();
            case "XDG_DATA_DIRS" -> tmp.resolve("sys").toString();
            default -> null;
        });
        assertEquals(
                List.of("io.gitlab.librewolf-community.desktop"),
                got.stream().map(Browsers.DesktopEntry::fileId).toList());
    }

    // --- Windows per-user installs and more browsers -----------------------------------------------

    @Test
    void windowsFindsPerUserInstallsBraveAndVivaldi() {
        String lad = "C:\\Users\\me\\AppData\\Local";
        Set<Path> present = Set.of(
                Path.of(lad + "\\Google\\Chrome\\Application\\chrome.exe"),
                Path.of("D:\\Apps\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
                Path.of(lad + "\\Vivaldi\\Application\\vivaldi.exe"));
        java.util.function.Function<String, String> env = name -> switch (name) {
            case "LOCALAPPDATA" -> lad;
            case "ProgramFiles" -> "D:\\Apps\\";
            default -> null;
        };
        List<Browser> got =
                Browsers.detect(Os.WINDOWS, present::contains, name -> false, "C:\\Users\\me", env, List.of());
        assertEquals(List.of("chrome", "brave", "vivaldi", "default"), ids(got));
        assertEquals(
                List.of(lad + "\\Google\\Chrome\\Application\\chrome.exe", "http://x"),
                Browsers.launchArgv(
                        Os.WINDOWS, "chrome", "http://x", UnaryOperator.identity(), present::contains, env, List.of()));
    }

    @Test
    void macFindsMoreBrowsersAndKnownNamesFillIn() {
        Set<Path> present = Set.of(
                Path.of("/Applications", "Brave Browser.app"),
                Path.of("/Applications", "Arc.app"),
                Path.of("/Applications", "Chromium.app"));
        assertEquals(
                List.of("safari", "chromium", "brave", "arc", "default"),
                ids(Browsers.detect(Os.MAC, present::contains, name -> false, "/Users/me")));
        assertEquals(
                List.of("/usr/bin/open", "-a", "Brave Browser", "http://x"),
                Browsers.launchArgv(Os.MAC, "brave", "http://x", UnaryOperator.identity(), p -> false));
        assertEquals("Brave", Browsers.knownName("brave"));
        assertEquals(
                "io.gitlab.librewolf-community", Browsers.knownName("desktop:io.gitlab.librewolf-community.desktop"));
        assertEquals("mystery", Browsers.knownName("mystery"));
    }

    @Test
    void windowsArgvUsesTheFirstExistingExe() {
        Path edge = Path.of("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe");
        List<String> argv =
                Browsers.launchArgv(Os.WINDOWS, "edge", "http://x/y", UnaryOperator.identity(), edge::equals);
        assertEquals(List.of(edge.toString(), "http://x/y"), argv);
    }

    @Test
    void systemDefaultAndUnknownYieldNoArgv() {
        assertTrue(
                Browsers.launchArgv(Os.MAC, Browsers.SYSTEM_DEFAULT, "http://x", UnaryOperator.identity(), p -> false)
                        .isEmpty());
        assertTrue(Browsers.launchArgv(Os.MAC, "nope", "http://x", UnaryOperator.identity(), p -> false)
                .isEmpty());
    }

    @Test
    void linuxArgvEmptyWhenNoBinaryResolves() {
        List<String> argv = Browsers.launchArgv(Os.LINUX, "firefox", "http://x", UnaryOperator.identity(), p -> false);
        assertTrue(argv.isEmpty());
    }

    @Test
    void detectAlwaysAppendsSystemDefaultLast() {
        List<Browser> got = Browsers.detect(Os.LINUX, p -> false, name -> false, "/home/me");
        assertFalse(got.isEmpty());
        assertEquals(Browsers.SYSTEM_DEFAULT, got.get(got.size() - 1).id());
    }
}
