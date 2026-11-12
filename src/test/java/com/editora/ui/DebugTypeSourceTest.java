package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.lsp.LspManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jump to Type Source: the type a debugger value names, and the symbol that declares it. */
class DebugTypeSourceTest {

    private static DebugTypeSource.TypeName parse(String type) {
        return DebugTypeSource.parse(type, null);
    }

    @Test
    void eachAdaptersWayOfNamingATypeComesDownToItsName() {
        assertEquals(new DebugTypeSource.TypeName("ArrayList", "java.util"), parse("java.util.ArrayList<String>"));
        assertEquals(new DebugTypeSource.TypeName("ArrayList", ""), parse("ArrayList<E>"));
        assertEquals(new DebugTypeSource.TypeName("Entry", "java.util.Map"), parse("java.util.Map$Entry"));
        assertEquals(new DebugTypeSource.TypeName("Order", "com.shop"), parse("com.shop.Order (id=12)"));
        assertEquals(new DebugTypeSource.TypeName("Circle", "main"), parse("*main.Circle"), "Go");
        assertEquals(new DebugTypeSource.TypeName("Stack", "main"), parse("main.Stack[int]"), "a Go generic");
        assertEquals(new DebugTypeSource.TypeName("Point", "app"), parse("app::Point"), "Rust");
        assertEquals(
                new DebugTypeSource.TypeName("Vec", "alloc::vec"), parse("alloc::vec::Vec<i32, alloc::alloc::Global>"));
        assertEquals(new DebugTypeSource.TypeName("Shape", ""), parse("const Shape &"), "C++");
        assertEquals(new DebugTypeSource.TypeName("vector", "std"), parse("std::vector<int, std::allocator<int> >"));
        assertEquals(new DebugTypeSource.TypeName("Node", ""), parse("struct Node *"));
        assertEquals(new DebugTypeSource.TypeName("Point", "shapes"), parse("shapes.Point"), "Python");
        assertEquals(
                new DebugTypeSource.TypeName("Order", ""),
                DebugTypeSource.parse("object", "Order {id: 3}"),
                "js-debug");
    }

    @Test
    void builtInTypesArraysAndAnonymousClassesNameNothingToOpen() {
        for (String type : List.of(
                "int",
                "boolean",
                "String[]",
                "[]int",
                "map[string]int",
                "[3]int",
                "i32",
                "&str",
                "str",
                "number",
                "undefined",
                "NoneType",
                "(i32, i32)",
                "Main$1",
                "",
                "object",
                "<lambda>")) {
            assertNull(parse(type), type);
        }
        assertNull(DebugTypeSource.parse("object", "{id: 3}"), "a plain object");
        assertNull(DebugTypeSource.parse(null, null));
    }

    @Test
    void theDeclarationIsTheTypeWhoseContainerAgreesAndTheProjectsComesFirst() {
        Path mine = Path.of("src", "List.java").toAbsolutePath();
        Path order = Path.of("src", "Order.java").toAbsolutePath();
        var ownList = new LspManager.WorkspaceSymbolMatch("List", "com.shop", "class", mine, 3, 13);
        var jdkList =
                new LspManager.WorkspaceSymbolMatch("List<E>", "java.util", "interface", null, 0, 0, "jdt://list");
        var method = new LspManager.WorkspaceSymbolMatch("Order", "com.shop.Order", "constructor", order, 9, 4);
        var orderClass = new LspManager.WorkspaceSymbolMatch("Order", "com.shop", "class", order, 5, 13);
        var all = List.of(method, jdkList, ownList, orderClass);

        assertEquals(jdkList, DebugTypeSource.best(new DebugTypeSource.TypeName("List", "java.util"), all));
        assertEquals(ownList, DebugTypeSource.best(new DebugTypeSource.TypeName("List", ""), all), "the project's own");
        assertEquals(orderClass, DebugTypeSource.best(new DebugTypeSource.TypeName("Order", "com.shop"), all));
        assertNull(DebugTypeSource.best(new DebugTypeSource.TypeName("Missing", ""), all));

        // java-debug names a JDK class without its package: the public one, not a private nested namesake.
        var nested = new LspManager.WorkspaceSymbolMatch(
                "ArrayList", "java.util.Arrays", "class", null, 0, 0, "jdt://arrays");
        var shaded = new LspManager.WorkspaceSymbolMatch(
                "ArrayList", "org.lib.shaded.util", "class", null, 0, 0, "jdt://shaded");
        var arrayList =
                new LspManager.WorkspaceSymbolMatch("ArrayList", "java.util", "class", null, 0, 0, "jdt://arraylist");
        assertEquals(
                arrayList,
                DebugTypeSource.best(
                        new DebugTypeSource.TypeName("ArrayList", ""), List.of(nested, shaded, arrayList)));
        assertTrue(DebugTypeSource.sameQualifier("crate::shapes", "shapes"));
        assertTrue(DebugTypeSource.sameQualifier("java.util.Map", "Map"));
        assertFalse(DebugTypeSource.sameQualifier("", "Map"));
        assertFalse(DebugTypeSource.sameQualifier("java.util", "com.shop"));
    }
}
