package com.editora.ui;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hot code replace refused with no reason, said with its JDK reason (Java debug review 2, B102). */
class CodeReplaceJdkTest {

    @BeforeAll
    static void english() {
        Messages.init("en"); // run alone, the catalog is not loaded yet
    }

    @Test
    void aRefusalWithoutAReasonIsRecognised() {
        assertTrue(CodeReplaceJdk.reasonless("Failed to redefine classes: null"));
        assertTrue(CodeReplaceJdk.reasonless(""));
        assertTrue(CodeReplaceJdk.reasonless(null));
        assertFalse(CodeReplaceJdk.reasonless("Failed to redefine classes: add method not implemented"));
    }

    @Test
    void differentJavasAreTheReason() {
        String said = CodeReplaceJdk.explain("Failed to redefine classes: null", 25, 27);
        assertTrue(said != null && said.contains("25") && said.contains("27"), String.valueOf(said));
        assertNull(CodeReplaceJdk.explain("Failed to redefine classes: null", 25, 25));
        assertNull(CodeReplaceJdk.explain("Failed to redefine classes: null", null, 27));
        assertNull(CodeReplaceJdk.explain("schema change not implemented", 25, 27));
    }

    @Test
    void theProgramsJavaIsReadFromTheRuntimeChip() {
        assertEquals(25, CodeReplaceJdk.programMajor("Java 25.0.1 (Corretto)"));
        assertEquals(27, CodeReplaceJdk.programMajor("Java 27 (Corretto)"));
        assertNull(CodeReplaceJdk.programMajor("Python 3.13.1"));
        assertNull(CodeReplaceJdk.programMajor(""));
    }
}
