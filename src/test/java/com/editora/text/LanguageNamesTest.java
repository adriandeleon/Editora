package com.editora.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Language ids as people read them in the status bar and the Problems window. */
class LanguageNamesTest {

    @Test
    void multiWordAndOddlyCasedNamesReadNaturally() {
        // The status bar used to show "Pip-requirements".
        assertEquals("pip requirements", LanguageNames.display(LanguageRegistry.forFileName("requirements.txt")));
        assertEquals("JavaScript (JSX)", LanguageNames.display("javascriptreact"));
        assertEquals("TypeScript", LanguageNames.display("typescript"));
        assertEquals("Python", LanguageNames.display("python"));
        assertEquals("", LanguageNames.display(null));
    }

    /** Acronyms read as acronyms: the status bar and Problems said "Xml" (XML review 2 C12). */
    @Test
    void acronymsAndOwnCasingsAreKept() {
        assertEquals("XML", LanguageNames.display(LanguageRegistry.forFileName("pom.xml")));
        assertEquals("JSON", LanguageNames.display("json"));
        assertEquals("YAML", LanguageNames.display(LanguageRegistry.forFileName("ci.yml")));
        assertEquals("HTML", LanguageNames.display("html"));
        assertEquals("CSS", LanguageNames.display("css"));
        assertEquals("SQL", LanguageNames.display("sql"));
        assertEquals("TOML", LanguageNames.display("toml"));
        assertEquals("C++", LanguageNames.display(LanguageRegistry.forFileName("a.cpp")));
        assertEquals("C#", LanguageNames.display(LanguageRegistry.forFileName("a.cs")));
        assertEquals("GraphQL", LanguageNames.display("graphql"));
        assertEquals("PowerShell", LanguageNames.display("powershell"));
    }
}
