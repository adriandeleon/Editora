package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.CodeLensKind;
import com.editora.lsp.LspManager.CodeLensSpan;
import com.editora.lsp.LspManager.InlayHintSpan;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * What a server draws between and beside the code, for {@link LspManager}: code lenses and inlay hints over a line
 * window, mapped to neutral spans and delivered on the FX thread.
 */
final class LspInlineHints {

    private final LspManager m;

    LspInlineHints(LspManager m) {
        this.m = m;
    }

    /** True if {@code file}'s server is ready and advertises code lenses. */
    public boolean supportsCodeLens(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && s.capabilities() != null && s.capabilities().getCodeLensProvider() != null;
    }

    /**
     * Pure: what a lens counts, read off its command id ({@code java.show.references},
     * {@code java.show.implementations}, {@code editor.action.showReferences}, …) — or null for a lens that
     * does something else (a "Run | Debug" lens), which is not shown: its command is the server's own
     * editor integration and nothing here could carry it out.
     */
    static @Nullable CodeLensKind codeLensKind(org.eclipse.lsp4j.Command command) {
        String id = command == null || command.getCommand() == null
                ? ""
                : command.getCommand().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("implementation")) {
            return CodeLensKind.IMPLEMENTATIONS;
        }
        return id.contains("reference") ? CodeLensKind.REFERENCES : null;
    }

    /** Pure: the lenses worth showing, as neutral spans in line order. */
    static List<CodeLensSpan> codeLensSpans(List<org.eclipse.lsp4j.CodeLens> lenses) {
        List<CodeLensSpan> out = new ArrayList<>();
        if (lenses == null) {
            return out;
        }
        for (org.eclipse.lsp4j.CodeLens lens : lenses) {
            if (lens == null || lens.getRange() == null || lens.getCommand() == null) {
                continue;
            }
            CodeLensKind kind = codeLensKind(lens.getCommand());
            String title = lens.getCommand().getTitle();
            if (kind == CodeLensKind.IMPLEMENTATIONS
                    && title != null
                    && title.strip().startsWith("0 ")) {
                continue; // jdtls answers for every type and method; "0 implementations" on each is noise
            }
            if (kind != null && title != null && !title.isBlank()) {
                Position start = lens.getRange().getStart();
                out.add(new CodeLensSpan(start.getLine(), start.getCharacter(), title.strip(), kind));
            }
        }
        out.sort(java.util.Comparator.comparingInt(CodeLensSpan::line).thenComparing(CodeLensSpan::kind));
        return out;
    }

    /**
     * Requests the code lenses on lines {@code [startLine..endLine]}, delivered as neutral spans on the FX
     * thread — empty when unsupported or failed.
     */
    public void requestCodeLens(Path file, int startLine, int endLine, Consumer<List<CodeLensSpan>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = LspManager.uri(file);
        m.latest.issue(
                uri,
                "codeLens",
                LspManager.stamp(s, uri, List.of(startLine, endLine)),
                true,
                () -> s.codeLens(uri, startLine, endLine),
                (lenses, error) -> {
                    List<CodeLensSpan> out = error == null ? codeLensSpans(lenses) : List.of();
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /** True if {@code file}'s server is ready and advertises inlay hints (#681). */
    public boolean supportsInlayHints(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && inlayHintProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code inlayHintProvider} (null-safe, either form). */
    static boolean inlayHintProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getInlayHintProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * The LSP range covering lines {@code [startLine..endLine]} <b>inclusive</b> of a document with
     * {@code lineCount} lines whose last line is {@code lastLineLength} characters long. Pure; unit-tested.
     *
     * <p>Covering line {@code endLine} means an exclusive end of {@code Position(endLine + 1, 0)} — but for
     * the <em>last</em> line that names a line the document does not have, and <b>jdtls answers an
     * out-of-range range with an empty list rather than an error</b>. That is the whole of #715: inlay hints
     * silently never appeared because the padded window always reached past the end of the file, and the
     * empty response is indistinguishable from "this file has no hints". Measured on jdtls 1.60 with a
     * 27-line file (last line index 26): end {@code Position(27,0)} → 0 hints, {@code Position(26,0)} → 6.
     * Unchanged in 1.62 ({@code JdtlsUpgradeProbeTest}: 0 hints past the end, 5 clamped).
     *
     * <p>So when {@code endLine + 1} would leave the document, the end becomes the true document end —
     * {@code Position(lastLine, lastLineLength)} — which still covers the last line in full.
     */
    static org.eclipse.lsp4j.Range inclusiveLineRange(int startLine, int endLine, int lineCount, int lastLineLength) {
        int lastLine = Math.max(0, lineCount - 1);
        int start = Math.min(Math.max(0, startLine), lastLine);
        int endExclusive = Math.max(start, Math.max(0, endLine)) + 1;
        Position end = endExclusive <= lastLine
                ? new Position(endExclusive, 0)
                : new Position(lastLine, Math.max(0, lastLineLength));
        return new org.eclipse.lsp4j.Range(new Position(start, 0), end);
    }

    /**
     * Requests inlay hints over the line window {@code [startLine..endLine]} (inclusive), delivered as
     * neutral spans on the FX thread — empty when unsupported/failed (#681). {@code lineCount} and
     * {@code lastLineLength} describe the document so the range can be clamped to it: a range reaching past
     * the last line makes jdtls return nothing at all (see {@link #inclusiveLineRange} — #715).
     */
    public void requestInlayHints(
            Path file,
            int startLine,
            int endLine,
            int lineCount,
            int lastLineLength,
            Consumer<List<InlayHintSpan>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var range = inclusiveLineRange(startLine, endLine, lineCount, lastLineLength);
        String uri = LspManager.uri(file);
        m.latest.issue(
                uri,
                "inlayHint",
                LspManager.stamp(s, uri, range),
                true,
                () -> s.inlayHint(uri, range),
                (hints, error) -> {
                    List<InlayHintSpan> out = new ArrayList<>();
                    if (error == null && hints != null) {
                        for (var h : hints) {
                            if (h == null || h.getPosition() == null) {
                                continue;
                            }
                            String label = inlayLabel(h);
                            if (!label.isBlank()) {
                                out.add(new InlayHintSpan(
                                        h.getPosition().getLine(),
                                        h.getPosition().getCharacter(),
                                        label,
                                        isParameterHint(h.getKind(), label),
                                        Boolean.TRUE.equals(h.getPaddingLeft()),
                                        Boolean.TRUE.equals(h.getPaddingRight())));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /**
     * Pure: whether a hint names an argument (parameter) rather than an inferred type, used to decide
     * what filtering may touch (#823).
     *
     * <p>{@code kind} is <b>optional</b> in the protocol, and a server that omits it would otherwise have
     * every hint treated as a parameter — so an unkinded {@code : String} would become filterable. The
     * fallback reads the label instead: a parameter name ends with {@code :} or {@code =} ({@code count:},
     * {@code ...args:}); anything else is a type — jdtls, which sends no kind, labels a {@code var} with
     * {@code : int} and a lambda parameter with a bare {@code String}.
     */
    static boolean isParameterHint(org.eclipse.lsp4j.InlayHintKind kind, String label) {
        if (kind == org.eclipse.lsp4j.InlayHintKind.Parameter) {
            return true;
        }
        if (kind == org.eclipse.lsp4j.InlayHintKind.Type) {
            return false;
        }
        String l = label == null ? "" : label.strip();
        return !l.startsWith(":") && (l.endsWith(":") || l.endsWith("="));
    }

    /** Pure: an inlay hint's display label — the plain string, or its label parts joined. */
    static String inlayLabel(org.eclipse.lsp4j.InlayHint h) {
        if (h.getLabel() == null) {
            return "";
        }
        if (h.getLabel().isLeft()) {
            return h.getLabel().getLeft() == null ? "" : h.getLabel().getLeft().strip();
        }
        StringBuilder sb = new StringBuilder();
        if (h.getLabel().getRight() != null) {
            for (var part : h.getLabel().getRight()) {
                if (part != null && part.getValue() != null) {
                    sb.append(part.getValue());
                }
            }
        }
        return sb.toString().strip();
    }
}
