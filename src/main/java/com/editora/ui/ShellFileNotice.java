package com.editora.ui;

import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.text.ShellScripts;

import static com.editora.i18n.Messages.tr;

/**
 * The notice a shell script gets when it opens with bytes the shell cannot run: CRLF line endings
 * ({@code set: pipefail\r: invalid option name}) or a UTF-8 byte-order mark (the {@code #!} line is no longer
 * the first bytes, and the mark becomes part of the first command). Its action converts the buffer to LF
 * without a BOM; saving writes it. Shown on every load, so a reload that fixed the file hides it again.
 *
 * <p>A Java file gets one for a byte-order mark alone: {@code javac} stops at it ({@code illegal character:
 * '\ufeff'}), so the file neither compiles nor runs, and Windows tools (PowerShell 5's {@code Set-Content -Encoding
 * UTF8}, older Notepad) write one. Its action drops the mark and leaves the line endings, which javac reads.
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
        if ("java".equals(buffer.getLanguage()) && EditorConfigCharset.UTF_8_BOM.equals(charset)) {
            buffer.showFileNotice(
                    tr("java.notice.bom", fileName), tr("shell.notice.removeBom"), tr("shell.notice.dismiss"), () -> {
                        buffer.setUserCharset(EditorConfigCharset.UTF_8);
                        buffer.hideFileNotice();
                        status.accept(tr("status.javaNotice.bomRemoved", fileName));
                    });
            return;
        }
        boolean shell = "shell".equals(buffer.getLanguage()) || ShellScripts.isShellScript(fileName, head);
        ShellScripts.Problem problem = ShellScripts.problem(shell ? "shell" : "", lineEnding, charset);
        if (problem == ShellScripts.Problem.NONE) {
            // Only its own: another feature's notice on the file (Trust Folder on a pom, XML/pom review 2) stays.
            String shown = buffer.fileNoticeText();
            if (shown != null
                    && (shown.equals(tr("shell.notice.crlf", fileName))
                            || shown.equals(tr("shell.notice.bom", fileName))
                            || shown.equals(tr("shell.notice.crlfBom", fileName))
                            || shown.equals(tr("java.notice.bom", fileName)))) {
                buffer.hideFileNotice();
            }
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
