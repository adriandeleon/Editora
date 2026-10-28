package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** bash-language-server's completion list, ranked by its own categories (Bash review C11). */
class BashCompletionsTest {

    @org.junit.jupiter.api.BeforeAll
    static void catalog() {
        com.editora.i18n.Messages.init("en"); // real text, so a wrong argument shows
    }

    private static CompletionItem item(String label, CompletionItemKind kind, int type) {
        CompletionItem i = new CompletionItem(label);
        i.setKind(kind);
        JsonObject data = new JsonObject();
        data.addProperty("type", type);
        i.setData(data);
        return i;
    }

    /** What the server answers on `ca` (5.6.0, raw run): commands, a builtin, the keyword and the snippet. */
    private static List<CompletionItem> ca() {
        return new ArrayList<>(List.of(
                item("case", CompletionItemKind.Keyword, BashCompletions.RESERVED_WORD),
                item("cabextract", CompletionItemKind.Function, BashCompletions.EXECUTABLE),
                item("cancel", CompletionItemKind.Function, BashCompletions.EXECUTABLE),
                item("caller", CompletionItemKind.Function, BashCompletions.BUILTIN),
                item("cat_all", CompletionItemKind.Function, BashCompletions.SYMBOL),
                item("case", CompletionItemKind.Snippet, BashCompletions.SNIPPET)));
    }

    private static List<String> ranked(List<CompletionItem> items) {
        List<com.editora.completion.Completion> mapped =
                com.editora.completion.CompletionEngine.sortLspByRelevance(CompletionMapper.map(items));
        return mapped.stream().map(c -> c.label() + "/" + c.detail()).toList();
    }

    @Test
    void keywordsAndSnippetsComeFirstThenTheScriptsNamesThenBuiltinsThenCommands() {
        List<String> order = ranked(BashCompletions.adjust(ca(), "  ca"));
        assertEquals("case/" + tr("completion.bash.keyword"), order.get(0));
        assertTrue(order.get(1).startsWith("case/"), "the server's snippet: " + order);
        assertEquals("cat_all/" + tr("completion.bash.function"), order.get(2));
        assertEquals("caller/" + tr("completion.bash.builtin"), order.get(3));
        assertEquals("cabextract/" + tr("completion.bash.command"), order.get(4));
    }

    @Test
    void afterADollarOnlyVariablesAreOffered() {
        List<CompletionItem> items = new ArrayList<>(List.of(
                item("name", CompletionItemKind.Variable, BashCompletions.SYMBOL),
                item("nano", CompletionItemKind.Function, BashCompletions.EXECUTABLE),
                item("namei", CompletionItemKind.Function, BashCompletions.EXECUTABLE)));
        assertEquals(
                List.of("name"),
                BashCompletions.adjust(new ArrayList<>(items), "echo $na").stream()
                        .map(CompletionItem::getLabel)
                        .toList());
        assertEquals(
                1, BashCompletions.adjust(new ArrayList<>(items), "echo \"${na").size());
        assertEquals(3, BashCompletions.adjust(new ArrayList<>(items), "na").size(), "a command word: everything");
        assertEquals(3, BashCompletions.adjust(new ArrayList<>(items), null).size());
    }

    @Test
    void aServersOwnSortTextAndDetailAreKept() {
        CompletionItem i = item("x", CompletionItemKind.Variable, BashCompletions.SYMBOL);
        i.setSortText("zzz");
        i.setDetail("mine");
        BashCompletions.adjust(new ArrayList<>(List.of(i)), "");
        assertEquals("zzz", i.getSortText());
        assertEquals("mine", i.getDetail());
        CompletionItem bare = new CompletionItem("y");
        BashCompletions.adjust(new ArrayList<>(List.of(bare)), "");
        assertEquals(-1, BashCompletions.dataType(bare));
        assertNull(bare.getDetail());
    }

    @Test
    void theLineBeforeTheCaretIsCutFromTheText() {
        assertEquals("echo $na", BashCompletions.lineBefore("#!/bin/bash\necho $name\n", 1, 8));
        assertEquals("echo $name", BashCompletions.lineBefore("#!/bin/bash\necho $name", 1, 99));
        assertNull(BashCompletions.lineBefore("one line", 3, 0));
        assertNull(BashCompletions.lineBefore(null, 0, 0));
        assertTrue(BashCompletions.afterDollar("x=${"));
        assertFalse(BashCompletions.afterDollar("echo $(ls"));
    }
}
