package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.editops.HtmlMarkupCheck;
import com.editora.markdown.MarkdownLint;
import com.editora.text.LspDiagnostic;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Editora's markup check ({@link HtmlMarkupCheck}, HTML review D5) as diagnostics. With the HTML language server
 * running they join its own (which cover only embedded CSS and JavaScript) and so reach the Problems window, the
 * squiggles and the problem counter, attributed to {@value #SOURCE}; without a server the buffer shows them as
 * marks of its own ({@link #marks}). Either way the check is Settings → Editor → HTML markup checks.
 */
public final class HtmlMarkupLint {

    private HtmlMarkupLint() {}

    /** The {@code source} of these diagnostics in the Problems window. */
    public static final String SOURCE = "editora";

    /** A page past this size is not checked: the check runs on every diagnostics pass. */
    static final int MAX_CHARS = 2_000_000;

    /** Whether {@code file} is an HTML page the check reads ({@code .html}, {@code .htm}; not a template). */
    public static boolean applies(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm");
    }

    /**
     * {@code server}'s diagnostics for {@code file} with the markup check's added, when the file is an HTML page
     * the HTML server serves and the check is on; {@code server} itself otherwise (and null stays null: an
     * "unchanged" report). Runs off the FX thread, on the text the server diagnosed.
     */
    static @Nullable List<LspDiagnostic> merged(
            String serverId,
            @Nullable Path file,
            @Nullable String text,
            @Nullable Path root,
            @Nullable List<LspDiagnostic> server,
            boolean on) {
        if (server == null || !on || text == null || !"html".equals(serverId) || !applies(file)) {
            return server;
        }
        List<LspDiagnostic> ours = diagnostics(text, file, root);
        if (ours.isEmpty()) {
            return server;
        }
        List<LspDiagnostic> all = new ArrayList<>(server.size() + ours.size());
        all.addAll(server);
        all.addAll(ours);
        return List.copyOf(all);
    }

    /** The check's problems in {@code text} (of {@code file}, null when untitled) as 0-based diagnostics. */
    public static List<LspDiagnostic> diagnostics(String text, @Nullable Path file, @Nullable Path root) {
        if (text.length() > MAX_CHARS) {
            return List.of();
        }
        List<HtmlMarkupCheck.Problem> problems = HtmlMarkupCheck.check(text, file, root, existence(file));
        if (problems.isEmpty()) {
            return List.of();
        }
        int[] lineStarts = lineStarts(text);
        List<LspDiagnostic> out = new ArrayList<>(problems.size());
        for (HtmlMarkupCheck.Problem p : problems) {
            int startLine = lineOf(lineStarts, p.start());
            int endLine = lineOf(lineStarts, p.end());
            out.add(new LspDiagnostic(
                    startLine,
                    p.start() - lineStarts[startLine],
                    endLine,
                    p.end() - lineStarts[endLine],
                    LspDiagnostic.Severity.WARNING,
                    message(p),
                    code(p.kind()),
                    SOURCE));
        }
        return List.copyOf(out);
    }

    /**
     * The same problems as the buffer's own marks (1-based, one line each), for a page no server serves: the
     * squiggle overlay the {@code .http} variable marks use.
     */
    public static List<MarkdownLint.Diagnostic> marks(String text, @Nullable Path file, @Nullable Path root) {
        List<MarkdownLint.Diagnostic> out = new ArrayList<>();
        for (LspDiagnostic d : diagnostics(text, file, root)) {
            int length = d.endLine() == d.startLine() ? Math.max(1, d.endCol() - d.startCol()) : 1;
            out.add(new MarkdownLint.Diagnostic(
                    d.startLine() + 1, d.startCol() + 1, length, MarkdownLint.WARNING, SOURCE, d.message(), null));
        }
        return List.copyOf(out);
    }

    /** The diagnostic's message in the UI language. */
    static String message(HtmlMarkupCheck.Problem p) {
        return switch (p.kind()) {
            case UNCLOSED -> tr("html.check.unclosed", p.subject());
            case STRAY_END -> tr("html.check.strayEnd", p.subject());
            case MISSING_FILE -> tr("html.check.missingFile", p.subject());
        };
    }

    static String code(HtmlMarkupCheck.Kind kind) {
        return switch (kind) {
            case UNCLOSED -> "unclosed-element";
            case STRAY_END -> "stray-end-tag";
            case MISSING_FILE -> "missing-file";
        };
    }

    /** How a link's file is looked for: on this machine only (a remote page's links would each be a round trip). */
    private static java.util.function.@Nullable Predicate<Path> existence(@Nullable Path file) {
        return file != null && Vfs.isLocal(file) ? Files::exists : null;
    }

    private static int[] lineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int k = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[k++] = i + 1;
            }
        }
        return starts;
    }

    private static int lineOf(int[] starts, int offset) {
        int i = java.util.Arrays.binarySearch(starts, offset);
        return i >= 0 ? i : Math.max(0, -i - 2);
    }
}
