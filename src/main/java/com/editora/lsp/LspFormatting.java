package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * Formatting requests for {@link LspManager}: whole document, range and on-type, with the HTML server's own wrap
 * column and final-newline handling, and why the last request of each file failed ({@link FormatFailure}). Edits
 * reach their callbacks on the FX thread.
 */
final class LspFormatting {

    private final LspManager m;

    LspFormatting(LspManager m) {
        this.m = m;
    }

    /**
     * A formatting request's options for {@code s}: {@code indent}'s, and for the HTML server the document's wrap
     * column and final newline too, which it takes from settings it may have cached before they changed (A3, D6).
     */
    private static org.eclipse.lsp4j.FormattingOptions formattingOptions(
            LanguageServerSession s, FormattingIndent indent) {
        return HtmlServerSettings.SERVER_ID.equals(s.serverId())
                ? s.htmlSettings().formattingOptions(indent)
                : indent.options();
    }

    /**
     * {@code edits}, the HTML server's format of {@code sent}, with the final newline Editora keeps itself (HTML
     * review 2, B104); any other server's as they are. {@code whole}: a Format Document, not a selection's.
     */
    private static List<com.editora.text.LspTextEdit> keptFinalNewline(
            LanguageServerSession s,
            FormattingIndent indent,
            @Nullable String sent,
            List<com.editora.text.LspTextEdit> edits,
            boolean whole) {
        Boolean want = HtmlServerSettings.SERVER_ID.equals(s.serverId())
                ? s.htmlSettings().finalNewline(indent)
                : null;
        return want == null || sent == null
                ? edits
                : com.editora.text.LspTextEdits.withFinalNewline(sent, edits, want, whole);
    }

    /** The text an HTML server holds for {@code file}, what its format edits are against; null for other servers. */
    private static @Nullable String htmlText(LanguageServerSession s, Path file) {
        return HtmlServerSettings.SERVER_ID.equals(s.serverId())
                ? s.documentSnapshots().get(LspManager.uri(file))
                : null;
    }

    /** True if {@code file}'s server is ready and advertises whole-document formatting. */
    public boolean supportsFormatting(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && formattingProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentFormattingProvider} (null-safe). */
    static boolean formattingProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentFormattingProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** True if {@code file}'s server is ready and advertises range formatting (for Tab line re-indent). */
    public boolean supportsRangeFormatting(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && rangeFormattingProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentRangeFormattingProvider} (null-safe). */
    static boolean rangeFormattingProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentRangeFormattingProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * Pure: the characters a server formats on ({@code documentOnTypeFormattingProvider}), collected into one
     * set — the spec splits them into a mandatory {@code firstTriggerCharacter} and an optional
     * {@code moreTriggerCharacter} list, a distinction that matters to the server and not to us (#740).
     * Empty when the server has no provider, which is also how the feature stays inert for those servers.
     */
    static java.util.Set<Character> onTypeTriggersOf(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getDocumentOnTypeFormattingProvider() == null) {
            return java.util.Set.of();
        }
        var provider = caps.getDocumentOnTypeFormattingProvider();
        java.util.Set<Character> out = new java.util.HashSet<>();
        String first = provider.getFirstTriggerCharacter();
        if (first != null && !first.isEmpty()) {
            out.add(first.charAt(0));
        }
        if (provider.getMoreTriggerCharacter() != null) {
            for (String more : provider.getMoreTriggerCharacter()) {
                if (more != null && !more.isEmpty()) {
                    out.add(more.charAt(0));
                }
            }
        }
        return out;
    }

    /** The on-type formatting trigger characters for {@code file}'s server; empty when unsupported (#740). */
    public java.util.Set<Character> onTypeTriggerCharacters(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s == null ? java.util.Set.of() : onTypeTriggersOf(s.capabilities());
    }

    /**
     * On-type formatting ({@code textDocument/onTypeFormatting}, #740): the edits the server would apply
     * having just seen {@code ch} typed at the given position. Delivered on the FX thread; empty when the
     * server has no provider or the request fails.
     */
    public void onTypeFormatting(
            Path file,
            int line,
            int character,
            char ch,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || onTypeTriggersOf(s.capabilities()).isEmpty()) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.onTypeFormatting(
                        LspManager.uri(file),
                        new Position(line, character),
                        String.valueOf(ch),
                        new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces))
                .whenComplete((result, error) -> {
                    List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.text.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(edits));
                });
    }

    /**
     * Requests range formatting ({@code textDocument/rangeFormatting}) over the line range
     * {@code [startLine..endLine]} and delivers the resulting edits to {@code cb} on the FX thread (empty
     * on error / unsupported). Used by Tab to re-indent the current line to the server's convention.
     */
    public void rangeFormatting(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        rangeFormatting(file, startLine, startChar, endLine, endChar, new FormattingIndent(tabSize, insertSpaces), cb);
    }

    /** As above, with the file's save rules in the request's options (Format Selection). */
    public void rangeFormatting(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            FormattingIndent indent,
            Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        org.eclipse.lsp4j.Range range =
                new org.eclipse.lsp4j.Range(new Position(startLine, startChar), new Position(endLine, endChar));
        String sent = htmlText(s, file);
        s.rangeFormatting(LspManager.uri(file), range, formattingOptions(s, indent))
                .whenComplete((result, error) -> {
                    noteFormatOutcome(file, error);
                    List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.text.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    List<com.editora.text.LspTextEdit> kept = keptFinalNewline(s, indent, sent, edits, false);
                    Platform.runLater(() -> cb.accept(kept));
                });
    }

    /**
     * Why the last format request of each file failed — {@link FormatFailure} — while it is the last outcome: a
     * failed, timed-out or abandoned request delivers no edits, which read as "Already formatted" (HTML review A5).
     */
    private final Map<Path, FormatFailure> formatFailures = new ConcurrentHashMap<>();

    /** How the last format request of {@code file} failed, or null when it was answered (or none was sent). */
    public @Nullable FormatFailure formatFailure(Path file) {
        return formatFailures.get(file);
    }

    private void noteFormatOutcome(Path file, @Nullable Throwable error) {
        if (error == null) {
            formatFailures.remove(file);
        } else {
            formatFailures.put(file, FormatFailure.of(error));
        }
    }

    /**
     * Requests whole-document formatting ({@code textDocument/formatting}) for {@code file} and delivers the
     * resulting edits to {@code cb} on the FX thread (empty list if the server returns nothing or errors; {@link
     * #formatFailure} then says which).
     * {@code tabSize}/{@code insertSpaces} are formatter hints (many formatters use their own config).
     */
    public void formatDocument(
            Path file, int tabSize, boolean insertSpaces, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        formatDocument(file, new FormattingIndent(tabSize, insertSpaces), cb);
    }

    /** As above, with the file's save rules in the request's options (see {@link FormattingIndent#options}). */
    public void formatDocument(Path file, FormattingIndent indent, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            noteFormatOutcome(file, new IllegalStateException("language server not available"));
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String sent = htmlText(s, file);
        s.formatting(LspManager.uri(file), formattingOptions(s, indent)).whenComplete((result, error) -> {
            noteFormatOutcome(file, error);
            List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
            if (error == null && result != null) {
                for (org.eclipse.lsp4j.TextEdit e : result) {
                    if (e != null && e.getRange() != null) {
                        edits.add(new com.editora.text.LspTextEdit(
                                e.getRange().getStart().getLine(),
                                e.getRange().getStart().getCharacter(),
                                e.getRange().getEnd().getLine(),
                                e.getRange().getEnd().getCharacter(),
                                e.getNewText() == null ? "" : e.getNewText()));
                    }
                }
            }
            List<com.editora.text.LspTextEdit> kept = keptFinalNewline(s, indent, sent, edits, true);
            Platform.runLater(() -> cb.accept(kept));
        });
    }
}
