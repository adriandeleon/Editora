package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuItem;

import com.editora.config.Breakpoint;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.JavaLambdas;

import static com.editora.i18n.Messages.tr;

/**
 * Lambda breakpoints (VS Code's inline breakpoints, IntelliJ's lambda breakpoints): a line breakpoint in Java that
 * also stops — or only stops — inside lambdas that start on its line, each time a lambda's body runs, which a line
 * breakpoint does not: it stops once, when the line itself runs. The breakpoint keeps the lambdas by number on the
 * line ({@link Breakpoint#lambdas()}); {@link BreakpointTargets} turns them into the adapter's column breakpoints.
 */
final class LambdaBreakpoints {

    private final CoordinatorHost host;

    LambdaBreakpoints(CoordinatorHost host) {
        this.host = host;
    }

    /** Whether the caret's line of {@code b} is Java that starts a lambda (the editor menu then offers the command). */
    static boolean caretLineHasLambdas(EditorBuffer b) {
        var area = b.getFocusedArea();
        return BreakpointTargets.javaLanguage(b.getLanguage())
                && !JavaLambdas.on(area.getParagraph(area.getCurrentParagraph()).getText())
                        .isEmpty();
    }

    /**
     * Debug: Toggle Lambda Breakpoint — stops inside the lambda the caret is in (or the first on its line). On a line
     * with a breakpoint it adds that lambda to it, or takes it away; a breakpoint left stopping nowhere is removed.
     */
    void toggleAtCaret(EditorBuffer b) {
        var area = b.getFocusedArea();
        int line = area.getCurrentParagraph();
        if (!BreakpointTargets.javaLanguage(b.getLanguage())) {
            host.setStatus(tr("status.debug.lambdaJavaOnly"));
            return;
        }
        var found = JavaLambdas.on(area.getParagraph(line).getText());
        if (found.isEmpty()) {
            host.setStatus(tr("status.debug.noLambdaHere", line + 1));
            return;
        }
        toggle(b, line, JavaLambdas.numberAt(found, area.getCaretColumn()));
    }

    /** Adds lambda {@code number} of {@code line} to its breakpoint (making one), or takes it away; says which. */
    void toggle(EditorBuffer b, int line, int number) {
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        boolean added;
        if (bp == null) {
            b.toggleBreakpoint(line);
            mgr.setLambdas(line, List.of(number), true);
            added = true;
        } else if (bp.lambdas().contains(number)) {
            List<Integer> rest = new ArrayList<>(bp.lambdas());
            rest.remove(Integer.valueOf(number));
            if (rest.isEmpty() && bp.lambdasOnly()) {
                b.toggleBreakpoint(line); // it stopped nowhere else
            } else {
                mgr.setLambdas(line, rest, bp.lambdasOnly());
            }
            added = false;
        } else {
            List<Integer> more = new ArrayList<>(bp.lambdas());
            more.add(number);
            mgr.setLambdas(line, more, bp.lambdasOnly());
            added = true;
        }
        b.refreshGutterLine(line);
        host.setStatus(tr(added ? "status.debug.lambdaAdded" : "status.debug.lambdaRemoved", number, line + 1));
    }

    /**
     * The gutter menu's "Stop on the Line" and "Stop in Lambda n" choices for breakpoint {@code bp} on {@code line}:
     * none outside Java or on a line that starts no lambda.
     */
    List<MenuItem> choices(EditorBuffer b, int line, Breakpoint bp) {
        List<MenuItem> items = new ArrayList<>();
        var area = b.getArea();
        if (!BreakpointTargets.javaLanguage(b.getLanguage())
                || line < 0
                || line >= area.getParagraphs().size()) {
            return items;
        }
        var found = JavaLambdas.on(area.getParagraph(line).getText());
        if (found.isEmpty()) {
            return items;
        }
        var mgr = b.getBreakpointManager();
        CheckMenuItem onLine = new CheckMenuItem(tr("debug.gutter.stopOnLine"));
        onLine.setSelected(bp.stopsOnLine());
        onLine.setDisable(!bp.hasLambdas()); // it has to stop somewhere
        onLine.setOnAction(ev -> {
            mgr.setLambdas(line, bp.lambdas(), !onLine.isSelected());
            b.refreshGutterLine(line);
        });
        items.add(onLine);
        for (int i = 0; i < found.size(); i++) {
            int number = i + 1;
            CheckMenuItem one = new CheckMenuItem(
                    tr("debug.gutter.stopInLambda", number, found.get(i).preview()));
            one.setMnemonicParsing(false);
            one.setSelected(bp.lambdas().contains(number));
            one.setOnAction(ev -> {
                List<Integer> next = new ArrayList<>(bp.lambdas());
                if (one.isSelected()) {
                    next.add(number);
                } else {
                    next.remove(Integer.valueOf(number));
                }
                mgr.setLambdas(line, next, bp.lambdasOnly()); // the last one off: it stops on the line again
                b.refreshGutterLine(line);
            });
            items.add(one);
        }
        return items;
    }
}
