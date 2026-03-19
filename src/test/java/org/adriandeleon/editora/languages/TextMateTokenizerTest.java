package org.adriandeleon.editora.languages;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMateTokenizerTest {

    @Test
    void supportsNestedRepositoryIncludesAfterRuleExpansionCaching() {
        TextMateGrammar grammar = TextMateGrammar.fromPlist(Map.of(
                "name", "Demo",
                "scopeName", "source.demo",
                "patterns", List.of(Map.of("include", "#statements")),
                "repository", Map.of(
                        "statements", Map.of("patterns", List.of(
                                Map.of(
                                        "name", "keyword.control.demo",
                                        "match", "\\b(?:if|else)\\b"
                                ),
                                Map.of(
                                        "name", "string.quoted.demo",
                                        "begin", "\"",
                                        "end", "\"",
                                        "patterns", List.of(Map.of("include", "#escapes"))
                                )
                        )),
                        "escapes", Map.of("patterns", List.of(
                                Map.of(
                                        "name", "constant.character.escape.demo",
                                        "match", "\\\\."
                                )
                        ))
                )
        ));

        String source = "if \"a\\nb\" else";
        StyleSpans<Collection<String>> highlighting = TextMateTokenizer.computeHighlighting(grammar, source);

        assertEquals(source.length(), highlighting.length());
        assertTrue(hasStyle(highlighting, "keyword"));
        assertTrue(hasStyle(highlighting, "string"));
        assertTrue(hasStyle(highlighting, "constant"));
    }

    private boolean hasStyle(StyleSpans<Collection<String>> spans, String styleClass) {
        for (StyleSpan<Collection<String>> span : spans) {
            Collection<String> styles = span.getStyle();
            if (styles != null && styles.contains(styleClass)) {
                return true;
            }
        }
        return false;
    }
}


