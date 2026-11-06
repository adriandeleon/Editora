package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.text.OccurrenceSpan;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * The requests that help while typing, for {@link LspManager}: completion and its lazy resolution, signature help,
 * document highlights, hover, and the trigger characters that fire them. Results reach their callbacks on the FX
 * thread; an unserved file answers empty.
 */
final class LspAssists {

    private final LspManager m;

    LspAssists(LspManager m) {
        this.m = m;
    }

    public void completion(Path file, int line, int character, Consumer<List<CompletionItem>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.completion(LspManager.uri(file), new Position(line, character)).whenComplete((result, error) -> {
            List<CompletionItem> items = error == null ? CompletionMapper.itemsOf(result) : List.of();
            Platform.runLater(() -> cb.accept(items));
        });
    }

    /** Maps and ranks off FX; cancellation is wired to the original JSON-RPC future. */
    public Runnable completion(
            Path file,
            int line,
            int character,
            int triggerKind,
            @Nullable String triggerCharacter,
            java.util.function.Function<CompletionItem, @Nullable Runnable> onAccept,
            Consumer<com.editora.completion.CompletionResult> cb) {
        LanguageServerSession session = m.sessionFor(file);
        if (session == null) {
            cb.accept(com.editora.completion.CompletionResult.EMPTY);
            return () -> {};
        }
        var context =
                new org.eclipse.lsp4j.CompletionContext(org.eclipse.lsp4j.CompletionTriggerKind.forValue(triggerKind));
        context.setTriggerCharacter(triggerCharacter);
        long sent = com.editora.completion.CompletionTrace.now();
        var request = session.completion(LspManager.uri(file), new Position(line, character), context);
        if (sent != 0) request.whenComplete((r, e) -> com.editora.completion.CompletionTrace.elapsed("server", sent));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        request.whenCompleteAsync((result, error) -> {
            if (cancelled.get()) return;
            long mapping = com.editora.completion.CompletionTrace.now();
            var items = error == null ? CompletionMapper.itemsOf(result) : List.<CompletionItem>of();
            if (BashCompletions.SERVER_ID.equals(session.serverId())) {
                // ranked by the server's own categories, and variables only after `$` (Bash review C11)
                items = BashCompletions.adjust(
                        items,
                        BashCompletions.lineBefore(session.documentSnapshot(LspManager.uri(file)), line, character));
            }
            var options = session.capabilities() == null
                    ? null
                    : session.capabilities().getCompletionProvider();
            if (options != null && options.getAllCommitCharacters() != null) {
                for (var item : items) {
                    if (item != null && item.getCommitCharacters() == null)
                        item.setCommitCharacters(options.getAllCommitCharacters());
                }
            }
            var mapped =
                    com.editora.completion.CompletionEngine.sortLspByRelevance(CompletionMapper.map(items, onAccept));
            boolean incomplete = error == null
                    && result != null
                    && result.isRight()
                    && result.getRight() != null
                    && result.getRight().isIncomplete();
            var batch = new com.editora.completion.CompletionResult(mapped, incomplete);
            com.editora.completion.CompletionTrace.elapsed("map-and-rank", mapping);
            Platform.runLater(() -> {
                if (!cancelled.get() && m.sessionFor(file) == session) cb.accept(batch);
            });
        });
        return () -> {
            cancelled.set(true);
            request.cancel(true);
        };
    }

    /**
     * Resolves {@code item} (for {@code file}) and delivers its {@code additionalTextEdits} — the extra
     * edits a completion implies, e.g. a TypeScript auto-import's {@code import} line — to {@code cb} on
     * the FX thread (empty list if none or unresolved).
     */
    public void resolveCompletion(Path file, CompletionItem item, Consumer<List<com.editora.text.LspTextEdit>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || item == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.resolveCompletion(item).whenComplete((resolved, error) -> {
            List<com.editora.text.LspTextEdit> edits = new ArrayList<>();
            CompletionItem use = error == null && resolved != null ? resolved : item;
            if (use.getAdditionalTextEdits() != null) {
                for (org.eclipse.lsp4j.TextEdit e : use.getAdditionalTextEdits()) {
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
     * Resolves the documentation for a completion item (the opaque {@code token} is the LSP
     * {@code CompletionItem} from {@link CompletionMapper}) and delivers its markdown/plain-text body to
     * {@code cb} on the FX thread — {@code null} when there is none. Short-circuits when the item already
     * carries its documentation (many servers send it eagerly), else issues {@code completionItem/resolve}.
     * Drives the IntelliJ-style documentation side-popup.
     */
    public void resolveCompletionDoc(Path file, Object token, Consumer<String> cb) {
        if (!(token instanceof CompletionItem item)) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String existing = documentationOf(item);
        LanguageServerSession s = m.sessionFor(file);
        if (existing != null || s == null) {
            Platform.runLater(() -> cb.accept(existing));
            return;
        }
        String before = item.getDetail();
        String fence = TypeScriptServer.SERVER_ID.equals(s.serverId()) ? "typescript" : "";
        s.resolveCompletion(item).whenComplete((resolved, error) -> {
            String doc = (error == null && resolved != null)
                    ? withResolvedDetail(before, resolved.getDetail(), documentationOf(resolved), fence)
                    : null;
            Platform.runLater(() -> cb.accept(doc));
        });
    }

    /**
     * {@code doc} led by the signature {@code completionItem/resolve} brought, as a fenced code block, when it says
     * more than the item's own detail ({@code before}): typescript-language-server puts a member's signature, and an
     * auto-import's "Auto import from './math.js'", only in the resolved {@code detail}, which the doc pane never
     * showed (JS review B107). Null when there is neither. Pure.
     */
    static @Nullable String withResolvedDetail(
            @Nullable String before, @Nullable String resolved, @Nullable String doc, String fence) {
        String detail = resolved == null ? "" : resolved.strip();
        if (detail.isEmpty() || detail.equals(before == null ? "" : before.strip())) {
            return doc;
        }
        String block = "```" + fence + "\n" + detail + "\n```";
        return doc == null || doc.isBlank() ? block : block + "\n\n" + doc;
    }

    /**
     * The item's documentation as Markdown for the doc popup, or null/blank → null. Text marked {@code plaintext}
     * is escaped first, as a hover's is, so a {@code *} or {@code <tag>} in it shows as written (HTML review D7).
     */
    static @Nullable String documentationOf(CompletionItem item) {
        var doc = item.getDocumentation();
        if (doc == null) {
            return null;
        }
        var markup = doc.isRight() ? doc.getRight() : null;
        String s = doc.isLeft() ? doc.getLeft() : (markup == null ? null : markup.getValue());
        if (s == null || s.isBlank()) {
            return null;
        }
        return markup != null && org.eclipse.lsp4j.MarkupKind.PLAINTEXT.equals(markup.getKind())
                ? HoverMarkdown.escapePlainText(s)
                : s;
    }

    /** True if {@code file}'s server is ready and advertises signature help (#674). */
    public boolean supportsSignatureHelp(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && signatureHelpProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code signatureHelpProvider} (null-safe). */
    static boolean signatureHelpProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && caps.getSignatureHelpProvider() != null;
    }

    /** The signature-help trigger characters {@code file}'s server advertised (usually {@code (} and
     *  {@code ,}), retrigger characters included; empty when not ready / none. */
    public java.util.Set<Character> signatureTriggerCharacters(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s == null ? java.util.Set.of() : signatureTriggerCharsOf(s.capabilities());
    }

    /** Pure extraction of signature-help trigger + retrigger characters (null-safe). */
    static java.util.Set<Character> signatureTriggerCharsOf(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getSignatureHelpProvider() == null) {
            return java.util.Set.of();
        }
        java.util.Set<Character> out = new java.util.HashSet<>();
        addFirstChars(out, caps.getSignatureHelpProvider().getTriggerCharacters());
        addFirstChars(out, caps.getSignatureHelpProvider().getRetriggerCharacters());
        return out;
    }

    private static void addFirstChars(java.util.Set<Character> out, List<String> tokens) {
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isEmpty()) {
                    out.add(t.charAt(0));
                }
            }
        }
    }

    /**
     * Requests signature help at a 0-based position; the raw lsp4j result (or null) arrives on the FX thread
     * — resolved for display via {@link SignatureFormat#resolve}. {@code triggerChar} is the character that
     * fired it ({@code null} = the explicit command), {@code retrigger} that a popup is already open (#725).
     */
    public void signatureHelp(
            Path file,
            int line,
            int character,
            String triggerChar,
            boolean retrigger,
            Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        signatureHelp(file, line, character, triggerChar, retrigger, null, cb);
    }

    public void signatureHelp(
            Path file,
            int line,
            int character,
            @Nullable String triggerChar,
            boolean retrigger,
            org.eclipse.lsp4j.@Nullable SignatureHelp activeHelp,
            Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String uri = LspManager.uri(file);
        // Never joined (the context differs call to call), but a newer request still cancels the older one:
        // the popup only ever takes the latest answer.
        m.latest.issue(
                uri,
                "signatureHelp",
                new Object(),
                true,
                () -> s.signatureHelp(uri, new Position(line, character), triggerChar, retrigger, activeHelp),
                (r, e) -> Platform.runLater(() -> cb.accept(e == null ? r : null)));
    }

    /** True if {@code file}'s server is ready and advertises document highlight (occurrences — #675). */
    public boolean supportsDocumentHighlight(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && documentHighlightProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentHighlightProvider} (null-safe). */
    static boolean documentHighlightProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentHighlightProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Occurrences of the symbol at a 0-based position, mapped to neutral {@link OccurrenceSpan}s
     *  (Write kind flagged) and delivered on the FX thread — empty when unavailable (#675). */
    public void documentHighlights(
            Path file, int line, int character, Consumer<List<com.editora.text.OccurrenceSpan>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = LspManager.uri(file);
        Position at = new Position(line, character);
        m.latest.issue(
                uri,
                "documentHighlight",
                LspManager.stamp(s, uri, at),
                true,
                () -> s.documentHighlight(uri, at),
                (result, error) -> {
                    List<com.editora.text.OccurrenceSpan> spans = new ArrayList<>();
                    if (error == null && result != null) {
                        for (var h : result) {
                            if (h == null || h.getRange() == null) {
                                continue;
                            }
                            var r = h.getRange();
                            spans.add(new com.editora.text.OccurrenceSpan(
                                    r.getStart().getLine(),
                                    r.getStart().getCharacter(),
                                    r.getEnd().getLine(),
                                    r.getEnd().getCharacter(),
                                    h.getKind() == org.eclipse.lsp4j.DocumentHighlightKind.Write));
                        }
                    }
                    Platform.runLater(() -> cb.accept(spans));
                });
    }

    public void hover(Path file, int line, int character, Consumer<String> cb) {
        hover(file, line, character, cb, failure -> cb.accept(""));
    }

    /**
     * As {@link #hover(Path, int, int, Consumer)}, but a request the server answered with a fault reaches
     * {@code onFailure} (with the server's message) instead of reading as "no documentation" — the one place
     * a user looks when hover does nothing (Maven review C2). Both callbacks run on the FX thread.
     */
    public void hover(Path file, int line, int character, Consumer<String> cb, Consumer<String> onFailure) {
        hoverAt(file, null, line, character, cb, onFailure);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void hoverAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<String> cb,
            Consumer<String> onFailure) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null) {
            cb.accept("");
            return;
        }
        BashLsp.hoverDeadline(
                        s,
                        s.hover(
                                documentUri != null ? documentUri : LspManager.uri(anchor),
                                new Position(line, character)))
                .whenComplete((hover, error) -> {
                    String late = BashLsp.hoverTimedOut(s, error); // explainshell kept it waiting (B10)
                    if (late != null) {
                        Platform.runLater(() -> onFailure.accept(late));
                        return;
                    }
                    var fault = error == null ? null : RequestErrorTracker.responseError(error);
                    if (fault != null && RequestErrorTracker.serverFault(fault.getCode())) {
                        String why = RequestErrorTracker.describe(fault);
                        Platform.runLater(() -> onFailure.accept(why));
                        return;
                    }
                    String raw = error == null ? hoverText(hover) : "";
                    String text = LspServerRegistry.MAVEN_POM_SERVER_ID.equals(s.serverId())
                            ? MavenPomServer.fixHoverLinks(raw) // a plugin's "managed in" link (review 2, E6)
                            : raw;
                    Platform.runLater(() -> cb.accept(text));
                });
    }

    /**
     * The completion <em>trigger characters</em> {@code file}'s server advertised (e.g. {@code .} for Java,
     * {@code <} for HTML), or empty if the server isn't ready / advertises none. The editor fires LSP
     * completion when one of these is typed — so HTML/CSS/etc. complete like VS Code, not just on a word
     * prefix (see {@code EditorBuffer.setLspTriggerChars}).
     */
    public java.util.Set<Character> triggerCharacters(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s == null ? java.util.Set.of() : triggerCharsOf(s.capabilities());
    }

    /** Pure extraction of completion trigger characters from a server's capabilities (null-safe). */
    static java.util.Set<Character> triggerCharsOf(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null || caps.getCompletionProvider() == null) {
            return java.util.Set.of();
        }
        List<String> triggers = caps.getCompletionProvider().getTriggerCharacters();
        if (triggers == null) {
            return java.util.Set.of();
        }
        java.util.Set<Character> out = new java.util.HashSet<>();
        for (String t : triggers) {
            if (t != null && !t.isEmpty()) {
                out.add(t.charAt(0));
            }
        }
        return out;
    }

    static String hoverText(Hover hover) {
        return HoverMarkdown.of(hover);
    }
}
