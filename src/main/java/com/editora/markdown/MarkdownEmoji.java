package com.editora.markdown;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.commonmark.Extension;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Emoji shortcodes: {@code :rocket:} becomes an {@link Emoji} node carrying 🚀.
 *
 * <p>The names are GitHub's (the common few hundred, in {@code emoji.tsv} beside this class — one table for the
 * parser, the preview and completion after {@code :}). Only a known name between two colons is an emoji, and
 * the opening colon must not follow a letter or a digit, nor the closing one be followed by {@code ::}, so
 * {@code 12:30:45}, {@code a:b:c} and {@code std::move::x} stay text; an unknown {@code :name:} stays text too. Code is never scanned (CommonMark
 * claims code spans and blocks first). Pure.
 *
 * <p>The extension renders the node as its character for {@code HtmlRenderer} and
 * {@code TextContentRenderer}.
 */
public final class MarkdownEmoji {

    private MarkdownEmoji() {}

    /** One emoji written as a shortcode. */
    public static final class Emoji extends CustomNode {
        private final String shortcode;
        private final String emoji;

        public Emoji(String shortcode, String emoji) {
            this.shortcode = shortcode;
            this.emoji = emoji;
        }

        /** The name between the colons, e.g. {@code rocket}. */
        public String getShortcode() {
            return shortcode;
        }

        /** The character(s) it stands for. */
        public String getEmoji() {
            return emoji;
        }
    }

    /** Longest shortcode the parser looks for (the table's longest is well under this). */
    static final int MAX_NAME = 48;

    private static final class Table {
        static final Map<String, String> ALL = load();

        private static Map<String, String> load() {
            Map<String, String> map = new TreeMap<>();
            try (InputStream in = MarkdownEmoji.class.getResourceAsStream("emoji.tsv")) {
                if (in != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    for (String line; (line = reader.readLine()) != null; ) {
                        int tab = line.indexOf('\t');
                        if (tab > 0 && !line.startsWith("#")) {
                            map.put(
                                    line.substring(0, tab),
                                    line.substring(tab + 1).strip());
                        }
                    }
                }
            } catch (IOException e) {
                // no table: shortcodes stay text
            }
            return Collections.unmodifiableMap(map);
        }
    }

    /** Every shortcode and its emoji, sorted by name. Loaded once, on first use. */
    public static Map<String, String> table() {
        return Table.ALL;
    }

    /** The emoji for {@code shortcode} (without colons), or null when the name is not in the table. */
    public static String lookup(String shortcode) {
        return shortcode == null ? null : Table.ALL.get(shortcode);
    }

    /** Whether {@code c} can be part of a shortcode. Pure. */
    static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '+' || c == '-';
    }

    public static Extension extension() {
        return new EmojiExtension();
    }

    private static ParsedInline tryParse(InlineParserState state) {
        Scanner s = state.scanner();
        if (Character.isLetterOrDigit(s.peekPreviousCodePoint())) {
            return ParsedInline.none(); // a:b — a colon inside a word
        }
        Emoji emoji = shortcode(s);
        if (emoji == null) {
            return ParsedInline.none();
        }
        Position end = s.position();
        // "::" after it is a scope operator (std::move::x), unless what follows is another emoji (:a::b:).
        if (s.peek() == ':' && shortcode(s) == null) {
            return ParsedInline.none();
        }
        return ParsedInline.of(emoji, end);
    }

    /** Reads {@code :name:} at the scanner (on its opening colon); null — position undefined — when it is
     *  not a known shortcode. */
    private static Emoji shortcode(Scanner s) {
        s.next(); // the opening colon
        StringBuilder name = new StringBuilder();
        while (isNameChar(s.peek()) && name.length() <= MAX_NAME) {
            name.append(s.peek());
            s.next();
        }
        if (name.length() == 0 || name.length() > MAX_NAME || s.peek() != ':') {
            return null;
        }
        String emoji = lookup(name.toString());
        if (emoji == null) {
            return null;
        }
        s.next(); // the closing colon
        return new Emoji(name.toString(), emoji);
    }

    private static final class EmojiExtension
            implements Parser.ParserExtension,
                    HtmlRenderer.HtmlRendererExtension,
                    TextContentRenderer.TextContentRendererExtension {
        @Override
        public void extend(Parser.Builder builder) {
            builder.customInlineContentParserFactory(new InlineContentParserFactory() {
                @Override
                public Set<Character> getTriggerCharacters() {
                    return Set.of(':');
                }

                @Override
                public InlineContentParser create() {
                    return MarkdownEmoji::tryParse;
                }
            });
        }

        @Override
        public void extend(HtmlRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Emoji.class);
                }

                @Override
                public void render(Node node) {
                    context.getWriter().text(((Emoji) node).getEmoji());
                }
            });
        }

        @Override
        public void extend(TextContentRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Emoji.class);
                }

                @Override
                public void render(Node node) {
                    context.getWriter().write(((Emoji) node).getEmoji());
                }
            });
        }
    }
}
