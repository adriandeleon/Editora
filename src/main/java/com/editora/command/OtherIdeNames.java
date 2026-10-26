package com.editora.command;

import java.util.List;
import java.util.Map;

/**
 * The names IntelliJ IDEA and VS Code give actions Editora has under another title, so the palette finds them by
 * either: "Optimize Imports" for Organize Imports, "Show Context Actions" for Code Actions (fourth review EN4), "Go to
 * Test" for Related File (fifth review EN4). Not localized: they are those products' English action names, which their
 * users type.
 */
public final class OtherIdeNames {

    private OtherIdeNames() {}

    private static final Map<String, List<String>> NAMES = Map.ofEntries(
            Map.entry("lsp.codeActions", List.of("Show Context Actions", "Quick Fix", "Show Intention Actions")),
            Map.entry("lsp.extractVariable", List.of("Introduce Variable")),
            Map.entry("lsp.extractConstant", List.of("Introduce Constant")),
            Map.entry("lsp.extractField", List.of("Introduce Field")),
            Map.entry("lsp.organizeImports", List.of("Optimize Imports")),
            Map.entry("lsp.formatDocument", List.of("Reformat Code")),
            Map.entry("lsp.gotoSymbol", List.of("Go to Class", "Go to Symbol in Workspace")),
            Map.entry("structure.jump", List.of("File Structure", "Go to Symbol in Editor")),
            Map.entry("lsp.peekDefinition", List.of("Quick Definition")),
            Map.entry("lsp.signatureHelp", List.of("Parameter Info", "Trigger Parameter Hints")),
            Map.entry("lsp.nextProblem", List.of("Next Highlighted Error", "Go to Next Problem")),
            Map.entry("lsp.previousProblem", List.of("Previous Highlighted Error", "Go to Previous Problem")),
            Map.entry("snippets.insert", List.of("Insert Live Template", "Live Template")),
            Map.entry("snippets.surround", List.of("Surround With Live Template")),
            Map.entry("lsp.findReferences", List.of("Find Usages", "Show Usages", "Find All References")),
            Map.entry("lsp.copyQualifiedName", List.of("Copy Reference", "Copy Qualified Name")),
            Map.entry("lsp.callHierarchy", List.of("Show Call Hierarchy", "Call Hierarchy")),
            Map.entry("lsp.typeHierarchy", List.of("Show Type Hierarchy", "Type Hierarchy")),
            Map.entry("nav.relatedFile", List.of("Go to Test", "Go to Test Subject")),
            Map.entry("palette.show", List.of("Find Action", "Show All Commands")),
            Map.entry("lsp.gotoDefinition", List.of("Go to Declaration or Usages", "Jump to Source")),
            Map.entry("lsp.hover", List.of("Quick Documentation", "Show Hover")),
            Map.entry("lsp.overrideMethods", List.of("Implement Methods", "Override Methods")),
            Map.entry("lsp.generate", List.of("Generate Code")),
            Map.entry("lsp.rename", List.of("Rename Symbol", "Rename Refactoring")),
            Map.entry("lsp.smartTypeCompletion", List.of("Type-Matching Completion")),
            Map.entry("run.context", List.of("Run Context Configuration")),
            Map.entry("view.foldAll", List.of("Collapse All", "Fold All Regions")),
            Map.entry("view.unfoldAll", List.of("Expand All", "Unfold All Regions")),
            Map.entry("nav.recentLocations", List.of("Recent Locations")),
            Map.entry("search.everywhere", List.of("Search Everywhere", "Go to Anything")));

    /** The commands that have other names (tests). */
    static java.util.Set<String> commandIds() {
        return NAMES.keySet();
    }

    /** The other products' names for {@code commandId}; empty when it has none or the same title. */
    public static List<String> of(String commandId) {
        return NAMES.getOrDefault(commandId, List.of());
    }
}
