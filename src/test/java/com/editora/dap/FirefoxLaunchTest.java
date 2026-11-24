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
                List.of("-headless"),
                6123);
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
        assertEquals(6123, m.get("port"), "a free port, not the adapter's 6000");
        assertEquals(FirefoxLaunch.CONNECT_TIMEOUT_SECONDS, m.get("timeout"));
        assertEquals(false, m.get("reAttach"));
    }

    @Test
    void aFileUrlIsOpenedAsItsFileAndAnotherUrlThroughTheProject() {
        Path page = SITE.resolve("index.html");
        Map<String, Object> file =
                FirefoxLaunch.launch(page.toUri().toString(), SITE, null, null, null, List.of(), 6124);
        assertEquals(page.toString(), file.get("file"));
        assertFalse(file.containsKey("url"));
        assertFalse(file.containsKey("webRoot"), "the adapter maps file: URLs itself");
        assertFalse(file.containsKey("firefoxExecutable"), "the adapter finds Firefox");
        assertFalse(file.containsKey("profileDir"), "a temporary profile");
        Map<String, Object> dev =
                FirefoxLaunch.launch("http://localhost:5173", SITE, null, "", PROFILE, List.of(), 6125);
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
                Map.of("type", "firefox", "request", "launch", "url", "http://localhost:3000"), SITE, PROFILE, 6126);
        assertEquals(SITE.toString(), m.get("webRoot"));
        assertEquals(PROFILE.toString(), m.get("profileDir"));
        assertEquals(6126, m.get("port"));
        assertEquals(FirefoxLaunch.CONNECT_TIMEOUT_SECONDS, m.get("timeout"));
        Map<String, Object> own = FirefoxLaunch.configured(
                Map.of("type", "firefox", "request", "launch", "webRoot", "/x", "profile", "dev"), SITE, PROFILE, 6126);
        assertEquals("/x", own.get("webRoot"));
        assertFalse(own.containsKey("profileDir"), "a named profile of the user's");
        Map<String, Object> file = FirefoxLaunch.configured(
                Map.of("type", "firefox", "request", "launch", "file", "/work/site/index.html"), SITE, PROFILE, 6126);
        assertFalse(file.containsKey("webRoot"), "the adapter refuses a webRoot without a url");
        assertEquals(PROFILE.toString(), file.get("profileDir"));
        Map<String, Object> attach =
                FirefoxLaunch.configured(Map.of("type", "firefox", "request", "attach"), SITE, PROFILE, 6126);
        assertEquals(6000, attach.get("port"));
        assertEquals(SITE.toString(), attach.get("webRoot"));
        assertFalse(attach.containsKey("profileDir"));
    }
}
