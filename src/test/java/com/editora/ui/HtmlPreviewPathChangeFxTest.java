package com.editora.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.editora.crypto.GpgKey;
import com.editora.crypto.GpgTestHome;
import com.editora.editor.EditorBuffer;
import com.editora.editor.crypto.EncryptionState;
import com.editora.web.HtmlPreviewService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E201, in a real window over real HTTP: once a buffer's file has another name — Save As, a rename (tab menu or
 * Project tree, a file or a folder), Encrypt File with the real gpg — the old address no longer serves the editor's
 * text, unsaved edits included: it answers from disk, or 404 once the old file is gone. The new name is served as
 * any edited file is, unless it is encrypted. Only the system opener is stood in for (no browser starts).
 */
@Tag("fx")
class HtmlPreviewPathChangeFxTest {

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Got(int status, String body) {}

    private static Got get(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return new Got(r.statusCode(), r.body());
    }

    /** Polls {@code url} until its answer passes {@code ok} (the settled-edit pulse is asynchronous). */
    private static Got await(String url, Predicate<Got> ok, String what) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        Got got = get(url);
        while (!ok.test(got)) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what + "; last: " + got);
            Thread.sleep(50);
            got = get(url);
        }
        return got;
    }

    private static void awaitFx(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!FxTestSupport.callOnFx(condition)) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    /** A window on a project folder, previewing with a recording system opener; returns the opened URLs. */
    private static List<String> previewing(FxWindowFixture fx) throws Exception {
        List<String> opened = new CopyOnWriteArrayList<>();
        HtmlPreviewCoordinator hp = fx.controller.htmlPreview;
        java.lang.reflect.Field f = HtmlPreviewCoordinator.class.getDeclaredField("service");
        f.setAccessible(true);
        FxTestSupport.runOnFx(() -> {
            try {
                ((HtmlPreviewService) f.get(hp)).shutdown();
                HtmlPreviewService stub = new HtmlPreviewService(opened::add); // System Default: never a launch
                stub.setServerGoneMessage("gone");
                f.set(hp, stub);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            fx.shared.getSettings().setHtmlPreviewBrowser(com.editora.web.Browsers.SYSTEM_DEFAULT);
        });
        return opened;
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        awaitFx("a loaded tab for " + file.getFileName(), () -> {
            EditorBuffer b = fx.controller.activeBuffer();
            return b != null && file.equals(b.getPath()) && !b.isLoading();
        });
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    /** Opens {@code entry} and previews it; returns the site's base URL ({@code http://…/<token>}). */
    private static String preview(FxWindowFixture fx, List<String> opened, Path entry) throws Exception {
        open(fx, entry);
        FxTestSupport.runOnFx(fx.controller.htmlPreview::open);
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (opened.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the preview opened");
            Thread.sleep(20);
        }
        String url = opened.get(0);
        return url.substring(0, url.lastIndexOf('/'));
    }

    private static void type(EditorBuffer buffer, String text) throws Exception {
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, text));
    }

    private static void awaitSaved(FxWindowFixture fx, EditorBuffer buffer) throws Exception {
        awaitFx("saved", () -> !fx.controller.fileWorkflows.hasPendingSave(buffer) && !buffer.isDirty());
    }

    @Test
    void saveAsAndRenamesLeaveNothingOfTheEditorAtTheOldName() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path cfg = Files.createTempDirectory("editora-e201");
            FxWindowFixture fx = async.own(
                    FxWindowFixture.create(cfg, shared -> shared.getSettings().setHtmlPreviewSupport(true)));
            Path root = fx.useProject();
            Path index = Files.writeString(root.resolve("index.html"), "<body>home</body>\n");
            Path page = Files.writeString(root.resolve("page.html"), "<body>page on disk</body>\n");
            Path about = Files.writeString(root.resolve("about.html"), "<body>about on disk</body>\n");
            Path sub = Files.createDirectories(root.resolve("sub"));
            Path part = Files.writeString(sub.resolve("part.html"), "<body>part on disk</body>\n");
            List<String> opened = previewing(fx);
            String base = preview(fx, opened, index);

            // Save As: the old name answers from disk, then 404; the new name has the text.
            EditorBuffer pageBuffer = open(fx, page);
            type(pageBuffer, "<p>edited before Save As</p>");
            await(base + "/page.html", g -> g.body().contains("edited before Save As"), "the edit served");
            Path page2 = root.resolve("page2.html");
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.applySaveAsTarget(pageBuffer, page2));
            awaitSaved(fx, pageBuffer);
            Got old = get(base + "/page.html");
            assertEquals(200, old.status());
            assertTrue(old.body().contains("page on disk"), old.body());
            assertFalse(old.body().contains("edited before Save As"), "the old name serves the disk, not the editor");
            Files.delete(page);
            assertEquals(404, get(base + "/page.html").status(), "and nothing once the old file is gone");
            assertTrue(get(base + "/page2.html").body().contains("edited before Save As"));

            // The tab menu's rename, unsaved: the edit moves to the new name.
            EditorBuffer aboutBuffer = open(fx, about);
            type(aboutBuffer, "<p>about edited</p>");
            await(base + "/about.html", g -> g.body().contains("about edited"), "the edit served");
            Path aboutOld = root.resolve("about-old.html");
            FxTestSupport.runOnFx(() -> fx.controller.renameFileTo(aboutBuffer, about, aboutOld));
            assertEquals(404, get(base + "/about.html").status(), "renamed away: no snapshot answers at the old name");
            assertTrue(get(base + "/about-old.html").body().contains("about edited"), "the unsaved edit, at its name");
            assertTrue(FxTestSupport.callOnFx(aboutBuffer::hasHtmlPreviewControl), "the globe stays");

            // A folder moved (the Project tree's path): every buffer under it follows.
            EditorBuffer partBuffer = open(fx, part);
            type(partBuffer, "<p>part edited</p>");
            await(base + "/sub/part.html", g -> g.body().contains("part edited"), "the edit served");
            Path moved = root.resolve("moved");
            Files.move(sub, moved);
            FxTestSupport.runOnFx(() -> fx.controller.remapProjectFileLocal(sub, moved, false));
            assertEquals(404, get(base + "/sub/part.html").status(), "the moved folder's old path serves nothing");
            assertTrue(get(base + "/moved/part.html").body().contains("part edited"));
        }
    }

    @Test
    void encryptFileServesNoPlaintextAtTheOldName() throws Exception {
        assumeTrue(GpgTestHome.available(), "gpg is not installed");
        try (GpgTestHome home = GpgTestHome.create();
                AsyncTestScope async = new AsyncTestScope()) {
            home.newKey("Preview Test <preview@example.com>");
            EncryptionCoordinator.gpgEnvironment = home.env();
            Path cfg = Files.createTempDirectory("editora-e201");
            FxWindowFixture fx = async.own(
                    FxWindowFixture.create(cfg, shared -> shared.getSettings().setHtmlPreviewSupport(true)));
            Path root = fx.useProject();
            Path index = Files.writeString(root.resolve("index.html"), "<body>home on disk</body>\n");
            Path notes = Files.writeString(root.resolve("notes.txt"), "plain notes on disk\n");
            List<String> opened = previewing(fx);
            String base = preview(fx, opened, index);
            EncryptionCoordinator encryption = fx.controller.fileWorkflows.encryption();
            encryption.picker = (request, choose) -> {
                List<GpgKey> me = request.keys().stream()
                        .filter(k -> request.preselected().contains(k.fingerprint()))
                        .toList();
                choose.accept(new RecipientPicker.Choice(EncryptionState.Mode.PUBLIC_KEY, me, null, false, false));
            };

            // A plain file of the site, edited, then encrypted: its unsaved text never stays served.
            EditorBuffer notesBuffer = open(fx, notes);
            type(notesBuffer, "NEVER-SAVED PLAINTEXT ");
            await(base + "/notes.txt", g -> g.body().contains("NEVER-SAVED"), "the edit served");
            FxTestSupport.runOnFx(encryption::encryptFile);
            Path notesGpg = root.resolve("notes.txt.gpg");
            awaitFx(
                    "encrypted",
                    () -> Files.exists(notesGpg)
                            && notesGpg.equals(notesBuffer.getPath())
                            && !fx.controller.fileWorkflows.hasPendingSave(notesBuffer));
            Got afterEncrypt = get(base + "/notes.txt");
            assertEquals("plain notes on disk\n", afterEncrypt.body(), "the original's bytes, not the editor's");
            Files.delete(notes); // what the banner's Move to Trash leaves
            assertEquals(404, get(base + "/notes.txt").status());
            type(notesBuffer, "LATER SECRET ");
            Thread.sleep(900); // past the settled-edit pulse: nothing may come of it
            assertEquals(404, get(base + "/notes.txt").status());
            assertEquals(404, get(base + "/notes.txt.gpg").status());

            // The previewed page itself: its open tab reloads into the disk, never into the unsaved secret.
            EditorBuffer indexBuffer = open(fx, index);
            type(indexBuffer, "<p>ENTRY UNSAVED SECRET</p>");
            await(base + "/index.html", g -> g.body().contains("ENTRY UNSAVED SECRET"), "the edit served");
            FxTestSupport.runOnFx(encryption::encryptFile);
            Path indexGpg = root.resolve("index.html.gpg");
            awaitFx(
                    "encrypted",
                    () -> Files.exists(indexGpg)
                            && indexGpg.equals(indexBuffer.getPath())
                            && !fx.controller.fileWorkflows.hasPendingSave(indexBuffer));
            for (String path : List.of("/index.html", "/")) {
                Got got = get(base + path);
                assertTrue(got.body().contains("home on disk"), got.body());
                assertFalse(got.body().contains("SECRET"), "no plaintext at " + path);
            }
            assertFalse(FxTestSupport.callOnFx(indexBuffer::hasHtmlPreviewControl), "an encrypted page has no globe");
            Files.delete(index);
            assertEquals(404, get(base + "/index.html").status());
        } finally {
            EncryptionCoordinator.gpgEnvironment = java.util.Map.of();
        }
    }
}
