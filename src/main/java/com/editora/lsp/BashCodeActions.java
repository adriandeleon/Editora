package com.editora.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * bash-language-server's quick fixes as Editora lists them (Bash review C10, B13):
 *
 * <ul>
 *   <li>Its fix is titled "Apply fix for SC2006" and nothing else, and two on one line look the same: the title
 *       becomes "Fix SC2006: Use $(...) notation instead of legacy backticks", from the diagnostic the action
 *       carries, with the column when two would still read alike.
 *   <li>5.8's "Disable ShellCheck rule SC2086 for this command / for the entire file" were listed as fixes: they are
 *       suppressions, under their own tag after the fixes ({@link #SUPPRESS_KIND}).
 *   <li>5.6 offers no suppression at all, so a finding could be silenced only by typing the directive: a "Disable
 *       SC2086 for this command" is made here from each ShellCheck diagnostic at the caret the server did not offer
 *       one for, inserting {@code # shellcheck disable=SC2086} above the line at its indent.
 * </ul>
 */
public final class BashCodeActions {

    /** The kind a suppression is listed under ({@link CodeActionKinds.Group#SUPPRESS}). */
    public static final String SUPPRESS_KIND = "quickfix.suppress";

    private static final Pattern APPLY_FIX = Pattern.compile("^Apply fix for (SC\\d+)\\s*$");
    private static final Pattern DISABLE = Pattern.compile("^Disable ShellCheck rule (SC\\d+)\\b");

    private BashCodeActions() {}

    /**
     * {@code items} (the server's actions for {@code uri}, whose text is {@code text}) with clearer titles, their
     * suppressions marked, and a suppression for each ShellCheck diagnostic in {@code context} it had none for.
     */
    public static List<LspManager.CodeActionItem> shape(
            List<LspManager.CodeActionItem> items,
            List<Diagnostic> context,
            String uri,
            @Nullable String text,
            Map<java.nio.file.Path, String> expected,
            long sentAt) {
        List<LspManager.CodeActionItem> out = new ArrayList<>(items.size() + context.size());
        Set<String> suppressed = new LinkedHashSet<>();
        Map<String, Integer> titles = new HashMap<>();
        List<String> retitled = new ArrayList<>();
        for (LspManager.CodeActionItem item : items) {
            retitled.add(fixTitle(item, false));
        }
        for (String t : retitled) {
            titles.merge(t, 1, Integer::sum);
        }
        for (int i = 0; i < items.size(); i++) {
            LspManager.CodeActionItem item = items.get(i);
            Matcher disable = DISABLE.matcher(item.title());
            if (disable.find()) {
                suppressed.add(disable.group(1));
                out.add(new LspManager.CodeActionItem(
                        item.title(), SUPPRESS_KIND, false, item.raw(), expected, sentAt));
                continue;
            }
            String title = titles.getOrDefault(retitled.get(i), 0) > 1 ? fixTitle(item, true) : retitled.get(i);
            out.add(
                    title.equals(item.title())
                            ? item
                            : new LspManager.CodeActionItem(
                                    title, item.kind(), item.preferred(), item.raw(), expected, sentAt));
        }
        Set<String> made = new LinkedHashSet<>();
        for (Diagnostic d : context) {
            String code = shellcheckCode(d);
            if (code == null || suppressed.contains(code) || d.getRange() == null) {
                continue;
            }
            int line = d.getRange().getStart().getLine();
            if (!made.add(code + '@' + line)) {
                continue;
            }
            CodeAction action = disableAction(uri, text, line, code, d);
            if (action != null) {
                out.add(new LspManager.CodeActionItem(
                        action.getTitle(), SUPPRESS_KIND, false, action, expected, sentAt));
            }
        }
        return out;
    }

    /**
     * The row's title for a ShellCheck fix: its rule and its diagnostic's message (with its column when {@code
     * withColumn}); any other action keeps its own. Pure.
     */
    static String fixTitle(LspManager.CodeActionItem item, boolean withColumn) {
        Matcher m = APPLY_FIX.matcher(item.title());
        if (!m.find()
                || !(item.raw() instanceof CodeAction action)
                || action.getDiagnostics() == null
                || action.getDiagnostics().isEmpty()) {
            return item.title();
        }
        Diagnostic d = action.getDiagnostics().get(0);
        var raw = d.getMessage();
        String text = raw == null
                ? ""
                : raw.isLeft()
                        ? raw.getLeft()
                        : raw.getRight() == null ? "" : raw.getRight().getValue();
        String message = text == null ? "" : text.strip().replaceFirst("\\.$", "");
        if (message.isEmpty()) {
            return item.title();
        }
        return withColumn && d.getRange() != null
                ? tr(
                        "lsp.codeAction.bashFixAt",
                        m.group(1),
                        message,
                        d.getRange().getStart().getCharacter() + 1)
                : tr("lsp.codeAction.bashFix", m.group(1), message);
    }

    /** A ShellCheck diagnostic's rule ({@code SC2086}), or null for any other. */
    static @Nullable String shellcheckCode(Diagnostic d) {
        if (d == null
                || d.getSource() == null
                || !d.getSource().toLowerCase(java.util.Locale.ROOT).contains("shellcheck")) {
            return null;
        }
        var code = d.getCode();
        if (code == null) {
            return null;
        }
        String c = code.isLeft() ? code.getLeft() : code.getRight() == null ? null : "SC" + code.getRight();
        return c != null && c.matches("SC\\d+") ? c : null;
    }

    /**
     * "Disable SC2086 for this command": {@code # shellcheck disable=SC2086} on a line of its own above {@code line},
     * at that line's indent; null when the line is not in {@code text}.
     */
    static @Nullable CodeAction disableAction(String uri, @Nullable String text, int line, String code, Diagnostic d) {
        String lineText = BashCompletions.lineBefore(text, line, Integer.MAX_VALUE);
        if (lineText == null) {
            return null;
        }
        int k = 0;
        while (k < lineText.length() && (lineText.charAt(k) == ' ' || lineText.charAt(k) == '\t')) {
            k++;
        }
        String indent = lineText.substring(0, k);
        Position at = new Position(line, 0);
        TextEdit insert = new TextEdit(new Range(at, at), indent + "# shellcheck disable=" + code + "\n");
        CodeAction action = new CodeAction(tr("lsp.codeAction.bashDisable", code));
        action.setKind(SUPPRESS_KIND);
        action.setDiagnostics(List.of(d));
        action.setEdit(new WorkspaceEdit(Map.of(uri, List.of(insert))));
        return action;
    }
}
