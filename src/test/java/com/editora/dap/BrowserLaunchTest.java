package com.editora.dap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Editora asks vscode-js-debug for to debug a page in a browser, and the choices around it. */
class BrowserLaunchTest {

    private static final Path SITE =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("site");

    @Test
    void chromiumBrowsersAreDebuggedAndEdgeHasItsOwnType() {
        assertEquals(BrowserLaunch.CHROME, BrowserLaunch.typeFor("chrome"));
        assertEquals(BrowserLaunch.CHROME, BrowserLaunch.typeFor("brave"));
        assertEquals(BrowserLaunch.EDGE, BrowserLaunch.typeFor("edge"));
        assertNull(BrowserLaunch.typeFor("firefox"));
        assertNull(BrowserLaunch.typeFor("safari"));
        assertTrue(BrowserLaunch.isBrowserType("msedge"));
        assertTrue(BrowserLaunch.isBrowserType("pwa-chrome"));
        assertFalse(BrowserLaunch.isBrowserType("node"));
        assertEquals(BrowserLaunch.EDGE, BrowserLaunch.adapterType("msedge"));
        assertEquals(BrowserLaunch.CHROME, BrowserLaunch.adapterType("chrome"));
    }

    @Test
    void theChosenBrowserWinsWhenFoundThenThePreviewsThenTheFirstFound() {
        List<String> found = List.of("firefox", "brave", "edge", "chrome");
        assertEquals("brave", BrowserLaunch.pick(found, "brave", "chrome"));
        assertEquals("chrome", BrowserLaunch.pick(found, "vivaldi", "chrome"), "the chosen one is gone");
        assertEquals("chrome", BrowserLaunch.pick(found, "", "firefox"), "Firefox cannot be debugged: Chrome first");
        assertEquals("edge", BrowserLaunch.pick(List.of("edge", "firefox"), null, null));
        assertNull(BrowserLaunch.pick(List.of("firefox", "safari", "default"), null, null));
    }

    @Test
    void aServedPageMapsItsTokenToTheSiteAndUsesTheBrowsersProgramAndProfile() {
        BrowserLaunch.Served served = BrowserLaunch.Served.of("http://127.0.0.1:4711/abc123/pages/index.html", SITE)
                .orElseThrow();
        assertEquals("/abc123/", served.urlPath());
        Path profile = SITE.resolveSibling("profile");
        Map<String, Object> launch = BrowserLaunch.launch(
                BrowserLaunch.CHROME,
                "http://127.0.0.1:4711/abc123/pages/index.html",
                null,
                served,
                "/usr/bin/google-chrome",
                profile,
                List.of("--headless=new"));
        assertEquals("pwa-chrome", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals("http://127.0.0.1:4711/abc123/pages/index.html", launch.get("url"));
        assertEquals(SITE.toString(), launch.get("webRoot"), "the served folder, not the project");
        assertEquals(Map.of("/abc123/", SITE + java.io.File.separator), launch.get("pathMapping"));
        assertEquals("/usr/bin/google-chrome", launch.get("runtimeExecutable"));
        assertEquals(List.of("--headless=new"), launch.get("runtimeArgs"));
        assertEquals(profile.toString(), launch.get("userDataDir"));
        assertEquals(
                BrowserLaunch.LAUNCH_TIMEOUT_MILLIS,
                launch.get("timeout"),
                "a browser's first start takes longer than js-debug's own 10 s");
        assertEquals(30_000, BrowserLaunch.LAUNCH_TIMEOUT_MILLIS, "as long as a launch may take to be answered");
    }

    @Test
    void anyOtherUrlMapsThroughTheProjectAndLetsJsDebugFindTheBrowser() {
        Map<String, Object> launch =
                BrowserLaunch.launch(BrowserLaunch.EDGE, "http://localhost:5173", SITE, null, null, null, List.of());
        assertEquals("pwa-msedge", launch.get("type"));
        assertEquals(SITE.toString(), launch.get("webRoot"));
        assertEquals("stable", launch.get("runtimeExecutable"));
        assertFalse(launch.containsKey("pathMapping"));
        assertFalse(launch.containsKey("runtimeArgs"));
        assertFalse(launch.containsKey("userDataDir"), "js-debug keeps its own");
    }

    @Test
    void anAttachNamesTheDebuggingPort() {
        Map<String, Object> attach = BrowserLaunch.attach(BrowserLaunch.CHROME, "localhost", 9333, SITE);
        assertEquals("attach", attach.get("request"));
        assertEquals("localhost", attach.get("address"));
        assertEquals(9333, attach.get("port"));
        assertEquals(SITE.toString(), attach.get("webRoot"));
        assertFalse(BrowserLaunch.attach(BrowserLaunch.CHROME, "localhost", 9222, null)
                .containsKey("webRoot"));
    }

    @Test
    void aLaunchJsonConfigurationKeepsWhatItSaysAndGetsTheRest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "chrome");
        body.put("request", "launch");
        body.put("url", "http://localhost:8080");
        body.put("webRoot", "/w/src");
        Path profile = SITE.resolveSibling("profile");
        Map<String, Object> launch = BrowserLaunch.configured(body, SITE, profile);
        assertEquals("pwa-chrome", launch.get("type"));
        assertEquals("/w/src", launch.get("webRoot"));
        assertEquals(profile.toString(), launch.get("userDataDir"));
        assertEquals(BrowserLaunch.LAUNCH_TIMEOUT_MILLIS, launch.get("timeout"));

        body.put("userDataDir", false);
        body.put("timeout", 5000);
        body.remove("webRoot");
        launch = BrowserLaunch.configured(body, SITE, profile);
        assertEquals(false, launch.get("userDataDir"), "the configuration's own profile choice");
        assertEquals(5000, launch.get("timeout"), "and its own time for the browser to start");
        assertEquals(SITE.toString(), launch.get("webRoot"));

        Map<String, Object> attach = BrowserLaunch.configured(
                new LinkedHashMap<>(Map.of("type", "msedge", "request", "attach")), SITE, profile);
        assertEquals("pwa-msedge", attach.get("type"));
        assertEquals(BrowserLaunch.DEFAULT_PORT, attach.get("port"));
        assertFalse(attach.containsKey("userDataDir"), "an attach uses the browser's own profile");
        assertFalse(attach.containsKey("timeout"), "and starts no browser to wait for");
    }

    @Test
    void whatIsTypedBecomesAUrl() {
        assertEquals(Optional.of("http://localhost:5173"), BrowserLaunch.url(" localhost:5173 "));
        assertEquals(Optional.of("http://example.com/app"), BrowserLaunch.url("example.com/app"));
        assertEquals(Optional.of("https://example.com"), BrowserLaunch.url("https://example.com"));
        assertEquals(Optional.of("file:///tmp/a.html"), BrowserLaunch.url("file:///tmp/a.html"));
        assertEquals(Optional.empty(), BrowserLaunch.url("ftp://files"));
        assertEquals(Optional.empty(), BrowserLaunch.url("two words"));
        assertEquals(Optional.empty(), BrowserLaunch.url(""));
        assertEquals(Optional.empty(), BrowserLaunch.url("http://"));
        assertEquals(Optional.empty(), BrowserLaunch.Served.of("http://127.0.0.1:4711/", SITE));
    }

    @Test
    void aSessionIsNamedAfterItsPage() {
        assertEquals("localhost:5173", BrowserLaunch.label("http://localhost:5173/", null));
        assertEquals("example.com/app", BrowserLaunch.label("https://example.com/app", null));
        assertEquals("a.html", BrowserLaunch.label("file:///tmp/a.html", null));
        assertEquals(
                "index.html", BrowserLaunch.label("http://127.0.0.1:4711/abc/index.html", SITE.resolve("index.html")));
    }
}
