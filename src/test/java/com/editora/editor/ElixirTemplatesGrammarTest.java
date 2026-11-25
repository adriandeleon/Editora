package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.text.HtmlElements;
import com.editora.text.LanguageRegistry;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phoenix's templates are highlighted as what they are: EEx ({@code .html.eex}, {@code .leex}) and HEEx
 * ({@code .heex}) colour their Elixir as Elixir and their HTML as HTML, and an Elixir file's {@code ~H} and {@code ~L}
 * sigils, which include EEx, colour the Elixir inside them too (it stayed plain while EEx was not bundled). Each is an
 * HTML template to the rest of Editora, as ERB is.
 */
class ElixirTemplatesGrammarTest {

    /** Each non-blank token of {@code text} as {@code text|its innermost scope's full list}. */
    private static List<String> tokens(String fileName, String text) {
        IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
        assertNotNull(grammar, fileName);
        IStateStack state = null;
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            var result = grammar.tokenizeLine(line + "\n", state, Duration.ofSeconds(5));
            state = result.getRuleStack();
            for (IToken t : result.getTokens()) {
                int start = Math.min(t.getStartIndex(), line.length());
                int end = Math.min(t.getEndIndex(), line.length());
                if (end > start && !line.substring(start, end).isBlank()) {
                    out.add(line.substring(start, end) + "|" + String.join(" ", t.getScopes()));
                }
            }
        }
        return out;
    }

    private static boolean has(List<String> tokens, String text, String scope) {
        return tokens.stream().anyMatch(t -> t.startsWith(text + "|") && t.contains(scope));
    }

    @Test
    void anHtmlEexTemplateColoursItsElixirAndItsHtml() {
        List<String> t = tokens("index.html.eex", "<p class=\"x\"><%= Enum.map(@items, &to_string/1) %></p>");
        assertTrue(has(t, "p", "entity.name.tag"), t.toString());
        assertTrue(has(t, "<%=", "text.html.elixir"), t.toString());
        assertTrue(t.stream().anyMatch(s -> s.contains("source.elixir")), "the Elixir is Elixir's: " + t);
        assertTrue(has(t, "items", "source.elixir"), t.toString());
    }

    @Test
    void aHeexTemplateColoursItsAssignsComponentsAndAttributes() {
        List<String> t = tokens(
                "show.html.heex", "<.button :if={@admin} phx-click=\"save\">{@label}</.button>\n<%= @count + 1 %>");
        assertTrue(t.stream().allMatch(s -> s.contains("source.heex")), t.toString());
        assertTrue(has(t, "admin", "source.elixir"), "an attribute's {expression} is Elixir: " + t);
        assertTrue(has(t, "label", "source.elixir"), "a body's {expression} is Elixir: " + t);
        assertTrue(has(t, "count", "source.elixir"), "<%= %> too: " + t);
        assertTrue(t.stream().anyMatch(s -> s.startsWith("button|") || s.startsWith(".button|")), t.toString());
    }

    @Test
    void anElixirSigilsTemplateColoursItsElixirNow() {
        List<String> t =
                tokens("page_live.ex", "def render(assigns) do\n  ~L\"\"\"\n  <p><%= @name %></p>\n  \"\"\"\nend");
        assertTrue(
                has(t, "name", "sigil.leex meta.embedded.line.elixir source.elixir"),
                "the Elixir in ~L is Elixir's (EEx is bundled): " + t);
        assertTrue(has(t, "<%=", "punctuation.section.embedded.begin.elixir"), t.toString());
    }

    @Test
    void elixirTemplatesAreHtmlTemplatesToTheRestOfEditora() {
        for (String name : List.of("index.html.eex", "live.html.leex", "show.html.heex", "card.heex")) {
            assertEquals(HtmlElements.TEMPLATE_LANGUAGE, LanguageRegistry.forFileName(name), name);
        }
        assertEquals("elixir", LanguageRegistry.forFileName("app.ex"));
    }
}
