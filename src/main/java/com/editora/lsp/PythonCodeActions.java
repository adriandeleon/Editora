package com.editora.lsp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Python quick fixes Pyright does not give (Python review 2 E102). Pyright 1.1.411 answers a code-action request on
 * {@code "Path" is not defined}, an unused import or a type error with nothing, so the lightbulb and Code Actions were
 * empty on every Python problem. Added here, from the diagnostics at the caret:
 *
 * <ul>
 *   <li><b>Import X</b> for an undefined name: Pyright's own completion at the name, whose auto-import items carry
 *       the import edit ({@code additionalTextEdits}), one row per module that has it.
 *   <li><b>Remove unused import X</b> for an import Pyright fades (its {@code Unnecessary} hint).
 *   <li><b>Ignore on this line</b>: {@code # pyright: ignore[rule]} at the end of the line, or the rule added to
 *       the line's existing ignore comment.
 * </ul>
 */
public final class PythonCodeActions {

    /** The Python server's id ({@link LspServerRegistry}); basedpyright runs under it too. */
    public static final String SERVER_ID = "python";

    /** Undefined names asked about at most per request: each costs a completion round trip. */
    static final int MAX_NAMES = 3;

    /** Modules offered per undefined name. */
    static final int MAX_IMPORTS = 5;

    private static final Pattern UNDEFINED = Pattern.compile("^\"([A-Za-z_][A-Za-z0-9_]*)\" is not defined");
    private static final Pattern NOT_ACCESSED = Pattern.compile("^\"([^\"]+)\" is not accessed");
    private static final Pattern IGNORE_COMMENT = Pattern.compile("#\\s*pyright:\\s*ignore(\\[([^\\]]*)])?");
    private static final Pattern IMPORT_STATEMENT = Pattern.compile("^\\s*(import|from)\\s");

    private PythonCodeActions() {}

    /**
     * {@code items} (Pyright's own actions for {@code uri}) with the Python quick fixes for the {@code context}
     * diagnostics added, those of the {@code only} kinds when it is set. Completes with {@code items} as they are when
     * there is nothing to add.
     */
    static CompletableFuture<List<LspManager.CodeActionItem>> withQuickFixes(
            LanguageServerSession s,
            String uri,
            List<LspManager.CodeActionItem> items,
            List<Diagnostic> context,
            @Nullable List<String> only,
            Map<java.nio.file.Path, String> expected,
            long sentAt) {
        String text = s.documentSnapshot(uri);
        List<CodeAction> local = new ArrayList<>(localFixes(uri, text, context));
        List<CompletableFuture<List<CodeAction>>> imports = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Diagnostic d : context) {
            String name = undefinedName(d);
            if (name != null && wanted("quickfix", only) && names.size() < MAX_NAMES && names.add(name)) {
                imports.add(importActions(s, uri, name, d));
            }
        }
        return CompletableFuture.allOf(imports.toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> {
                    List<LspManager.CodeActionItem> out = new ArrayList<>(items);
                    for (CompletableFuture<List<CodeAction>> f : imports) {
                        List<CodeAction> found = f.getNow(List.of());
                        for (CodeAction a : found == null ? List.<CodeAction>of() : found) {
                            out.add(item(a, expected, sentAt));
                        }
                    }
                    for (CodeAction a : local) {
                        if (wanted(a.getKind(), only)) {
                            out.add(item(a, expected, sentAt));
                        }
                    }
                    return out;
                });
    }

    private static LspManager.CodeActionItem item(CodeAction a, Map<java.nio.file.Path, String> expected, long sentAt) {
        return new LspManager.CodeActionItem(
                a.getTitle(), a.getKind(), Boolean.TRUE.equals(a.getIsPreferred()), a, expected, sentAt);
    }

    /** Pure: whether an action of {@code kind} is wanted by a request for the {@code only} kinds (null: all). */
    static boolean wanted(String kind, @Nullable List<String> only) {
        if (only == null || only.isEmpty()) {
            return true;
        }
        for (String o : only) {
            if (CodeActionKinds.matches(kind, o)) {
                return true;
            }
        }
        return false;
    }

    /** Pure: the remove-import and ignore actions for {@code context} in {@code text} (no server round trip). */
    static List<CodeAction> localFixes(String uri, @Nullable String text, List<Diagnostic> context) {
        if (text == null) {
            return List.of();
        }
        List<CodeAction> out = new ArrayList<>();
        Set<String> made = new LinkedHashSet<>();
        for (Diagnostic d : context) {
            if (d == null || d.getRange() == null) {
                continue;
            }
            CodeAction remove = removeUnusedImport(uri, text, d);
            if (remove != null && made.add(remove.getTitle())) {
                out.add(remove);
            }
        }
        for (Diagnostic d : context) {
            if (d == null || d.getRange() == null) {
                continue;
            }
            CodeAction ignore = ignoreAction(uri, text, d);
            if (ignore != null
                    && made.add(
                            ignore.getTitle() + '@' + d.getRange().getStart().getLine())) {
                out.add(ignore);
            }
        }
        return out;
    }

    // --- Import X ----------------------------------------------------------------------------------------

    /** Pure: the name a {@code "X" is not defined} diagnostic is about, or null. */
    static @Nullable String undefinedName(@Nullable Diagnostic d) {
        if (d == null || d.getSeverity() == DiagnosticSeverity.Hint) {
            return null;
        }
        Matcher m = UNDEFINED.matcher(message(d));
        return m.find() ? m.group(1) : null;
    }

    /** Asks Pyright to complete {@code name} where it stands, and turns its auto-import items into actions. */
    private static CompletableFuture<List<CodeAction>> importActions(
            LanguageServerSession s, String uri, String name, Diagnostic d) {
        Position at = d.getRange().getEnd();
        return s.completion(uri, at)
                .thenCompose(result -> {
                    List<CompletionItem> candidates = importCandidates(items(result), name);
                    if (candidates.isEmpty()) {
                        CodeAction std = stdlibImport(uri, s.documentSnapshot(uri), name, d);
                        return CompletableFuture.completedFuture(std == null ? List.<CodeAction>of() : List.of(std));
                    }
                    List<CompletableFuture<CompletionItem>> resolved = new ArrayList<>();
                    for (CompletionItem c : candidates) {
                        boolean hasEdits = c.getAdditionalTextEdits() != null
                                && !c.getAdditionalTextEdits().isEmpty();
                        resolved.add(hasEdits ? CompletableFuture.completedFuture(c) : s.resolveCompletion(c));
                    }
                    return CompletableFuture.allOf(resolved.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> {
                                List<CodeAction> out = new ArrayList<>();
                                for (CompletableFuture<CompletionItem> f : resolved) {
                                    CodeAction a = importAction(uri, name, f.join(), d, out.isEmpty());
                                    if (a != null) {
                                        out.add(a);
                                    }
                                }
                                return out;
                            });
                })
                .exceptionally(t -> List.of());
    }

    private static List<CompletionItem> items(@Nullable Either<List<CompletionItem>, CompletionList> result) {
        if (result == null) {
            return List.of();
        }
        if (result.isLeft()) {
            return result.getLeft() == null ? List.of() : result.getLeft();
        }
        CompletionList list = result.getRight();
        return list == null || list.getItems() == null ? List.of() : list.getItems();
    }

    /**
     * Pure: Pyright's auto-import items for exactly {@code name}, at most {@link #MAX_IMPORTS}, in its order: those
     * with an import edit, or a module to import from ({@code labelDetails.description}) to resolve one.
     */
    static List<CompletionItem> importCandidates(List<CompletionItem> items, String name) {
        List<CompletionItem> out = new ArrayList<>();
        for (CompletionItem c : items) {
            if (c == null || !name.equals(c.getLabel())) {
                continue;
            }
            boolean hasEdits = c.getAdditionalTextEdits() != null
                    && !c.getAdditionalTextEdits().isEmpty();
            if (hasEdits || module(c) != null) {
                out.add(c);
                if (out.size() == MAX_IMPORTS) {
                    break;
                }
            }
        }
        return out;
    }

    /** "Import Path from pathlib": the item's import edit as an action, or null when it carries none. */
    static @Nullable CodeAction importAction(
            String uri, String name, @Nullable CompletionItem c, Diagnostic d, boolean preferred) {
        if (c == null
                || c.getAdditionalTextEdits() == null
                || c.getAdditionalTextEdits().isEmpty()) {
            return null;
        }
        String module = module(c);
        CodeAction action = new CodeAction(
                module == null
                        ? tr("lsp.codeAction.pythonImportBare", name)
                        : tr("lsp.codeAction.pythonImport", name, module));
        action.setKind("quickfix");
        action.setDiagnostics(List.of(d));
        action.setIsPreferred(preferred);
        action.setEdit(new WorkspaceEdit(Map.of(uri, List.copyOf(c.getAdditionalTextEdits()))));
        return action;
    }

    private static @Nullable String module(CompletionItem c) {
        var details = c.getLabelDetails();
        String description = details == null ? null : details.getDescription();
        return description == null || description.isBlank() ? null : description.strip();
    }

    /**
     * Pure: "Import Path from pathlib" (or "Import os") for a standard-library name Pyright had no import for: it
     * offers imports only from modules already in the file's import graph ({@link PythonStdlibNames}). Null when the
     * name is not one of those listed.
     */
    static @Nullable CodeAction stdlibImport(String uri, @Nullable String text, String name, Diagnostic d) {
        String module = PythonStdlibNames.moduleOf(name);
        if (text == null || (module == null && !PythonStdlibNames.MODULES.contains(name))) {
            return null;
        }
        TextEdit edit = importEdit(text, module, name);
        CodeAction action = new CodeAction(
                module == null
                        ? tr("lsp.codeAction.pythonImportBare", name)
                        : tr("lsp.codeAction.pythonImport", name, module));
        action.setKind("quickfix");
        action.setDiagnostics(List.of(d));
        action.setIsPreferred(true);
        action.setEdit(new WorkspaceEdit(Map.of(uri, List.of(edit))));
        return action;
    }

    /**
     * Pure: the edit that imports {@code name} from {@code module} ({@code import name} when {@code module} is null):
     * added to the file's one-line {@code from module import …} when it has one, else a statement of its own after
     * the imports at the top of the file (after its docstring and {@code __future__} imports when it has none).
     */
    static TextEdit importEdit(String text, @Nullable String module, String name) {
        List<com.editora.editops.PythonScan.Line> lines = com.editora.editops.PythonScan.scan(text);
        int afterHeader = 0; // the line a first import goes on
        int afterImports = -1; // the line after the last import of the top block
        boolean seenCode = false;
        for (int i = 0; i < lines.size() && !seenCode; i++) {
            var line = lines.get(i);
            if (!line.statement() || line.indentEnd() > line.start()) {
                continue;
            }
            int end = com.editora.editops.PythonScan.statementEnd(lines, i);
            String word = com.editora.editops.PythonScan.firstWord(text, line);
            String code = text.substring(line.indentEnd(), lines.get(end).end());
            if (word.equals("import") || word.equals("from")) {
                if (module != null
                        && code.stripTrailing()
                                .matches("from\\s+" + Pattern.quote(module) + "\\s+import\\s+[\\w ,]+")) {
                    int at = line.indentEnd() + code.stripTrailing().length();
                    return new TextEdit(new Range(position(lines, text, at), position(lines, text, at)), ", " + name);
                }
                if (code.startsWith("from __future__")) {
                    afterHeader = end + 1;
                } else {
                    afterImports = end + 1;
                }
            } else if (afterImports < 0 && i == afterHeader && isDocstring(text, line)) {
                afterHeader = end + 1;
            } else {
                seenCode = true;
            }
            i = end;
        }
        String statement = module == null ? "import " + name : "from " + module + " import " + name;
        int at = afterImports >= 0 ? afterImports : afterHeader;
        boolean codeFollows = at < lines.size() && !lines.get(at).blank() && afterImports < 0;
        Position pos = at < lines.size() ? new Position(at, 0) : endPosition(lines);
        String insert = at < lines.size() ? statement + "\n" + (codeFollows ? "\n" : "") : "\n" + statement + "\n";
        return new TextEdit(new Range(pos, pos), insert);
    }

    private static boolean isDocstring(String text, com.editora.editops.PythonScan.Line line) {
        int i = line.indentEnd();
        while (i < line.end() && i < line.indentEnd() + 2 && Character.isLetter(text.charAt(i))) {
            i++;
        }
        return i < line.end() && (text.charAt(i) == '"' || text.charAt(i) == '\'');
    }

    private static Position position(List<com.editora.editops.PythonScan.Line> lines, String text, int offset) {
        int line = com.editora.editops.PythonScan.lineAt(lines, offset);
        return new Position(line, offset - lines.get(line).start());
    }

    private static Position endPosition(List<com.editora.editops.PythonScan.Line> lines) {
        var last = lines.get(lines.size() - 1);
        return new Position(lines.size() - 1, last.end() - last.start());
    }

    // --- Remove unused import --------------------------------------------------------------------------

    /**
     * Pure: "Remove unused import X" for Pyright's {@code "X" is not accessed} hint on an import statement: the name
     * and its comma go, or the whole statement when it imports nothing else. Null for anything else, and for forms
     * not safe to edit by text (a line continued with a backslash, a statement split over lines it cannot read).
     */
    static @Nullable CodeAction removeUnusedImport(String uri, String text, Diagnostic d) {
        if (d.getTags() == null || !d.getTags().contains(DiagnosticTag.Unnecessary)) {
            return null;
        }
        Matcher m = NOT_ACCESSED.matcher(message(d));
        Range r = d.getRange();
        if (!m.find() || r.getStart().getLine() != r.getEnd().getLine()) {
            return null;
        }
        List<String> lines = text.lines().toList();
        int line = r.getStart().getLine();
        if (line >= lines.size()) {
            return null;
        }
        String lineText = lines.get(line);
        if (lineText.stripTrailing().endsWith("\\")) {
            return null;
        }
        List<TextEdit> edits;
        if (IMPORT_STATEMENT.matcher(lineText).find()) {
            edits = removeFromImportLine(lines, line, r.getStart().getCharacter());
        } else {
            edits = removeFromParenthesizedImport(lines, line);
        }
        if (edits == null) {
            return null;
        }
        CodeAction action = new CodeAction(tr("lsp.codeAction.pythonRemoveImport", m.group(1)));
        action.setKind("quickfix");
        action.setDiagnostics(List.of(d));
        action.setEdit(new WorkspaceEdit(Map.of(uri, edits)));
        return action;
    }

    /** The edit removing the item at {@code column} of a one-line {@code import a, b} / {@code from m import a, b}. */
    private static @Nullable List<TextEdit> removeFromImportLine(List<String> lines, int line, int column) {
        String text = lines.get(line);
        int code = codeEnd(text);
        int listStart = importListStart(text);
        if (listStart < 0 || column < listStart || column > code) {
            return null;
        }
        String body = text.substring(listStart, code);
        int open = body.indexOf('(');
        int close = body.lastIndexOf(')');
        if (open >= 0 && close < 0) {
            return null; // a parenthesized list that goes on below: the name is on this line, the rest is not read
        }
        int from = listStart + (open >= 0 ? open + 1 : 0);
        int to = listStart + (close >= 0 ? close : body.length());
        List<int[]> spans = items(text, from, to);
        int at = -1;
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i)[0] <= column && column <= spans.get(i)[1]) {
                at = i;
            }
        }
        if (at < 0) {
            return null;
        }
        if (spans.size() == 1) {
            return List.of(deleteLines(lines, line, line));
        }
        int start;
        int end;
        if (at < spans.size() - 1) {
            start = spans.get(at)[0];
            end = spans.get(at + 1)[0];
        } else {
            start = spans.get(at - 1)[1];
            end = spans.get(at)[1];
        }
        return List.of(new TextEdit(new Range(new Position(line, start), new Position(line, end)), ""));
    }

    /**
     * The edit removing an item that sits on a line of its own in {@code from m import (\n a,\n b,\n)}: that line,
     * or the whole statement when the item is its only one.
     */
    private static @Nullable List<TextEdit> removeFromParenthesizedImport(List<String> lines, int line) {
        String item = lines.get(line).strip();
        if (!item.matches("[A-Za-z_][\\w.]*(\\s+as\\s+[A-Za-z_]\\w*)?\\s*,?\\s*(#.*)?")) {
            return null;
        }
        int first = line - 1;
        while (first >= 0 && !lines.get(first).contains("(")) {
            if (lines.get(first).contains(")")
                    || IMPORT_STATEMENT.matcher(lines.get(first)).find()) {
                return null;
            }
            first--;
        }
        if (first < 0 || !lines.get(first).stripLeading().startsWith("from ")) {
            return null;
        }
        int last = line + 1;
        while (last < lines.size() && !lines.get(last).contains(")")) {
            last++;
        }
        if (last >= lines.size()) {
            return null;
        }
        int names = 0;
        for (int i = first; i <= last; i++) {
            String t = lines.get(i);
            int s = i == first ? t.indexOf('(') + 1 : 0;
            int e = i == last ? t.indexOf(')') : codeEnd(t);
            if (s <= e) {
                names += items(t, s, Math.max(s, Math.min(e, codeEnd(t)))).size();
            }
        }
        return List.of(names <= 1 ? deleteLines(lines, first, last) : deleteLines(lines, line, line));
    }

    /** Spans {@code [start, end)} of the comma-separated items between {@code from} and {@code to}, blanks trimmed. */
    private static List<int[]> items(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            int comma = text.indexOf(',', i);
            int end = comma < 0 || comma > to ? to : comma;
            int s = i;
            int e = end;
            while (s < e && Character.isWhitespace(text.charAt(s))) s++;
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (s < e) {
                out.add(new int[] {s, e});
            }
            i = end + 1;
        }
        return out;
    }

    /** Where the imported names start: after {@code import } (of a {@code from m import} too), or -1. */
    private static int importListStart(String text) {
        Matcher m = Pattern.compile("^\\s*(from\\s+[\\w.]+\\s+)?import\\s+").matcher(text);
        return m.find() ? m.end() : -1;
    }

    /** The column a line's code ends at: before its {@code #} comment (none of these lines hold a string). */
    private static int codeEnd(String text) {
        int hash = text.indexOf('#');
        return hash < 0 ? text.length() : hash;
    }

    /** Deletes whole lines {@code first..last}, with their line break (or the break before them, at the end). */
    private static TextEdit deleteLines(List<String> lines, int first, int last) {
        Position start = new Position(first, 0);
        Position end = last + 1 < lines.size()
                ? new Position(last + 1, 0)
                : new Position(last, lines.get(last).length());
        if (last + 1 >= lines.size() && first > 0) {
            start = new Position(first - 1, lines.get(first - 1).length());
        }
        return new TextEdit(new Range(start, end), "");
    }

    // --- # pyright: ignore[rule] ----------------------------------------------------------------------

    /**
     * Pure: "Ignore reportX on this line" for a Pyright error, warning or information that names its rule: {@code #
     * pyright: ignore[reportX]} at the end of the line, or the rule added to the line's ignore comment. Null for a
     * hint, a diagnostic without a rule or over several lines, and a line that does not end in code (it ends inside
     * a string that goes on, or with a backslash), where a comment cannot go.
     */
    static @Nullable CodeAction ignoreAction(String uri, String text, Diagnostic d) {
        String rule = rule(d);
        Range r = d.getRange();
        if (rule == null
                || d.getSeverity() == DiagnosticSeverity.Hint
                || r.getStart().getLine() != r.getEnd().getLine()) {
            return null;
        }
        int line = r.getStart().getLine();
        int lineStart = lineStart(text, line);
        if (lineStart < 0) {
            return null;
        }
        int nl = text.indexOf('\n', lineStart);
        int lineEnd = nl < 0 ? text.length() : nl;
        String lineText = text.substring(lineStart, lineEnd);
        if (lineText.endsWith("\r")) {
            lineText = lineText.substring(0, lineText.length() - 1);
        }
        List<com.editora.editops.PythonScan.Line> scanned = com.editora.editops.PythonScan.scan(text);
        if (line >= scanned.size()
                || scanned.get(line).stringAtEnd()
                || scanned.get(line).continued()) {
            return null; // the line ends inside a string that goes on, or with a backslash: no comment can go there
        }
        TextEdit edit;
        Matcher m = IGNORE_COMMENT.matcher(lineText);
        if (m.find()) {
            if (m.group(1) == null) {
                return null; // a bare ignore already silences the whole line
            }
            String rules = m.group(2);
            for (String existing : rules.split(",")) {
                if (existing.strip().equals(rule)) {
                    return null;
                }
            }
            int at = m.end(2);
            String add = rules.isBlank() ? rule : ", " + rule;
            edit = new TextEdit(new Range(new Position(line, at), new Position(line, at)), add);
        } else {
            int end = lineText.stripTrailing().length();
            edit = new TextEdit(
                    new Range(new Position(line, end), new Position(line, lineText.length())),
                    "  # pyright: ignore[" + rule + "]");
        }
        CodeAction action = new CodeAction(tr("lsp.codeAction.pythonIgnore", rule));
        action.setKind(BashCodeActions.SUPPRESS_KIND);
        action.setDiagnostics(List.of(d));
        action.setEdit(new WorkspaceEdit(Map.of(uri, List.of(edit))));
        return action;
    }

    /** Pure: the Pyright rule a diagnostic names ({@code reportAttributeAccessIssue}), or null. */
    static @Nullable String rule(Diagnostic d) {
        var code = d.getCode();
        if (code == null) {
            return null;
        }
        String c = code.isLeft() ? code.getLeft() : null;
        return c != null && c.matches("report[A-Za-z]+") ? c : null;
    }

    private static int lineStart(String text, int line) {
        int at = 0;
        for (int i = 0; i < line; i++) {
            int nl = text.indexOf('\n', at);
            if (nl < 0) {
                return -1;
            }
            at = nl + 1;
        }
        return at;
    }

    private static String message(Diagnostic d) {
        var raw = d.getMessage();
        if (raw == null) {
            return "";
        }
        String text = raw.isLeft()
                ? raw.getLeft()
                : raw.getRight() == null ? null : raw.getRight().getValue();
        return text == null ? "" : text;
    }
}
