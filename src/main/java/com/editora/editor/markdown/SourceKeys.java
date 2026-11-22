package com.editora.editor.markdown;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The cache keys of the previews' rendered images, remembered by the source they were worked out for, so the FX
 * thread never hashes a source (PERF-01, Pauta 6.2.0). A key is a SHA-256 of the whole source, worked out on the
 * render executor; the FX thread asks for the key of a source it has seen before and gets it from here, comparing
 * the source to the one remembered (a plain comparison, no hash). A source not seen yet has no key on the FX
 * thread: its node asks the executor, which works the key out, answers from the image cache when it holds that key,
 * and renders otherwise.
 *
 * <p>Found by a probe of the source's length and its first and last characters (no hash of the whole), then
 * confirmed by {@link String#equals}. Bounded: the latest {@value #MAX} sources.
 */
final class SourceKeys {

    private static final int MAX = 512;
    private static final int EDGE = 48;

    /** What finds a remembered source: its tag (kind, theme, folder), its length and its two ends. */
    private record Probe(String tag, int length, String head, String tail) {
        static Probe of(String tag, String source) {
            int n = source.length();
            return new Probe(tag, n, source.substring(0, Math.min(EDGE, n)), source.substring(Math.max(0, n - EDGE)));
        }
    }

    private record Known(String source, String key) {}

    private final Map<Probe, Known> known = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Probe, Known> eldest) {
            return size() > MAX;
        }
    };

    /** The key worked out earlier for exactly {@code source} under {@code tag}, or null when there is none. */
    synchronized @Nullable String knownKey(String tag, String source) {
        Known k = known.get(Probe.of(tag, source));
        return k != null && k.source().equals(source) ? k.key() : null;
    }

    /** Remembers {@code key} for {@code source} under {@code tag}: called where the key was worked out, off FX. */
    synchronized void remember(String tag, String source, String key) {
        known.put(Probe.of(tag, source), new Known(source, key));
    }
}
