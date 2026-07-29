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

    /**
     * A command-less item is a special widget the coordinator has to map to an existing node. Declaring one
     * without listing it here is invisible: the rebuild silently leaves it out and the control never appears
     * — which is exactly how the run-configuration selector went missing from the toolbar.
     */
    @Test
    void everyCommandlessItemIsADeclaredSpecialWidget() {
        for (ToolbarCatalog.Item it : ToolbarCatalog.items()) {
            if (it.commandId() == null) {
                assertTrue(
                        ToolbarCatalog.SPECIAL_WIDGET_IDS.contains(it.id()),
                        it.id() + " has no command and is not declared in SPECIAL_WIDGET_IDS, so the toolbar"
                                + " rebuild would drop it");
            }
        }
    }

    @Test
    void everyDeclaredSpecialWidgetIsACatalogItem() {
        for (String id : ToolbarCatalog.SPECIAL_WIDGET_IDS) {
            assertTrue(ToolbarCatalog.isKnownId(id), id + " is declared special but is not a catalog item");
        }
    }
}
