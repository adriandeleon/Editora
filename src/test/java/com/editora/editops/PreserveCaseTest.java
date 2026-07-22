package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreserveCaseTest {

    // --- the four case rules, in precedence order ---

    @Test
    void allUpperMatchUppercasesTheReplacement() {
        assertEquals("BAR", PreserveCase.apply("FOO", "bar"));
        assertEquals("BAR", PreserveCase.apply("FOO", "Bar"));
        assertEquals("BAR", PreserveCase.apply("FOO", "BAR"));
    }

    @Test
    void allLowerMatchLowercasesTheReplacement() {
        assertEquals("bar", PreserveCase.apply("foo", "BAR"));
        assertEquals("bar", PreserveCase.apply("foo", "Bar"));
    }

    @Test
    void leadingUpperMatchCapitalisesOnlyTheFirstCharacter() {
        assertEquals("Bar", PreserveCase.apply("Foo", "bar"));
        // the rest of the replacement is left exactly as typed
        assertEquals("BarBaz", PreserveCase.apply("Foo", "barBaz"));
        assertEquals("BAr", PreserveCase.apply("Foo", "bAr"));
    }

    @Test
    void leadingLowerMatchLowercasesOnlyTheFirstCharacter() {
        // "fOO" is neither all-upper nor all-lower, so the first-char rule applies
        assertEquals("bAR", PreserveCase.apply("fOO", "BAR"));
    }

    @Test
    void allUpperTakesPrecedenceOverTheFirstCharRule() {
        // a single uppercase letter is "all upper", so the whole replacement is uppercased
        assertEquals("BAR", PreserveCase.apply("A", "bar"));
    }

    // --- no case signal ---

    @Test
    void uncasedMatchLeavesTheReplacementAlone() {
        assertEquals("bAr", PreserveCase.apply("123", "bAr"));
        assertEquals("bAr", PreserveCase.apply("--", "bAr"));
        // a CJK letter is cased neither up nor down
        assertEquals("bAr", PreserveCase.apply("日本", "bAr"));
    }

    @Test
    void emptyOrNullInputsPassThrough() {
        assertEquals("bar", PreserveCase.apply("", "bar"));
        assertEquals("bar", PreserveCase.apply(null, "bar"));
        assertEquals("", PreserveCase.apply("FOO", ""));
        assertEquals(null, PreserveCase.apply("FOO", null));
    }

    // --- delimiter-aware, per-segment casing ---

    @Test
    void snakeCaseIsCasedPerSegment() {
        assertEquals("NEW_NAME", PreserveCase.apply("OLD_NAME", "new_name"));
        assertEquals("new_name", PreserveCase.apply("old_name", "NEW_NAME"));
        assertEquals("New_Name", PreserveCase.apply("Old_Name", "new_name"));
    }

    @Test
    void kebabCaseIsCasedPerSegment() {
        assertEquals("NEW-NAME", PreserveCase.apply("OLD-NAME", "new-name"));
        assertEquals("New-Name", PreserveCase.apply("Old-Name", "new-name"));
    }

    @Test
    void mixedSegmentsAreCasedIndependently() {
        // first segment all-upper, second leading-upper
        assertEquals("NEW_Name", PreserveCase.apply("OLD_Value", "new_name"));
    }

    @Test
    void segmentCountMismatchFallsBackToTheWholeStringRule() {
        // 2 segments vs 3 → per-segment mapping is not meaningful
        assertEquals("A_B_C", PreserveCase.apply("OLD_NAME", "a_b_c"));
    }

    @Test
    void separatorOnOneSideOnlyFallsBackToTheWholeStringRule() {
        assertEquals("NEWNAME", PreserveCase.apply("OLD_NAME", "newname"));
        assertEquals("NEW_NAME", PreserveCase.apply("OLDNAME", "new_name"));
    }

    @Test
    void underscoreWinsOverHyphenWhenBothAreShared() {
        // deterministic separator choice: '_' is checked first
        assertEquals("A-B_C-D", PreserveCase.apply("X-Y_Z-W", "a-b_c-d"));
    }

    @Test
    void emptySegmentsAreKept() {
        // leading/trailing separators must not change the segment count
        assertEquals("_BAR_", PreserveCase.apply("_FOO_", "_bar_"));
    }

    // --- Unicode ---

    @Test
    void accentedLettersAreCased() {
        assertEquals("ÉTÉ", PreserveCase.apply("FOO", "été")); // été → ÉTÉ
        assertEquals("Été", PreserveCase.apply("Foo", "été")); // été → Été
    }

    @Test
    void surrogatePairFirstCharacterIsNotSplit() {
        // DESERET SMALL LETTER LONG I (U+10428) uppercases to U+10400, both surrogate pairs
        String deseretLower = new String(Character.toChars(0x10428)) + "x";
        String deseretUpper = new String(Character.toChars(0x10400)) + "x";
        assertEquals(deseretUpper, PreserveCase.apply("Foo", deseretLower));
    }

    @Test
    void turkishDottedIIsNotProduced() {
        // Locale.ROOT, so 'i' must uppercase to 'I' regardless of the default locale
        assertEquals("I", PreserveCase.apply("FOO", "i"));
    }
}
