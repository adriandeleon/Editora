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
}
