package com.editora.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.editorconfig.EditorConfigCharset;

import static com.editora.i18n.Messages.tr;

/**
 * The warning before running a shell script saved with Windows line endings or a byte-order mark.
 *
 * <p>Bash reads the {@code \r} of a CRLF line as part of the word before it, so such a script fails with
 * messages that never mention line endings ({@code $'\r': command not found}, {@code syntax error: unexpected
 * end of file}); a byte-order mark turns the first line into an unknown command. The warning names the cause
 * and offers to fix it (convert to LF, drop the UTF-8 BOM) before the save that precedes every Run, or to run
 * the file as it is.
 */
final class ShellRunWarning {

    private ShellRunWarning() {}

    /** What is wrong with a shell script's bytes for running it. */
    enum Hazard {
        CRLF,
        BOM
    }

    /** The hazards of a buffer that saves with {@code lineEnding} in {@code charset} (pure). */
    static Set<Hazard> hazards(String lineEnding, String charset) {
        Set<Hazard> out = EnumSet.noneOf(Hazard.class);
        if ("CRLF".equals(lineEnding)) {
            out.add(Hazard.CRLF);
        }
        if (charset != null && EditorConfigCharset.writesBom(charset)) {
            out.add(Hazard.BOM);
        }
        return out;
    }

    /** Whether the buffer's hazards can all be fixed here (an EditorConfig-forced CRLF or UTF-16 cannot). */
    static boolean fixable(EditorBuffer buffer, Set<Hazard> hazards) {
        if (hazards.contains(Hazard.CRLF) && buffer.isLineEndingForced()) {
            return false;
        }
        return !hazards.contains(Hazard.BOM) || EditorConfigCharset.UTF_8_BOM.equals(buffer.getEffectiveCharset());
    }

    /** Makes the next save write LF and no BOM. */
    static void fix(EditorBuffer buffer, Set<Hazard> hazards) {
        if (hazards.contains(Hazard.CRLF)) {
            buffer.convertLineEndings(false);
        }
        if (hazards.contains(Hazard.BOM)) {
            buffer.setUserCharset(EditorConfigCharset.UTF_8);
        }
    }

    /**
     * Asks what to do; {@code run} follows "Convert and Run" (after the fix) and "Run Anyway". Shown without
     * blocking, so the window keeps painting and a test can answer it.
     */
    static Alert ask(Window owner, EditorBuffer buffer, Set<Hazard> hazards, Runnable run) {
        boolean canFix = fixable(buffer, hazards);
        List<String> causes = new ArrayList<>();
        if (hazards.contains(Hazard.CRLF)) {
            causes.add(tr(buffer.isLineEndingForced() ? "run.shellWarning.crlfForced" : "run.shellWarning.crlf"));
        }
        if (hazards.contains(Hazard.BOM)) {
            causes.add(tr("run.shellWarning.bom"));
        }
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.WARNING));
        alert.initOwner(owner);
        alert.setTitle(tr("run.shellWarning.title"));
        alert.setHeaderText(tr("run.shellWarning.header", buffer.getTitle()));
        alert.setContentText(String.join("\n", causes));
        ButtonType convert = new ButtonType(tr("run.shellWarning.fix"), ButtonBar.ButtonData.OK_DONE);
        ButtonType anyway = new ButtonType(tr("run.shellWarning.runAnyway"), ButtonBar.ButtonData.OTHER);
        alert.getButtonTypes()
                .setAll(canFix ? List.of(convert, anyway, ButtonType.CANCEL) : List.of(anyway, ButtonType.CANCEL));
        alert.setOnHidden(e -> {
            ButtonType chosen = alert.getResult();
            if (chosen == convert) {
                fix(buffer, hazards);
                run.run();
            } else if (chosen == anyway) {
                run.run();
            }
        });
        alert.show();
        return alert;
    }
}
