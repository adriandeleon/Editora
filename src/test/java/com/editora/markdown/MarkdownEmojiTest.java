package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomNode;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownEmojiTest {

    private static final List<org.commonmark.Extension> EXT =
            List.of(MarkdownEmoji.extension(), AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXT).build();

    private static List<String> emoji(String md) {
        List<String> out = new ArrayList<>();
        PARSER.parse(md).accept(new AbstractVisitor() {
            @Override
            public void visit(CustomNode node) {
                if (node instanceof MarkdownEmoji.Emoji e) {
                    out.add(e.getShortcode() + "=" + e.getEmoji());
                }
                visitChildren(node);
            }
        });
        return out;
    }

    private static String text(String md) {
        return TextContentRenderer.builder().extensions(EXT).build().render(PARSER.parse(md));
    }

    @Test
    void aKnownShortcodeIsAnEmoji() {
        assertEquals(List.of("rocket=🚀"), emoji("Ship it :rocket: today"));
        assertEquals(List.of("+1=👍", "-1=👎"), emoji(":+1: or :-1:"));
        assertEquals(List.of("smile=😄", "tada=🎉"), emoji(":smile::tada:"), "back to back");
        assertEquals(List.of("100=💯"), emoji("(:100:)"));
        assertEquals(List.of("warning=⚠️"), emoji("# Heading :warning:\n"));
        assertEquals(List.of("bug=🐛"), emoji("- [ ] fix :bug:\n"));
        assertEquals("Ship it 🚀 today", text("Ship it :rocket: today").strip());
    }

    @Test
    void anythingElseBetweenColonsStaysText() {
        assertEquals(List.of(), emoji("An :unknown_name: stays."));
        assertEquals(":unknown_name:", text(":unknown_name:").strip());
        assertEquals(List.of(), emoji("At 12:30:45 sharp, ratio 1:100:2."));
        assertEquals(List.of(), emoji("a:rocket:b and std::rocket::x"), "inside a word or after a colon");
        assertEquals(List.of(), emoji(":Rocket: and :rocket : and : rocket:"));
        assertEquals(List.of(), emoji("`:rocket:` and\n\n```\n:rocket:\n```\n"), "never in code");
        assertEquals(List.of(), emoji("https://example.org:8080/:rocket"));
        assertEquals(List.of(), emoji("a lone : colon and :: two"));
    }

    @Test
    void theTableIsGithubsCommonSet() {
        Map<String, String> table = MarkdownEmoji.table();
        assertTrue(table.size() >= 400, "a few hundred names: " + table.size());
        assertEquals("🚀", MarkdownEmoji.lookup("rocket"));
        assertEquals(MarkdownEmoji.lookup("+1"), MarkdownEmoji.lookup("thumbsup"), "aliases share a character");
        assertNull(MarkdownEmoji.lookup("no_such_emoji"));
        assertNull(MarkdownEmoji.lookup(null));
        String previous = "";
        for (Map.Entry<String, String> e : table.entrySet()) {
            assertTrue(e.getKey().compareTo(previous) > 0, "sorted, no duplicates: " + e.getKey());
            previous = e.getKey();
            assertFalse(e.getKey().isEmpty() || e.getKey().length() > MarkdownEmoji.MAX_NAME);
            for (char c : e.getKey().toCharArray()) {
                assertTrue(MarkdownEmoji.isNameChar(c), e.getKey());
            }
            assertFalse(e.getValue().isBlank(), e.getKey());
            assertTrue(e.getValue().codePointAt(0) > 0x7F, e.getKey() + " maps to a symbol, not text");
        }
    }

    @Test
    void htmlGetsTheCharacter() {
        String html = HtmlRenderer.builder().extensions(EXT).build().render(PARSER.parse("Done :white_check_mark:"));
        assertEquals("<p>Done ✅</p>\n", html);
    }
}
