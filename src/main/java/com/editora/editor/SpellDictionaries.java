package com.editora.editor;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;

import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.store.ByteBuffersDirectory;

/**
 * Loads and caches the bundled Hunspell dictionaries (Apache Lucene's pure-Java engine). Each language
 * ships as {@code resources/com/editora/dictionaries/<id>/index.aff} + {@code index.dic}; building the
 * {@link Hunspell} (an FST) is the only heavy step, so it runs <b>once per language, off the FX thread,
 * cached</b> and shared across all buffers. Until a language is built, {@link #ifReady} returns empty and
 * the spell overlay simply draws nothing.
 */
public final class SpellDictionaries {

    private static final String BASE = "/com/editora/dictionaries/";
    /** Bundled language ids (folder names). Only permissively-licensed dictionaries are shipped
     *  (SCOWL for English; es/fr used under the Mozilla Public License). */
    private static final List<String> AVAILABLE = List.of("en_US", "en_GB", "es", "es_MX", "fr");

    private static final Map<String, Hunspell> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> BUILDING = ConcurrentHashMap.newKeySet();
    /** Callbacks waiting on an in-flight build, per language — several buffers commonly ask at once. */
    private static final Map<String, List<Runnable>> PENDING = new ConcurrentHashMap<>();

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spell-dictionary-builder");
        t.setDaemon(true);
        return t;
    });

    private SpellDictionaries() {}

    /** The bundled language ids, e.g. {@code en_US}, {@code en_GB}. */
    public static List<String> available() {
        return AVAILABLE;
    }

    public static boolean isAvailable(String langId) {
        return AVAILABLE.contains(langId);
    }

    /** The built engine for {@code langId} if it's already loaded, else empty (never builds on this thread). */
    public static Optional<Hunspell> ifReady(String langId) {
        return Optional.ofNullable(langId == null ? null : CACHE.get(langId));
    }

    /**
     * Ensures {@code langId} is built (off-thread, once), then runs {@code onReady} on the FX thread.
     * {@code onReady} <b>always</b> fires (unless the language is unavailable): immediately when the language
     * is already built, and after an in-flight build for every waiting caller. {@code onReady} may be null.
     */
    public static void ensureBuilt(String langId, Runnable onReady) {
        if (langId == null || !AVAILABLE.contains(langId)) {
            return;
        }
        if (CACHE.containsKey(langId)) {
            if (onReady != null) {
                Platform.runLater(onReady); // already built — the caller still needs its callback
            }
            return;
        }
        // Queue BEFORE claiming the build: several buffers ask for the same language at once (session
        // restore, a language switch), and dropping the losers' callbacks left those buffers with no
        // squiggles at all until the user scrolled or typed.
        if (onReady != null) {
            PENDING.computeIfAbsent(langId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(onReady);
        }
        if (!BUILDING.add(langId)) {
            return; // a build is in flight; it will run our queued callback
        }
        BUILDER.execute(() -> {
            try {
                Hunspell h = build(langId);
                CACHE.put(langId, h);
            } catch (Exception | LinkageError e) {
                // Leave uncached; a later ensureBuilt() can retry. Never let a bad dictionary crash the app.
            } finally {
                // Clear BUILDING first: a caller racing here then starts a fresh build that drains its own
                // queued callback, rather than having it stranded in PENDING forever.
                BUILDING.remove(langId);
                List<Runnable> waiting = PENDING.remove(langId);
                if (waiting != null) {
                    for (Runnable r : waiting) {
                        Platform.runLater(r);
                    }
                }
            }
        });
    }

    /** Builds (and caches) a language synchronously — for tests/headless use. Empty on failure. */
    static Optional<Hunspell> buildBlocking(String langId) {
        Hunspell cached = CACHE.get(langId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!AVAILABLE.contains(langId)) {
            return Optional.empty();
        }
        try {
            Hunspell h = build(langId);
            CACHE.put(langId, h);
            return Optional.of(h);
        } catch (IOException | ParseException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Hunspell build(String langId) throws IOException, ParseException {
        try (InputStream aff = SpellDictionaries.class.getResourceAsStream(BASE + langId + "/index.aff");
                InputStream dic = SpellDictionaries.class.getResourceAsStream(BASE + langId + "/index.dic")) {
            if (aff == null || dic == null) {
                throw new IOException("Missing dictionary resources for " + langId);
            }
            // ByteBuffersDirectory keeps Lucene's temp sort/FST files in memory (nothing touches disk).
            Dictionary dictionary = new Dictionary(new ByteBuffersDirectory(), "spell-" + langId, aff, dic);
            return new Hunspell(dictionary);
        }
    }
}
