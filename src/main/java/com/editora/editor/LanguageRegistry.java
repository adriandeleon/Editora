package com.editora.editor;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.editor.LanguageRules.Rule;

/** Maps file extensions to {@link LanguageRules}, with a plaintext fallback. */
public final class LanguageRegistry {

    private static final LanguageRules PLAINTEXT = new LanguageRules("plaintext", List.of());

    private static final String STRING = "\"(?:\\\\.|[^\"\\\\])*\"";

    private static final LanguageRules JAVA = new LanguageRules("java", List.of(
            new Rule("COMMENT", "//[^\\n]*|/\\*(?:.|\\R)*?\\*/", "comment"),
            new Rule("STRING", STRING, "string"),
            new Rule("ANNOTATION", "@\\w+", "annotation"),
            new Rule("KEYWORD", "\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|"
                    + "const|continue|default|do|double|else|enum|extends|final|finally|float|for|"
                    + "goto|if|implements|import|instanceof|int|interface|long|native|new|package|"
                    + "private|protected|public|record|return|sealed|short|static|strictfp|super|"
                    + "switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|"
                    + "yield|true|false|null)\\b", "keyword"),
            new Rule("NUMBER", "\\b\\d+(?:\\.\\d+)?\\b", "number")));

    private static final LanguageRules JSON = new LanguageRules("json", List.of(
            new Rule("STRING", STRING, "string"),
            new Rule("NUMBER", "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b", "number"),
            new Rule("KEYWORD", "\\b(?:true|false|null)\\b", "keyword"),
            new Rule("PUNCT", "[{}\\[\\]:,]", "punct")));

    private static final LanguageRules XML = new LanguageRules("xml", List.of(
            new Rule("COMMENT", "<!--(?:.|\\R)*?-->", "comment"),
            new Rule("STRING", "\"[^\"]*\"|'[^']*'", "string"),
            new Rule("TAG", "</?[\\w:.-]+|/?>", "tag"),
            new Rule("ATTRIBUTE", "[\\w:.-]+(?=\\s*=)", "attribute")));

    private static final LanguageRules MARKDOWN = new LanguageRules("markdown", List.of(
            new Rule("CODE", "```(?:.|\\R)*?```|`[^`]*`", "code"),
            new Rule("HEADING", "(?m)^#{1,6}\\s.*$", "heading"),
            new Rule("LINK", "\\[[^\\]]*\\]\\([^)]*\\)", "link"),
            new Rule("BOLD", "\\*\\*[^*]+\\*\\*", "bold"),
            new Rule("ITALIC", "\\*[^*]+\\*", "italic")));

    private static final Map<String, LanguageRules> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", JAVA),
            Map.entry("json", JSON),
            Map.entry("xml", XML),
            Map.entry("fxml", XML),
            Map.entry("html", XML),
            Map.entry("md", MARKDOWN),
            Map.entry("markdown", MARKDOWN));

    private LanguageRegistry() {
    }

    public static LanguageRules forFileName(String fileName) {
        if (fileName == null) {
            return PLAINTEXT;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return PLAINTEXT;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXTENSION.getOrDefault(ext, PLAINTEXT);
    }

    public static LanguageRules plaintext() {
        return PLAINTEXT;
    }
}
