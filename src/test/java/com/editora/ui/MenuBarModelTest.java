package com.editora.ui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the menu-bar table. A mistyped command id would compile, and the menu would simply render an item
 * that does nothing when clicked — silent, and only findable by clicking every entry by hand. Checking the
 * ids against the i18n catalog catches it at build time without needing a toolkit or a live registry, since
 * every registered command is required to have a {@code command.<id>} title there anyway.
 */
class MenuBarModelTest {

    private static Properties messages() throws Exception {
        Properties p = new Properties();
        try (InputStream in = MenuBarModelTest.class.getResourceAsStream("/com/editora/i18n/messages.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyReferencedCommandExists() throws Exception {
        Properties messages = messages();
        List<String> unknown = new ArrayList<>();
        for (String id : MenuBarModel.allCommandIds()) {
            if (!messages.containsKey("command." + id)) {
                unknown.add(id);
            }
        }
        assertTrue(unknown.isEmpty(), "menu entries naming commands that do not exist: " + unknown);
    }

    @Test
    void everyMenuTitleIsLocalized() throws Exception {
        Properties messages = messages();
        List<String> missing = new ArrayList<>();
        for (MenuBarModel.MenuSpec menu : MenuBarModel.menus()) {
            if (!messages.containsKey(menu.titleKey())) {
                missing.add(menu.titleKey());
            }
        }
        assertTrue(missing.isEmpty(), "menu titles with no i18n key: " + missing);
    }

    /** A command listed twice would appear in two menus, which is a mistake rather than a feature. */
    @Test
    void noCommandAppearsTwice() {
        List<String> all = new ArrayList<>();
        for (MenuBarModel.MenuSpec menu : MenuBarModel.menus()) {
            for (String e : menu.entries()) {
                if (!MenuBarModel.SEPARATOR.equals(e)) {
                    all.add(e);
                }
            }
        }
        assertEquals(all.size(), all.stream().distinct().count(), "a command is listed in more than one menu");
    }

    /** A leading, trailing or doubled separator renders as a stray line in the menu. */
    @Test
    void separatorsAreWellPlaced() {
        for (MenuBarModel.MenuSpec menu : MenuBarModel.menus()) {
            List<String> e = menu.entries();
            assertFalse(e.isEmpty(), menu.titleKey() + " is empty");
            assertFalse(MenuBarModel.SEPARATOR.equals(e.get(0)), menu.titleKey() + " starts with a separator");
            assertFalse(MenuBarModel.SEPARATOR.equals(e.get(e.size() - 1)), menu.titleKey() + " ends with a separator");
            for (int i = 1; i < e.size(); i++) {
                boolean doubled =
                        MenuBarModel.SEPARATOR.equals(e.get(i)) && MenuBarModel.SEPARATOR.equals(e.get(i - 1));
                assertFalse(doubled, menu.titleKey() + " has two separators in a row at " + i);
            }
        }
    }
}
