package com.editora.lsp;

import java.util.List;

/**
 * Pure mapping of a semantic-token <em>type name</em> + modifier bitset to a single CSS class string
 * (or {@code null} = "leave TextMate's lexical class in place"). The mapping is driven by the server's
 * <em>legend</em> (the ordered token-type / modifier name lists from {@code initialize}), never by
 * hardcoded indices — jdtls, rust-analyzer, etc. each publish a different legend.
 *
 * <p>Modifier emphasis is folded into the class name ({@code "sem-function sem-deprecated"}) so
 * {@code styles/syntax.css} stays a flat list of {@code .text.sem-*} rules with no per-modifier logic.
 * Side-effect-free and toolkit-free so it can be unit-tested directly.
 */
public final class SemanticTokenMapper {

    private SemanticTokenMapper() {}

    /**
     * The CSS class(es) for a token of type {@code type} with the given {@code modBits}, resolved against
     * {@code modLegend}; {@code null} for a type TextMate already classifies well (keyword/comment/
     * string/number/operator), so those fall through to the lexical highlight.
     */
    static String cssClass(String type, int modBits, List<String> modLegend) {
        String base = baseClass(type);
        if (base == null) {
            return null;
        }
        // deprecated wins — it's a visible strikethrough TextMate can never produce. Otherwise a
        // readonly/static symbol gets a subtle constant-like emphasis.
        if (hasMod(modBits, modLegend, "deprecated")) {
            return base + " sem-deprecated";
        }
        if (hasMod(modBits, modLegend, "readonly") || hasMod(modBits, modLegend, "static")) {
            return base.equals("sem-constant") ? base : base + " sem-constant"; // avoid "sem-constant sem-constant"
        }
        return base;
    }

    /** The base {@code sem-*} class for a semantic token type, or {@code null} to defer to TextMate. */
    private static String baseClass(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "parameter" -> "sem-parameter";
            // member (TS), field (C#), tomlTableKey/tomlArrayKey (taplo) are field/property-like.
            case "property",
                    "enumMember",
                    "event",
                    "recordComponent",
                    "member",
                    "field",
                    "tomlTableKey",
                    "tomlArrayKey" -> "sem-property";
            case "variable" -> "sem-variable";
            case "function", "method" -> "sem-function";
            // builtinType/typeAlias/union (rust-analyzer), concept (clangd) are type-like.
            case "class",
                    "interface",
                    "enum",
                    "struct",
                    "type",
                    "typeParameter",
                    "namespace",
                    "record",
                    "builtinType",
                    "typeAlias",
                    "union",
                    "concept" -> "sem-type";
            case "macro", "decorator", "annotation", "annotationMember" -> "sem-macro";
            case "constant" -> "sem-constant"; // C# emits a 'constant' type
            default -> null; // keyword/comment/string/number/regexp/operator/modifier — TextMate nails these
        };
    }

    /** Whether bit {@code name} (looked up in {@code modLegend}) is set in {@code bits}. Null-safe. */
    private static boolean hasMod(int bits, List<String> modLegend, String name) {
        if (modLegend == null) {
            return false;
        }
        int idx = modLegend.indexOf(name);
        return idx >= 0 && idx < 32 && (bits & (1 << idx)) != 0;
    }
}
