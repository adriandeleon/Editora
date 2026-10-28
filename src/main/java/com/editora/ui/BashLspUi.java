package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the editor does differently when bash-language-server answers (Bash review, 2026-10-27): the answers it gives
 * that mean something other than what they say. Kept out of {@link LspCoordinator}, which calls in with a line each.
 *
 * <ul>
 *   <li>Rename on a command word ({@code echo}, {@code cd}): the server allows it and rewrites every call; refused
 *       unless Go to Definition finds a declaration in a script (C1, B5), and a new name must be a shell name (B11).
 *   <li>Format Document on a script shfmt cannot parse: no edits, the reason only in the server's log (B3, C3).
 *   <li>The problem counter over a zsh script, which ShellCheck cannot check (C14).
 * </ul>
 */
final class BashLspUi {

    /** The Bash server's id ({@code LspServerRegistry.ServerDef.BASH}). */
    static final String SERVER_ID = "bash";

    private BashLspUi() {}

    static boolean isBash(@Nullable String serverId) {
        return SERVER_ID.equals(serverId);
    }

    // --- the problem counter (C14) ---------------------------------------------------------------------------------

    /** How much of a buffer {@link #uncheckedReason} reads: the shebang and the directives below it. */
    private static final int HEAD_CHARS = 4096;

    /** Why the Bash server does not lint {@code b} although it serves it (a zsh script), or null when it does. */
    static @Nullable String uncheckedReason(EditorBuffer b, @Nullable Path path) {
        if (!"shell".equals(b.getLanguage())) {
            return null;
        }
        CodeArea a = b.getArea();
        String head = a.getText(0, Math.min(a.getLength(), HEAD_CHARS));
        String name = path == null || path.getFileName() == null
                ? null
                : path.getFileName().toString();
        return com.editora.text.ShellDialect.isZsh(name, head) ? tr("editor.problemCounter.zshNotChecked") : null;
    }

    // --- Format Document (B3, C3, B14) -----------------------------------------------------------------------------

    /** shfmt's parse error as bash-language-server logs it: {@code …/file.sh:5:1: `for` statement must end…}. */
    private static final Pattern SHFMT_ERROR = Pattern.compile(":(\\d+):(\\d+): (.+)$");

    /** shfmt's own aside on a POSIX parse ("(parsed as posix via -ln=auto)"), said by the hint instead. */
    private static final Pattern PARSED_AS = Pattern.compile("\\s*\\(parsed as [^)]*\\)\\s*$");

    /**
     * What Format Document says when the server changed nothing. For the Bash server: shfmt is missing, or it could
     * not parse the script (the reason, with its line, from {@code loggedError}, the server's log line since the
     * request went out), and only otherwise "Already formatted", which it used to say over a script with a syntax
     * error that was never formatted at all.
     */
    static String formatNoChange(@Nullable String serverId, BooleanSupplier shfmtFound, @Nullable String loggedError) {
        if (!isBash(serverId)) {
            return tr("status.lsp.formatNoChange");
        }
        String why = shfmtFailure(loggedError);
        if (why != null) {
            return why;
        }
        return shfmtFound.getAsBoolean() ? tr("status.lsp.formatNoChange") : tr("status.lsp.bashNoShfmt");
    }

    /** The message for shfmt's failure in {@code logged} (a server log line), or null when it is not one. Pure. */
    static @Nullable String shfmtFailure(@Nullable String logged) {
        if (logged == null) {
            return null;
        }
        int at = logged.indexOf("Error while formatting:");
        if (at < 0) {
            return null;
        }
        String rest = logged.substring(at + "Error while formatting:".length()).strip();
        Matcher m = SHFMT_ERROR.matcher(rest);
        if (!m.find()) {
            return tr("status.lsp.bashCannotFormat", rest.replaceFirst("^Error:\\s*", ""));
        }
        String message = m.group(3).strip();
        boolean posix = message.contains("tried parsing as posix");
        message = PARSED_AS.matcher(message).replaceFirst("");
        return tr(
                posix ? "status.lsp.bashCannotFormatPosix" : "status.lsp.bashCannotFormatAt",
                Integer.parseInt(m.group(1)),
                message);
    }

    // --- Rename (C1, B5, B11) --------------------------------------------------------------------------------------

    /** A shell variable's name, which is also what a function's name usually is. */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** What bash also takes as a function's name: {@code -}, {@code .} and {@code :} too ({@code git-sync}). */
    private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]*");

    /**
     * Before the rename prompt on a Bash script: Go to Definition at the caret, and the rename goes ahead only when it
     * finds a declaration in a script. bash-language-server offers rename on every command word, and renaming
     * {@code echo} rewrote every call of it in the file with no preview. {@code prompt} is given the check a new name
     * must pass; any other server goes straight to it, with no check.
     */
    static void beforeRename(
            LspManager lsp,
            EditorBuffer b,
            Path path,
            int line,
            int col,
            String oldName,
            Consumer<String> say,
            Consumer<Function<String, @Nullable String>> prompt) {
        if (!isBash(lsp.managedServerId(path))) {
            prompt.accept(name -> null);
            return;
        }
        lsp.definition(path, line, col, targets -> {
            LspManager.Target declared = declaration(targets);
            if (declared == null) {
                say.accept(tr("status.lsp.bashCannotRenameCommand", oldName));
                return;
            }
            String declaration = declarationLine(b, path, declared);
            // a name only a function can have, or a declaration not read: the function rule, the looser one
            boolean function = !VARIABLE_NAME.matcher(oldName).matches()
                    || declaration == null
                    || declaresFunction(declaration, oldName);
            prompt.accept(name -> validName(name, function) ? null : tr("status.lsp.renameInvalidShellName", name));
        });
    }

    /** The first target that is a file, or null: a builtin or a PATH command has none. */
    static LspManager.@Nullable Target declaration(@Nullable List<LspManager.Target> targets) {
        if (targets == null) {
            return null;
        }
        for (LspManager.Target t : targets) {
            if (t != null && t.file() != null) {
                return t;
            }
        }
        return null;
    }

    /** Whether {@code name} may replace a shell variable's ({@code function} false) or a function's name. Pure. */
    static boolean validName(String name, boolean function) {
        return name != null
                && (function ? FUNCTION_NAME : VARIABLE_NAME).matcher(name).matches();
    }

    /** Whether {@code line} declares {@code name} as a function: {@code name() {} or {@code function name}. Pure. */
    static boolean declaresFunction(@Nullable String line, String name) {
        if (line == null || name == null || name.isEmpty()) {
            return false;
        }
        String q = Pattern.quote(name);
        return Pattern.compile("(^|[\\s;&|({])" + q + "\\s*\\(\\s*\\)")
                        .matcher(line)
                        .find()
                || Pattern.compile("(^|[\\s;&|({])function\\s+" + q + "(\\s|\\(|\\{|$)")
                        .matcher(line)
                        .find();
    }

    /** The declaration's line: from the buffer when it is this file, else from a small local file; null if unread. */
    private static @Nullable String declarationLine(EditorBuffer b, Path path, LspManager.Target t) {
        Path file = t.file();
        if (file == null) {
            return null;
        }
        if (file.equals(path)) {
            CodeArea a = b.getArea();
            return t.line() >= 0 && t.line() < a.getParagraphs().size()
                    ? a.getParagraph(t.line()).getText()
                    : null;
        }
        try {
            if (com.editora.vfs.Vfs.isRemote(file) || java.nio.file.Files.size(file) > 512 * 1024) {
                return null;
            }
            try (var lines = java.nio.file.Files.lines(file)) {
                return lines.skip(Math.max(0, t.line())).findFirst().orElse(null);
            }
        } catch (java.io.IOException | java.io.UncheckedIOException | SecurityException e) {
            return null;
        }
    }
}
