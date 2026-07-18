package com.editora.toolbar;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolbarCatalogTest {

    @Test
    void everyDefaultLayoutIdIsAKnownCatalogItem() {
        for (String tok : ToolbarCatalog.defaultLayout()) {
            if (!ToolbarCatalog.SEPARATOR.equals(tok)) {
                assertTrue(ToolbarCatalog.isKnownId(tok), "unknown default id: " + tok);
                assertNotNull(ToolbarCatalog.item(tok));
            }
        }
    }

    @Test
    void catalogItemIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (ToolbarCatalog.Item it : ToolbarCatalog.items()) {
            assertTrue(ids.add(it.id()), "duplicate id: " + it.id());
        }
    }

    @Test
    void everyItemHasAnIconKey() {
        for (ToolbarCatalog.Item it : ToolbarCatalog.items()) {
            assertNotNull(it.iconKey());
            assertFalse(it.iconKey().isBlank(), "blank iconKey for " + it.id());
        }
    }

    @Test
    void separatorIsNotAnItemId() {
        assertFalse(ToolbarCatalog.isKnownId(ToolbarCatalog.SEPARATOR));
    }

    @Test
    void defaultLayoutHasNoDuplicateItems() {
        List<String> items = ToolbarCatalog.defaultLayout().stream()
                .filter(ToolbarCatalog::isKnownId)
                .toList();
        assertEquals(items.size(), new HashSet<>(items).size());
    }

    @Test
    void recentIsTheOnlyCommandlessItem() {
        for (ToolbarCatalog.Item it : ToolbarCatalog.items()) {
            if (it.commandId() == null) {
                assertEquals("toolbar.recent", it.id());
            }
        }
    }
}
