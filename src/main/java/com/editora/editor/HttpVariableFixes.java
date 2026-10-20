package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.MenuItem;

import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The quick fixes of a {@code .http} buffer's unresolved-variable marks ("Use environment dev"): which apply at a spot,
 * the hint the squiggle tooltip adds, and the right-click items. Each fix's token is a {@link Runnable}; the fixes
 * themselves come from the HTTP Client through {@link HttpHooks#variableFixes()}.
 */
final class HttpVariableFixes {

    private HttpVariableFixes() {}

    /** The fixes for the marks at {@code offset} of {@code view}; empty when there are none (or the marks are off). */
    static List<CodeAction> at(HttpVariableMarks marks, HttpHooks hooks, @Nullable CodeArea view, int offset) {
        if (!marks.enabled() || view == null) {
            return List.of();
        }
        var pos = view.offsetToPosition(Math.max(0, Math.min(offset, view.getLength())), TwoDimensional.Bias.Forward);
        List<MarkdownLint.Diagnostic> hits = marks.at(pos.getMajor(), pos.getMinor());
        return hits.isEmpty() ? List.of() : hooks.variableFixes().apply(hits);
    }

    /**
     * The line the squiggle tooltip adds under marks that have a fix — "Right-click: Use environment dev" — so the fix
     * is found from the mark itself; null for other marks.
     */
    static @Nullable String hint(HttpVariableMarks marks, HttpHooks hooks, List<MarkdownLint.Diagnostic> hits) {
        if (!marks.enabled()) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        for (CodeAction fix : hooks.variableFixes().apply(hits)) {
            titles.add(fix.title());
        }
        return titles.isEmpty() ? null : tr("http.fix.hint", String.join(" · ", titles));
    }

    /** One right-click item per fix. */
    static List<MenuItem> menuItems(List<CodeAction> fixes) {
        List<MenuItem> items = new ArrayList<>(fixes.size());
        for (CodeAction fix : fixes) {
            MenuItem item = new MenuItem(fix.title(), MenuIcons.codeAction());
            item.setOnAction(e -> apply(fix));
            items.add(item);
        }
        return items;
    }

    /** Applies a fix the code-action popup or a menu chose (null: nothing chosen). */
    static void apply(CodeAction chosen) {
        if (chosen != null && chosen.token() instanceof Runnable run) {
            run.run();
        }
    }
}
