package com.editora.editor.http;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.MenuItem;

import com.editora.editor.CodeAction;
import com.editora.editor.EditorFx;
import com.editora.editor.MarkdownLintOverlay;
import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick fixes for an unresolved {@code {{variable}}}: the squiggle tooltip names them, the right-click menu offers
 * one item per fix, and choosing one runs it — nothing while the marks are off.
 */
@Tag("fx")
class HttpVariableFixesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static final MarkdownLint.Diagnostic HIT =
            new MarkdownLint.Diagnostic(0, 4, 8, "warning", "http.variable", "{{host}} is not defined");

    /** Hooks whose variable check reports {@link #HIT} and whose fix for it records what it applied. */
    private static HttpHooks hooks(List<String> applied) {
        return new HttpHooks(
                line -> {},
                () -> {},
                () -> {},
                () -> null,
                (text, sink) -> sink.accept(List.of(HIT)),
                null,
                hits -> hits.stream()
                        .map(h -> new CodeAction("Use environment dev", "quickfix", true, (Runnable)
                                () -> applied.add("dev: " + h.message())))
                        .toList());
    }

    @Test
    void theHintNamesTheFixesAndTheMenuAppliesThem() throws Exception {
        EditorFx.onFx(() -> {
            CodeArea area = new CodeArea("GET {{host}}/a\n");
            MarkdownLintOverlay overlay = new MarkdownLintOverlay(area);
            HttpVariableMarks marks = new HttpVariableMarks(() -> overlay, area::getText, () -> true, () -> false);
            List<String> applied = new ArrayList<>();
            HttpHooks hooks = hooks(applied);
            List<MarkdownLint.Diagnostic> hits = List.of(HIT);

            assertNull(HttpVariableFixes.hint(marks, hooks, hits), "the marks are off: no hint");

            marks.setValidator(hooks.variableLint());
            assertTrue(marks.enabled());
            assertEquals(tr("http.fix.hint", "Use environment dev"), HttpVariableFixes.hint(marks, hooks, hits));

            List<MenuItem> items =
                    HttpVariableFixes.menuItems(hooks.variableFixes().apply(hits));
            assertEquals(
                    List.of("Use environment dev"),
                    items.stream().map(MenuItem::getText).toList());
            assertNotNull(items.get(0).getGraphic(), "the code-action icon");
            items.get(0).fire();
            assertEquals(List.of("dev: {{host}} is not defined"), applied);

            HttpVariableFixes.apply(null);
            HttpVariableFixes.apply(new CodeAction("plain", "quickfix", false, "not runnable"));
            assertEquals(1, applied.size(), "nothing chosen, or a fix without an action: nothing runs");
        });
    }
}
