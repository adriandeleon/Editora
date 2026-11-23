package com.editora.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import com.editora.AppInfo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled user guide: which links it takes over, and the loopback server that serves it. */
class AppDocsTest {

    private final Function<String, @Nullable InputStream> realFiles = AppDocs.files;

    @AfterEach
    void restore() {
        AppDocs.stop();
        AppDocs.files = realFiles;
        AppDocs.resetManifest();
    }

    @Test
    void onlyThisVersionsDocsAreTakenOver() {
        String base = "https://editora-project.dev/docs/v-1.2.0";
        assertTrue(AppDocs.isThisVersionsDocs(base, base));
        assertTrue(AppDocs.isThisVersionsDocs(base + "/", base));
        assertTrue(AppDocs.isThisVersionsDocs(base + "/commands/file.save", base));
        assertTrue(AppDocs.isThisVersionsDocs(base + "#top", base));
        assertFalse(AppDocs.isThisVersionsDocs(base + "1/editing", base), "v-1.2.01 is another version");
        assertFalse(AppDocs.isThisVersionsDocs("https://editora-project.dev/docs/v-1.1.0/", base));
        assertFalse(AppDocs.isThisVersionsDocs("https://editora-project.dev/news", base));
        assertFalse(AppDocs.isThisVersionsDocs(null, base));
    }

    @Test
    void aWebsiteUrlMapsToTheSamePathOnLoopback() {
        assertEquals(
                "http://127.0.0.1:4000/docs/v-1.2.0/editing#find",
                AppDocs.toLocal(AppInfo.HOMEPAGE + "/docs/v-1.2.0/editing#find", 4000));
        assertEquals("https://editora-project.dev/news?x=1", AppDocs.webUrl(URI.create("http://127.0.0.1:1/news?x=1")));
    }

    @Test
    void pathsThatClimbOutAreRefused() {
        assertNull(AppDocs.resolve("/docs/../../etc/passwd"));
        assertNull(AppDocs.resolve("/docs/./x"));
        assertNull(AppDocs.resolve("/docs\\x"));
        assertNull(AppDocs.resolve("relative"));
        assertEquals("/docs/v-1/editing", AppDocs.resolve("/docs/v-1/editing"));
    }

    @Test
    void aFolderPageIsTriedBeforeAFileOfTheSameName() {
        assertArrayEquals(new String[] {"/a/index.html"}, AppDocs.candidates("/a/"));
        assertArrayEquals(
                new String[] {"/commands/file.save/index.html", "/commands/file.save"},
                AppDocs.candidates("/commands/file.save"));
    }

    @Test
    void theManifestNamesTheVersion() {
        assertEquals(
                "0.29.0", AppDocs.manifestVersion("{\n  \"version\": \"0.29.0\",\n  \"docs\": \"/docs/v-0.29.0/\"\n}"));
        assertNull(AppDocs.manifestVersion("{}"));
    }

    @Test
    void textIsServedAsUtf8() {
        assertEquals("text/html; charset=utf-8", AppDocs.contentType("index.html"));
        assertEquals("image/png", AppDocs.contentType("shot.png"));
    }

    @Test
    void withoutACopyLinksStayOnTheWebsite(@TempDir Path site) {
        AppDocs.files = path -> open(site, path);
        AppDocs.resetManifest();
        String url = AppInfo.docsUrl() + "/editing";
        assertFalse(AppDocs.bundled());
        assertEquals(url, AppDocs.localize(url));
        assertFalse(AppDocs.running(), "nothing to serve, so no server");
    }

    @Test
    void aCopyForAnotherVersionIsNotUsed(@TempDir Path site) throws IOException {
        Files.writeString(site.resolve("app-docs.json"), "{\"version\": \"0.0.1-other\"}");
        AppDocs.files = path -> open(site, path);
        AppDocs.resetManifest();
        assertFalse(AppDocs.bundled());
    }

    @Test
    void theCopyIsServedAndEverythingElseGoesToTheWebsite(@TempDir Path site) throws Exception {
        String version = AppInfo.releaseVersion();
        Files.writeString(site.resolve("app-docs.json"), "{\"version\": \"" + version + "\"}");
        Path page = site.resolve("docs/v-" + version + "/editing/index.html");
        Files.createDirectories(page.getParent());
        Files.writeString(page, "<!doctype html><title>Editing</title>");
        Path command = site.resolve("docs/v-" + version + "/commands/edit.escape.html/index.html");
        Files.createDirectories(command.getParent());
        Files.writeString(command, "<!doctype html><title>Escape HTML</title>");
        AppDocs.files = path -> open(site, path);
        AppDocs.resetManifest();

        String local = AppDocs.localize(AppInfo.docsUrl() + "/editing");
        assertTrue(local.startsWith("http://127.0.0.1:"), local);
        assertTrue(AppDocs.running());

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> ok =
                http.send(HttpRequest.newBuilder(URI.create(local)).build(), bodyUtf8());
        assertEquals(200, ok.statusCode());
        assertEquals("<!doctype html><title>Editing</title>", ok.body());
        assertEquals(
                "text/html; charset=utf-8",
                ok.headers().firstValue("Content-Type").orElse(""));

        String origin = local.substring(0, local.indexOf("/docs/"));
        HttpResponse<String> dotted = http.send(
                HttpRequest.newBuilder(URI.create(origin + "/docs/v-" + version + "/commands/edit.escape.html"))
                        .build(),
                bodyUtf8());
        assertEquals(200, dotted.statusCode(), "a command id that ends like a file name is still a page");

        HttpResponse<String> missing = http.send(
                HttpRequest.newBuilder(URI.create(origin + "/news?page=2")).build(), bodyUtf8());
        assertEquals(302, missing.statusCode());
        assertEquals(
                "https://editora-project.dev/news?page=2",
                missing.headers().firstValue("Location").orElse(""));

        HttpResponse<String> root =
                http.send(HttpRequest.newBuilder(URI.create(origin + "/")).build(), bodyUtf8());
        assertEquals(302, root.statusCode());
        assertEquals(
                "/docs/v-" + version + "/",
                root.headers().firstValue("Location").orElse(""));
    }

    private static HttpResponse.BodyHandler<String> bodyUtf8() {
        return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
    }

    private static @Nullable InputStream open(Path site, String path) {
        Path file = site.resolve(path.substring(1));
        try {
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
