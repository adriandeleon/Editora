package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FirefoxLaunchTest {

    private static final Path SITE = Path.of("/work/site").toAbsolutePath();
    private static final Path PROFILE = Path.of("/cfg/browser-debug/firefox").toAbsolutePath();

    @Test
    void aServedPageIsMappedByItsWholeUrlAndRunsInEditorasKeptProfile() {
        BrowserLaunch.Served served = BrowserLaunch.Served.of("http://127.0.0.1:4321/tok/index.html", SITE)
                .orElseThrow();
        Map<String, Object> m = FirefoxLaunch.launch(
                "http://127.0.0.1:4321/tok/index.html",
                null,
                served,
                "/usr/bin/firefox",
                PROFILE,
                List.of("-headless"));
        assertEquals("firefox", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals("http://127.0.0.1:4321/tok/index.html", m.get("url"));
        assertEquals(SITE.toString(), m.get("webRoot"));
        assertEquals(
                List.of(Map.of(
                        "url",
                        "http://127.0.0.1:4321/tok/",
                        "path",
                        SITE + (SITE.toString().contains("\\") ? "\\" : "/"))),
                m.get("pathMappings"));
        assertEquals("/usr/bin/firefox", m.get("firefoxExecutable"));
        assertEquals(PROFILE.toString(), m.get("profileDir"));
        assertEquals(true, m.get("keepProfileChanges"));
        assertEquals(List.of("-headless"), m.get("firefoxArgs"));
    }

    @Test
    void aFileUrlIsOpenedAsItsFileAndAnotherUrlThroughTheProject() {
        Path page = SITE.resolve("index.html");
        Map<String, Object> file = FirefoxLaunch.launch(page.toUri().toString(), SITE, null, null, null, List.of());
        assertEquals(page.toString(), file.get("file"));
        assertFalse(file.containsKey("url"));
        assertFalse(file.containsKey("webRoot"), "the adapter maps file: URLs itself");
        assertFalse(file.containsKey("firefoxExecutable"), "the adapter finds Firefox");
        assertFalse(file.containsKey("profileDir"), "a temporary profile");
        Map<String, Object> dev = FirefoxLaunch.launch("http://localhost:5173", SITE, null, "", PROFILE, List.of());
        assertEquals("http://localhost:5173", dev.get("url"));
        assertEquals(SITE.toString(), dev.get("webRoot"));
        assertFalse(dev.containsKey("pathMappings"));
    }

    @Test
    void anAttachJoinsFirefoxsDebuggerServer() {
        assertEquals(
                Map.of(
                        "type", "firefox",
                        "name", "Editora (Firefox)",
                        "request", "attach",
                        "host", "localhost",
                        "port", 6000,
                        "webRoot", SITE.toString()),
                FirefoxLaunch.attach("localhost", FirefoxLaunch.DEFAULT_PORT, SITE));
    }

    @Test
    void aLaunchJsonConfigurationGetsTheWorkspaceAndProfileUnlessItSaysOtherwise() {
        Map<String, Object> m = FirefoxLaunch.configured(
                Map.of("type", "firefox", "request", "launch", "url", "http://localhost:3000"), SITE, PROFILE);
        assertEquals(SITE.toString(), m.get("webRoot"));
        assertEquals(PROFILE.toString(), m.get("profileDir"));
        Map<String, Object> own = FirefoxLaunch.configured(
                Map.of("type", "firefox", "request", "launch", "webRoot", "/x", "profile", "dev"), SITE, PROFILE);
        assertEquals("/x", own.get("webRoot"));
        assertFalse(own.containsKey("profileDir"), "a named profile of the user's");
        Map<String, Object> file = FirefoxLaunch.configured(
                Map.of("type", "firefox", "request", "launch", "file", "/work/site/index.html"), SITE, PROFILE);
        assertFalse(file.containsKey("webRoot"), "the adapter refuses a webRoot without a url");
        assertEquals(PROFILE.toString(), file.get("profileDir"));
        Map<String, Object> attach =
                FirefoxLaunch.configured(Map.of("type", "firefox", "request", "attach"), SITE, PROFILE);
        assertEquals(6000, attach.get("port"));
        assertEquals(SITE.toString(), attach.get("webRoot"));
        assertFalse(attach.containsKey("profileDir"));
    }
}
