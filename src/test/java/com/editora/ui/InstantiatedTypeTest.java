package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where Go to Definition's answer is checked, and what a paste-imports edit is (thirteenth review BK5, AK4). */
class InstantiatedTypeTest {

    @Test
    void onlyADiamondAnonymousCreationsLastSegmentIsChecked() {
        String text = "Object g = new Wrong12.Mid.Sink<>() {\n};\nSink<String> t = new Sink<String>() {};\n";
        int sink = text.indexOf("Sink<>");
        assertEquals(sink, InstantiatedType.diamondLast(text, sink + 2));
        assertEquals(-1, InstantiatedType.diamondLast(text, text.indexOf("Mid")), "an outer segment");
        assertEquals(-1, InstantiatedType.diamondLast(text, text.indexOf("Sink<String> t")), "a declared type");
        assertEquals(-1, InstantiatedType.diamondLast(text, text.indexOf("Sink<String>()")), "explicit arguments");
    }

    /** jdtls names a member type by its $-path (fourteenth check RJ1). */
    @Test
    void aMemberTypesSimpleNameIsAfterItsLastDollar() {
        assertEquals("Sink", InstantiatedType.simple("Wrong12$Mid$Sink<T>"));
        assertEquals("Comparator", InstantiatedType.simple("Comparator<T>"));
    }

    @Test
    void aPasteImportsEditIsImportLinesOnly() {
        assertTrue(EditingCoordinator.importsOnly("import java.time.Instant;\n"));
        assertTrue(EditingCoordinator.importsOnly("\nimport static java.util.Map.*;\nimport java.util.List;\n"));
        assertFalse(EditingCoordinator.importsOnly("int x = 1;\n"));
    }
}
