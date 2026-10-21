package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.editor.lsp.LspDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * The lemminx-maven quirks the client smooths over, all pure (Maven review C18–C20):
 *
 * <ul>
 *   <li>{@link #readyGate}: the server answers {@code initialize} at once but is unusable until its own
 *       "Maven initialization done" progress ends — the ready narration is held until then.</li>
 *   <li>{@link #adjustDiagnostics}: a Maven-model diagnostic carries no {@code source} (the XML ones say
 *       {@code xml}) — it defaults to the server's display name; and the transient "the resource … is
 *       downloading in the cache path" schema message is information, not an error.</li>
 *   <li>{@link #filterCompletion}: {@code ${env.*}} property items flood a GAV completion — they are dropped
 *       unless the typed prefix starts with {@code $}.</li>
 * </ul>
 */
public final class MavenPomServer {

    /** The progress-end message lemminx-maven sends once its Maven components are loaded. */
    static final String INITIALIZATION_DONE = "Maven initialization done";

    private MavenPomServer() {}

    // --- ready gate ---------------------------------------------------------------------------------

    /**
     * Holds the Maven server's "ServiceReady" (its {@code initialize} answer) until the progress whose end
     * says {@link #INITIALIZATION_DONE}; a per-server-id instance owned by the coordinator.
     */
    public static final class ReadyGate {
        private boolean held;

        /** One status to forward, as {@code {type, message}}. */
        public record Status(String type, @Nullable String message) {}

        /**
         * Filters one status from the Maven server: the statuses to forward now (the ready one later, once
         * the initialization progress ends; never, if the server errors or relaunches first).
         */
        public List<Status> onStatus(@Nullable String type, @Nullable String message) {
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            List<Status> out = new ArrayList<>(2);
            if (t.equals("serviceready") && (message == null || message.isBlank())) {
                held = true;
                return out; // nothing to say yet: the Maven extension is still initializing
            }
            if (t.equals("launching") || t.equals("error")) {
                held = false;
            }
            out.add(new Status(type == null ? "" : type, message));
            if (held
                    && t.equals("progressend")
                    && message != null
                    && message.strip().startsWith(INITIALIZATION_DONE)) {
                held = false;
                out.add(new Status("ServiceReady", null));
            }
            return out;
        }

        /** Whether a ready status is being held back. */
        public boolean holding() {
            return held;
        }
    }

    // --- diagnostics --------------------------------------------------------------------------------

    /** The marker of lemminx's "schema not cached yet" message, cleared once the download completes. */
    static final String DOWNLOADING_MARKER = "is downloading in the cache path";

    /**
     * Diagnostics as the editor shows them: a null {@code source} becomes {@code serverName} (lemminx-maven
     * omits it; copied reports printed "null"), and the schema-download notice is downgraded to information.
     * Unchanged entries are kept as they are; any server's list may pass through here.
     */
    public static List<LspDiagnostic> adjustDiagnostics(String serverName, List<LspDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return diagnostics == null ? List.of() : diagnostics;
        }
        List<LspDiagnostic> out = new ArrayList<>(diagnostics.size());
        for (LspDiagnostic d : diagnostics) {
            String source = d.source() == null || d.source().isBlank() ? serverName : d.source();
            LspDiagnostic.Severity severity = d.severity();
            if (d.message() != null && d.message().contains(DOWNLOADING_MARKER)) {
                severity = LspDiagnostic.Severity.INFO;
            }
            if (source == d.source() && severity == d.severity()) {
                out.add(d);
            } else {
                out.add(new LspDiagnostic(
                        d.startLine(),
                        d.startCol(),
                        d.endLine(),
                        d.endCol(),
                        severity,
                        d.message(),
                        d.code(),
                        source,
                        d.href(),
                        d.unnecessary(),
                        d.deprecated()));
            }
        }
        return out;
    }

    // --- completion ---------------------------------------------------------------------------------

    /**
     * Whether a completion item is a {@code ${…}} property reference — what lemminx-maven offers in every
     * text node of a pom, environment variables included.
     */
    public static boolean isPropertyReference(String label) {
        return label != null && label.startsWith("${") && label.endsWith("}");
    }

    /**
     * Drops {@code ${…}} items unless the text typed before the caret in the element starts with {@code $}:
     * 160 property references (one per environment variable) otherwise bury the nine artifacts matching
     * {@code commons-}. {@code prefix} is that typed text ({@code ""} when unknown, which keeps everything).
     */
    public static <T> List<T> filterCompletion(
            String prefix, List<T> items, java.util.function.Function<T, String> labelOf) {
        if (items == null || items.isEmpty() || prefix == null || prefix.isEmpty() || prefix.startsWith("$")) {
            return items == null ? List.of() : items;
        }
        List<T> out = new ArrayList<>(items.size());
        for (T item : items) {
            if (!isPropertyReference(labelOf.apply(item))) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * The text typed in the current element before the caret: {@code line} up to {@code column}, after the
     * last {@code >} (so {@code <artifactId>commons-} gives {@code commons-}), with leading blanks dropped.
     */
    public static String elementPrefix(String line, int column) {
        if (line == null) {
            return "";
        }
        int end = Math.max(0, Math.min(column, line.length()));
        String before = line.substring(0, end);
        int gt = before.lastIndexOf('>');
        return (gt < 0 ? before : before.substring(gt + 1)).stripLeading();
    }
}
