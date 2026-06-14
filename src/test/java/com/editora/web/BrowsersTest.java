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
        // google-chrome not found, chromium resolves to an absolute path → that wins.
        UnaryOperator<String> resolve = name -> name.equals("chromium") ? "/usr/bin/chromium" : name;
        List<String> argv = Browsers.launchArgv(Os.LINUX, "chrome", "http://x/y", resolve, p -> false);
        assertEquals(List.of("/usr/bin/chromium", "http://x/y"), argv);
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
