package com.editora.lsp;

import java.util.List;

/**
 * jdtls's clean-ups — the ids its {@code java.cleanup.actions} preference takes, in the order Settings lists them.
 * {@code organizeImports} and {@code renameFileToType} are left out: Organize Imports has a setting of its own, and
 * jdtls never renames a file from a clean-up run. Pure.
 */
public final class JavaCleanups {

    private JavaCleanups() {}

    /** Every clean-up Settings offers, by jdtls id; the label key is {@code settings.javaCleanup.<id>}. */
    public static final List<String> ALL = List.of(
            "addOverride",
            "addDeprecated",
            "redundantModifiers",
            "redundantSuperCall",
            "redundantComparisonStatement",
            "redundantIfCondition",
            "redundantFallingThroughBlockEnd",
            "qualifyMembers",
            "qualifyStaticMembers",
            "invertEquals",
            "addFinalModifier",
            "instanceofPatternMatch",
            "useSwitchForInstanceofPattern",
            "switchExpression",
            "lambdaExpressionFromAnonymousClass",
            "lambdaExpression",
            "tryWithResource",
            "stringConcatToTextBlock",
            "renameUnusedLocalVariables");

    /** {@code chosen} as jdtls is sent it: only known ids, in {@link #ALL}'s order, once each. */
    public static List<String> known(List<String> chosen) {
        return ALL.stream().filter(id -> chosen != null && chosen.contains(id)).toList();
    }
}
