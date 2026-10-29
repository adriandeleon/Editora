package com.editora.text;

import java.util.Locale;

/**
 * How a language id ({@link LanguageRegistry}) reads to a person — in the status bar and the Problems tool
 * window. Proper nouns, deliberately untranslated. An id with no entry is shown with its first letter
 * upper-cased ({@code python} → {@code Python}). Pure.
 */
public final class LanguageNames {

    private LanguageNames() {}

    /** The display name for {@code id}; {@code ""} for a null or empty id. */
    public static String display(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        return switch (id) {
            case "java" -> "Java";
            case "javascript" -> "JavaScript";
            case "javascriptreact" -> "JavaScript (JSX)";
            case "typescript" -> "TypeScript";
            case "typescriptreact" -> "TypeScript (TSX)";
            case "pip-requirements" -> "pip requirements"; // the tool's own spelling: lower-case pip
            // Acronyms and names with their own casing; capitalising the first letter gave "Xml", "Json", "Cpp"
            // (XML review 2 C12).
            case "xml" -> "XML";
            case "json" -> "JSON";
            case "yaml" -> "YAML";
            case "html" -> "HTML";
            case HtmlElements.TEMPLATE_LANGUAGE -> "HTML Template";
            case "css" -> "CSS";
            case "sql" -> "SQL";
            case "toml" -> "TOML";
            case "csv" -> "CSV";
            case "tsv" -> "TSV";
            case "ini" -> "INI";
            case "php" -> "PHP";
            case "http" -> "HTTP";
            case "graphql" -> "GraphQL";
            case "dot" -> "DOT";
            case "c" -> "C";
            case "cpp" -> "C++";
            case "csharp" -> "C#";
            case "powershell" -> "PowerShell";
            case "plantuml" -> "PlantUML";
            case "proto" -> "Protocol Buffers";
            case "batchfile" -> "Batch";
            case "systemd" -> "systemd"; // its own spelling, lower-case
            case "desktop" -> "Desktop Entry";
            default -> id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        };
    }
}
