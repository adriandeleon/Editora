package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.editora.text.ShellFiles;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

/**
 * Client-side workarounds for bash-language-server, each one standing in for a fix upstream (Bash review). Pure:
 * the session decides when they apply, these decide what changes.
 *
 * <ul>
 *   <li><b>References (C4, B9).</b> The server reads {@code includeDeclaration: false} as "leave out the occurrence
 *       under the caret", so Find References from a call listed the definition and hid the call. Editora asks with
 *       {@code true} and drops the declaration itself: the reference that starts where the definition does.
 *   <li><b>ShellCheck columns (B2).</b> ShellCheck counts columns in code points and the server passes them on as
 *       LSP characters, which are UTF-16 units: on a line with an emoji the squiggle sat one character left per
 *       astral character before it, and "Apply fix" put its quotes in the wrong place.
 *   <li><b>Library files (B7).</b> The server has no {@code workspace/didChangeWatchedFiles} handler and reads each
 *       workspace file once. A script changed on disk is re-read when it is opened, so the session opens and closes
 *       it ({@link #reanalysable}).
 * </ul>
 */
final class BashShims {

    /** The Bash server's id. */
    static final String SERVER_ID = "bash";

    /** The {@code source} of the diagnostics the server gets from ShellCheck. */
    static final String SHELLCHECK = "shellcheck";

    /** The largest file re-read for the server after a change on disk: what the server is sent whole. */
    static final long MAX_REANALYSED_BYTES = 1L << 20;

    /** The most files one burst of changes re-reads: a branch switch can touch every script in the tree. */
    static final int MAX_REANALYSED_PER_BURST = 200;

    private BashShims() {}

    static boolean isBash(@Nullable String serverId) {
        return SERVER_ID.equals(serverId);
    }

    // --- References (C4, B9) ---------------------------------------------------------------------------------

    /** The plain locations of a definition answer: a {@link LocationLink} by its target. */
    static List<Location> locations(@Nullable Either<List<? extends Location>, List<? extends LocationLink>> answer) {
        List<Location> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        if (answer.isLeft() && answer.getLeft() != null) {
            answer.getLeft().stream().filter(Objects::nonNull).forEach(out::add);
        } else if (answer.isRight() && answer.getRight() != null) {
            for (LocationLink link : answer.getRight()) {
                if (link != null && link.getTargetUri() != null) {
                    Range r = link.getTargetSelectionRange() != null
                            ? link.getTargetSelectionRange()
                            : link.getTargetRange();
                    out.add(new Location(link.getTargetUri(), r));
                }
            }
        }
        return out;
    }

    /**
     * {@code references} without the declarations: the server's definition range covers the whole declaration
     * ({@code f() { … }}, {@code name=value}) and its reference range only the name, so a reference is the
     * declaration when it is in the same file and starts where a definition does. A recursive call inside the body
     * starts elsewhere and stays.
     */
    static List<Location> withoutDeclarations(
            @Nullable List<? extends Location> references, List<Location> declarations) {
        List<Location> out = new ArrayList<>();
        if (references == null) {
            return out;
        }
        for (Location ref : references) {
            if (ref != null && declarations.stream().noneMatch(d -> sameStart(ref, d))) {
                out.add(ref);
            }
        }
        return out;
    }

    private static boolean sameStart(Location a, Location b) {
        return a.getUri() != null
                && a.getUri().equals(b.getUri())
                && a.getRange() != null
                && b.getRange() != null
                && Objects.equals(a.getRange().getStart(), b.getRange().getStart());
    }

    // --- ShellCheck columns (B2) ------------------------------------------------------------------------------

    /**
     * Turns the code-point columns of ShellCheck's diagnostics into UTF-16 ones, in place, against {@code text},
     * the document they were computed for. Nothing changes when the text has no character outside the Basic
     * Multilingual Plane, which is the common case and costs one scan.
     */
    static void diagnosticsToUtf16(@Nullable List<Diagnostic> diagnostics, @Nullable String text) {
        if (diagnostics == null || diagnostics.isEmpty() || text == null || !hasAstral(text)) {
            return;
        }
        Lines lines = new Lines(text);
        for (Diagnostic d : diagnostics) {
            if (d != null && SHELLCHECK.equals(d.getSource())) {
                rangeToUtf16(d.getRange(), lines);
            }
        }
    }

    /**
     * Does the same for the ShellCheck quick fixes among {@code actions}: their edits to the document {@code uri}
     * (as the server names it) and the diagnostics they fix. An action is ShellCheck's when one of its diagnostics
     * is.
     */
    static void codeActionsToUtf16(
            @Nullable List<Either<Command, CodeAction>> actions, String uri, @Nullable String text) {
        if (actions == null || actions.isEmpty() || text == null || !hasAstral(text)) {
            return;
        }
        Lines lines = new Lines(text);
        for (Either<Command, CodeAction> e : actions) {
            CodeAction action = e == null || !e.isRight() ? null : e.getRight();
            if (action == null
                    || action.getDiagnostics() == null
                    || action.getDiagnostics().stream().noneMatch(d -> d != null && SHELLCHECK.equals(d.getSource()))) {
                continue;
            }
            for (Diagnostic d : action.getDiagnostics()) {
                if (d != null) {
                    rangeToUtf16(d.getRange(), lines);
                }
            }
            WorkspaceEdit edit = action.getEdit();
            if (edit == null) {
                continue;
            }
            Map<String, List<TextEdit>> changes = edit.getChanges();
            if (changes != null && changes.get(uri) != null) {
                changes.get(uri).forEach(t -> rangeToUtf16(t == null ? null : t.getRange(), lines));
            }
            if (edit.getDocumentChanges() != null) {
                for (var change : edit.getDocumentChanges()) {
                    if (change != null
                            && change.isLeft()
                            && change.getLeft().getTextDocument() != null
                            && uri.equals(change.getLeft().getTextDocument().getUri())) {
                        for (var t : change.getLeft().getEdits()) {
                            rangeToUtf16(
                                    t == null || !t.isLeft()
                                            ? null
                                            : t.getLeft().getRange(),
                                    lines);
                        }
                    }
                }
            }
        }
    }

    private static void rangeToUtf16(@Nullable Range range, Lines lines) {
        if (range != null) {
            toUtf16(range.getStart(), lines);
            toUtf16(range.getEnd(), lines);
        }
    }

    private static void toUtf16(@Nullable Position p, Lines lines) {
        if (p != null) {
            p.setCharacter(utf16Column(lines.line(p.getLine()), p.getCharacter()));
        }
    }

    /**
     * The UTF-16 column of the {@code codePoints}-th code point of {@code line}. Past the end of the line each
     * further column counts as one unit, as the server's own columns do.
     */
    static int utf16Column(String line, int codePoints) {
        if (codePoints <= 0) {
            return Math.max(0, codePoints);
        }
        int units = 0;
        int counted = 0;
        while (counted < codePoints && units < line.length()) {
            units += Character.charCount(line.codePointAt(units));
            counted++;
        }
        return units + (codePoints - counted);
    }

    private static boolean hasAstral(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isSurrogate(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** The lines of a text, found once. */
    private static final class Lines {
        private final String text;
        private final int[] starts;

        Lines(String text) {
            this.text = text;
            int count = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    count++;
                }
            }
            starts = new int[count];
            int n = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts[n++] = i + 1;
                }
            }
        }

        String line(int line) {
            if (line < 0 || line >= starts.length) {
                return "";
            }
            int end = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
            return text.substring(starts[line], Math.max(starts[line], end));
        }
    }

    // --- Library files changed on disk (B7) -------------------------------------------------------------------

    /**
     * Whether a file changed on disk is one the Bash server should read again: a shell script by its extension or
     * name, or a {@code .inc} file (the server's own background-analysis glob includes them).
     */
    static boolean reanalysable(@Nullable Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        Path name = file.getFileName();
        String n = name.toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        return ext.equals("inc")
                || (dot > 0 && ShellFiles.isShellExtension(ext))
                || ShellFiles.isShellName(n, file.toString().replace('\\', '/'));
    }
}
