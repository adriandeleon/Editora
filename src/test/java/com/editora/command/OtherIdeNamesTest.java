package com.editora.command;

import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** IntelliJ's and VS Code's names for Editora's commands (fourth and fifth review EN4). */
class OtherIdeNamesTest {

    @Test
    void everyNamedCommandExistsAndGoToTestIsFound() {
        ResourceBundle messages = ResourceBundle.getBundle("com.editora.i18n.messages", java.util.Locale.ROOT);
        for (String id : OtherIdeNames.commandIds()) {
            assertTrue(messages.containsKey("command." + id), "no command " + id);
        }
        assertTrue(OtherIdeNames.of("nav.relatedFile").contains("Go to Test"));
        assertTrue(OtherIdeNames.of("lsp.copyQualifiedName").contains("Copy Reference"));
        assertTrue(OtherIdeNames.of("palette.show").contains("Find Action"));
    }
}
