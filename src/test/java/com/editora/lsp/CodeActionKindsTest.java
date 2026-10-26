package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeActionKindsTest {

    @Test
    void generateHoldsTheGeneratorsAndAltEnterNotTheirQuickAssists() {
        assertTrue(CodeActionKinds.isJavaGenerator("source.generate.toString"));
        assertTrue(CodeActionKinds.isJavaGenerator("source.overrideMethods"));
        assertFalse(CodeActionKinds.isJavaGenerator("source.organizeImports"));
        assertFalse(CodeActionKinds.isJavaGenerator("source.sortMembers"));
        assertFalse(CodeActionKinds.isJavaGenerator("source.generate.finalModifiers"));

        var prompt = new org.eclipse.lsp4j.CodeAction("Generate Constructors...");
        prompt.setKind("quickassist");
        prompt.setCommand(
                new org.eclipse.lsp4j.Command("Generate Constructors...", "java.action.generateConstructorsPrompt"));
        assertTrue(CodeActionKinds.isJavaGeneratorAssist(
                new LspManager.CodeActionItem(prompt.getTitle(), "quickassist", false, prompt)));
        var getter = new org.eclipse.lsp4j.CodeAction("Generate Getter for 'shapes'");
        assertTrue(CodeActionKinds.isJavaGeneratorAssist(
                new LspManager.CodeActionItem(getter.getTitle(), "quickassist", false, getter)));
        var real = new org.eclipse.lsp4j.CodeAction("Convert to switch expression");
        assertFalse(CodeActionKinds.isJavaGeneratorAssist(
                new LspManager.CodeActionItem(real.getTitle(), "quickassist", false, real)));
        assertFalse(
                CodeActionKinds.isJavaGeneratorAssist(
                        new LspManager.CodeActionItem("Generate x", "quickfix", false, real)),
                "only quick assists");
    }

    /** What acts on the selection comes before what acts on the type: Extract Interface was the default (B11). */
    @org.junit.jupiter.api.Test
    void aSelectionsRefactoringsComeBeforeTheTypes() {
        var iface = new LspManager.CodeActionItem("Extract interface...", "refactor.extract.interface", false, "a");
        var tryCatch = new LspManager.CodeActionItem("Surround with try/catch", "refactor", false, "b");
        var variable = new LspManager.CodeActionItem(
                "Extract to local variable (replace all occurrences)", "refactor.extract.variable", false, "c");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(variable, tryCatch, iface),
                CodeActionKinds.arrange(java.util.List.of(iface, tryCatch, variable), i -> true));
    }
}
