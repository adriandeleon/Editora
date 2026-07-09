package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Makefile target extraction behind the Run gutter glyphs. */
class MakefileTargetsTest {

    private static List<String> names(String text) {
        return MakefileTargets.parse(text).stream()
                .map(MakefileTargets.Target::name)
                .toList();
    }

    @Test
    void extractsSimpleTargetsWithLineNumbers() {
        String mk = "all: build test\n\techo done\n\nbuild:\n\tcc -o app app.c\n";
        List<MakefileTargets.Target> targets = MakefileTargets.parse(mk);
        assertEquals(2, targets.size());
        assertEquals("all", targets.get(0).name());
        assertEquals(0, targets.get(0).line());
        assertEquals("build", targets.get(1).name());
        assertEquals(3, targets.get(1).line());
    }

    @Test
    void multipleTargetsOnOneLineEachCounted() {
        assertEquals(List.of("clean", "distclean"), names("clean distclean:\n\trm -rf build\n"));
    }

    @Test
    void skipsVariableAssignments() {
        // `:=`, `::=`, `=`, `?=`, `+=`, `!=`, and a value that itself contains a colon.
        String mk = "CC := gcc\nOBJ ::= a.o\nCFLAGS = -O2\nMODE ?= release\nLDFLAGS += -lm\n"
                + "SHELL != echo /bin/sh\nPATH2 = /a:/b:/c\n";
        assertTrue(names(mk).isEmpty(), names(mk).toString());
    }

    @Test
    void keepsTargetSpecificVariableTargets() {
        // `foo: CFLAGS := -O2` is a target-specific assignment — `foo` is still a real target.
        assertEquals(List.of("foo"), names("foo: CFLAGS := -O2\n"));
    }

    @Test
    void skipsRecipeCommentDirectivePatternAndDotTargets() {
        String mk = "# a comment\n"
                + "include config.mk\n"
                + "ifeq ($(OS),Windows)\n"
                + ".PHONY: all\n"
                + ".SUFFIXES:\n"
                + "%.o: %.c\n\tcc -c $<\n"
                + "$(TARGET): main.o\n"
                + "\t@echo recipe: not a target\n"
                + "real:\n\ttrue\n";
        assertEquals(List.of("real"), names(mk));
    }

    @Test
    void doubleColonRuleIsATarget() {
        assertEquals(List.of("all"), names("all:: one\nall:: two\n"));
    }

    @Test
    void dedupesRepeatedTargetKeepingFirstLine() {
        List<MakefileTargets.Target> targets = MakefileTargets.parse("build: a\n\tx\nbuild: b\n\ty\n");
        assertEquals(1, targets.size());
        assertEquals(0, targets.get(0).line());
    }

    @Test
    void handlesCrlfLineEndings() {
        assertEquals(List.of("all", "clean"), names("all:\r\n\techo hi\r\nclean:\r\n\trm x\r\n"));
    }

    @Test
    void emptyOrNullYieldsNoTargets() {
        assertTrue(MakefileTargets.parse("").isEmpty());
        assertTrue(MakefileTargets.parse(null).isEmpty());
    }

    @Test
    void ruleColonDetection() {
        assertEquals(3, MakefileTargets.indexOfRuleColon("all: deps"));
        assertEquals(3, MakefileTargets.indexOfRuleColon("all:: deps"));
        assertEquals(-1, MakefileTargets.indexOfRuleColon("CC := gcc"));
        assertEquals(-1, MakefileTargets.indexOfRuleColon("VAR = a:b"));
        assertEquals(-1, MakefileTargets.indexOfRuleColon("# all: deps"));
        assertEquals(-1, MakefileTargets.indexOfRuleColon("no colon here"));
    }

    @Test
    void runnableTargetFilter() {
        assertTrue(MakefileTargets.isRunnableTarget("build"));
        assertFalse(MakefileTargets.isRunnableTarget(".PHONY"));
        assertFalse(MakefileTargets.isRunnableTarget("%.o"));
        assertFalse(MakefileTargets.isRunnableTarget("$(TARGET)"));
        assertFalse(MakefileTargets.isRunnableTarget(""));
    }
}
