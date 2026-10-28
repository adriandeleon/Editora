package com.editora.lsp;

import com.editora.lsp.XmlGrammarBinding.Binding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An XML document's existing grammar binding and its root element (XML review 2 C7, C8). */
class XmlGrammarBindingTest {

    @Test
    void anXsiAttributeOnTheRootIsABinding() {
        String note = "<?xml version=\"1.0\"?>\n<!-- a note -->\n<note xmlns:xsi=\"" + XmlGrammarBinding.XSI
                + "\"\n xsi:noNamespaceSchemaLocation=\"note.xsd\" id=\"n1\">\n<to>x</to></note>\n";
        assertEquals(new Binding("xsi:noNamespaceSchemaLocation", "note.xsd"), XmlGrammarBinding.existing(note));
        String ns = "<po:order xmlns:po=\"urn:po\" xmlns:i='" + XmlGrammarBinding.XSI
                + "' i:schemaLocation='urn:po  po.xsd'/>";
        assertEquals(new Binding("i:schemaLocation", "po.xsd"), XmlGrammarBinding.existing(ns), "any xsi prefix");
    }

    @Test
    void aDoctypeWithAnExternalDtdOrAnXmlModelIsABinding() {
        assertEquals(
                new Binding("DOCTYPE", "catalog.dtd"),
                XmlGrammarBinding.existing("<!DOCTYPE catalog SYSTEM \"catalog.dtd\">\n<catalog/>"));
        assertEquals(
                new Binding("DOCTYPE", "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd"),
                XmlGrammarBinding.existing("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" "
                        + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\"><html/>"));
        assertEquals(
                new Binding("xml-model", "addresses.rng"),
                XmlGrammarBinding.existing("<?xml version=\"1.0\"?>\n<?xml-model href=\"addresses.rng\"?>\n<a/>"));
    }

    @Test
    void anUnboundDocumentHasNone() {
        assertNull(XmlGrammarBinding.existing("<config><item>x</item></config>"));
        assertNull(XmlGrammarBinding.existing("<a href=\"x\" schemaLocation=\"y\"/>"), "no xsi prefix");
        assertNull(XmlGrammarBinding.existing("<!DOCTYPE c [<!ENTITY e \"x\">]><c>&e;</c>"), "an internal subset only");
        assertNull(XmlGrammarBinding.existing("<a><b xsi:noNamespaceSchemaLocation=\"b.xsd\"/></a>"), "not the root");
        assertNull(XmlGrammarBinding.existing(""));
        assertNull(XmlGrammarBinding.existing("not xml"));
    }

    @Test
    void theDoctypeAndTheRootNameAreFound() {
        String text = "﻿<?xml version=\"1.0\"?>\n<!DOCTYPE c [<!ENTITY e \"<x>\">]>\n<c a=\">\">&e;</c>";
        assertTrue(XmlGrammarBinding.hasDoctype(text));
        int[] root = XmlGrammarBinding.rootName(text);
        assertEquals("c", text.substring(root[0], root[1]));
        assertArrayEquals(new int[] {2, 1}, XmlGrammarBinding.lineColumn(text, root[0]));
        assertFalse(XmlGrammarBinding.hasDoctype("<config/>"));
        assertNull(XmlGrammarBinding.rootName("<!-- only a comment -->"));
    }
}
