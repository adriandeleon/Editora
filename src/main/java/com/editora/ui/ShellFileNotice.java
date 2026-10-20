package com.editora.ui;

import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import com.editora.editor.ShellScripts;
import com.editora.editorconfig.EditorConfigCharset;

import static com.editora.i18n.Messages.tr;

/**
 * The notice a shell script gets when it opens with bytes the shell cannot run: CRLF line endings
 * ({@code set: pipefail\r: invalid option name}) or a UTF-8 byte-order mark (the {@code #!} line is no longer
 * the first bytes, and the mark becomes part of the first command). Its action converts the buffer to LF
 * without a BOM; saving writes it. Shown on every load, so a reload that fixed the file hides it again.
 */
final class ShellFileNotice {

    private ShellFileNotice() {}

    /**
     * Shows or hides the notice for {@code buffer}, just loaded from {@code fileName} with {@code lineEnding}
     * and decoded as {@code charset}; {@code status} reports the conversion.
     */
    static void offer(
            EditorBuffer buffer, String fileName, String lineEnding, String charset, Consumer<String> status) {
        String head = buffer.getArea().getText(0, Math.min(buffer.getArea().getLength(), 256));
        boolean shell = "shell".equals(buffer.getLanguage()) || ShellScripts.isShellScript(fileName, head);
        ShellScripts.Problem problem = ShellScripts.problem(shell ? "shell" : "", lineEnding, charset);
        if (problem == ShellScripts.Problem.NONE) {
            buffer.hideFileNotice();
            return;
        }
        String message = tr(
                switch (problem) {
                    case CRLF -> "shell.notice.crlf";
                    case BOM -> "shell.notice.bom";
                    default -> "shell.notice.crlfBom";
                },
                fileName);
        String action = tr(problem == ShellScripts.Problem.BOM ? "shell.notice.removeBom" : "shell.notice.convert");
        buffer.showFileNotice(message, action, tr("shell.notice.dismiss"), () -> {
            fix(buffer);
            buffer.hideFileNotice();
            status.accept(tr("status.shellNotice.converted", fileName));
        });
    }

    /** Converts the buffer to LF and, if it had one, drops the byte-order mark; the next save writes both. */
    static void fix(EditorBuffer buffer) {
        buffer.convertLineEndings(false);
        if (EditorConfigCharset.UTF_8_BOM.equals(buffer.getEffectiveCharset())) {
            buffer.setUserCharset(EditorConfigCharset.UTF_8);
        }
    }
}
