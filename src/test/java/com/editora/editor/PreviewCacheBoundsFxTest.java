package com.editora.editor;

import java.util.List;
import java.util.Map;

import javafx.scene.image.WritableImage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The static render caches keep one render per preview surface, fit a byte budget, and give a document's
 * entries back when its buffer is disposed.
 */
@Tag("fx")
class PreviewCacheBoundsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static MermaidImages.Cached diagram(int width, int height) {
        return new MermaidImages.Cached(new PreviewImageLoader.Loaded(new WritableImage(width, height), width), null);
    }

    private static TypstImages.Cached document(int pages) {
        TypstImages.Loaded page = new TypstImages.Loaded(new WritableImage(8, 8), 8);
        return new TypstImages.Cached(java.util.Collections.nCopies(pages, page), null, 0);
    }

    @Test
    void newestPerSurfaceBookkeeping() {
        PreviewSurfaces.Newest newest = new PreviewSurfaces.Newest();
        assertNull(newest.record("s", "k1"), "first render of a surface replaces nothing");
        assertNull(newest.record("s", "k1"), "the same render again replaces nothing");
        assertEquals("k1", newest.record("s", "k2"));
        assertNull(newest.record(null, "k3"), "a render that belongs to no surface is not tracked");
        assertEquals(1, newest.size());
        assertEquals("k2", newest.release("s"));
        assertNull(newest.release("s"));
        assertEquals(0, newest.size());
    }

    @Test
    void aMermaidSurfaceKeepsOnlyItsNewestRenderAndReleasesItWithTheBuffer() {
        String surface = "mmd-surface-" + System.nanoTime();
        MermaidImages.store(surface + "#1", diagram(8, 8), surface);
        MermaidImages.store(surface + "#2", diagram(8, 8), surface);
        MermaidImages.store(surface + "#block", diagram(8, 8), null); // a Markdown fence: no surface
        assertFalse(MermaidImages.cached(surface + "#1"), "every settled edit used to leave its render behind");
        assertTrue(MermaidImages.cached(surface + "#2"));
        assertTrue(MermaidImages.cached(surface + "#block"));

        MermaidImages.release(surface);
        assertFalse(MermaidImages.cached(surface + "#2"));
        assertTrue(MermaidImages.cached(surface + "#block"), "another document's render is untouched");
    }

    @Test
    void theMermaidCacheIsTrimmedToItsByteBudget() {
        // 3072 x 3072 x 4 bytes = 36 MB each: four fit the 128 MB budget only as three.
        String tag = "mmd-big-" + System.nanoTime();
        for (int i = 0; i < 4; i++) {
            MermaidImages.store(tag + i, diagram(3072, 3072), null);
        }
        assertFalse(MermaidImages.cached(tag + 0), "the least recently used render went");
        assertTrue(MermaidImages.cached(tag + 3), "the newest is never evicted");
        for (int i = 1; i < 4; i++) {
            MermaidImages.store(tag + i, new MermaidImages.Cached(null, "released"), null); // free the test's images
        }
    }

    @Test
    void aTypstSurfaceKeepsOnlyItsNewestRenderAndReleasesItWithTheBuffer() {
        String surface = "typst-surface-" + System.nanoTime();
        String retain = surface + "-retain";
        TypstImages.store(surface + "#1", document(2), retain, surface);
        TypstImages.store(surface + "#2", document(3), retain, surface);
        assertFalse(TypstImages.cached(surface + "#1"));
        assertTrue(TypstImages.cached(surface + "#2"));
        assertTrue(TypstImages.retained(retain));

        TypstImages.release(retain, surface);
        assertFalse(TypstImages.cached(surface + "#2"));
        assertFalse(TypstImages.retained(retain), "a closed document's pages are not kept for a preview that is gone");
    }

    @Test
    void disposingABufferReleasesItsPreviewSurfaces() throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        javafx.application.Platform.runLater(() -> {
            try {
                EditorBuffer buffer = new EditorBuffer();
                String mermaid = PreviewSurfaces.mermaid(null, buffer);
                String retain = PreviewSurfaces.typstRetain(null, buffer);
                String typst = PreviewSurfaces.typst(buffer);
                MermaidImages.store(mermaid + "#1", diagram(8, 8), mermaid);
                TypstImages.store(typst + "#1", document(1), retain, typst);
                buffer.dispose();
                assertFalse(MermaidImages.cached(mermaid + "#1"));
                assertFalse(TypstImages.cached(typst + "#1"));
                assertFalse(TypstImages.retained(retain));
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    @Test
    void failedPreviewUrlsAreRememberedUpToABound() {
        Map<String, Long> failed = PreviewImageLoader.failureMemory(3);
        for (String url : List.of("a", "b", "c")) {
            failed.put(url, 1L);
        }
        failed.get("a"); // used again: no longer the eldest
        failed.put("d", 1L);
        assertEquals(java.util.Set.of("a", "c", "d"), failed.keySet());
    }
}
