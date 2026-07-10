package com.editora.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure coverage of reading the module path out of a go.mod. */
class GoProjectTest {

    @Test
    void extractsTheModulePath() {
        String goMod = """
                module github.com/acme/widget

                go 1.22

                require golang.org/x/text v0.14.0
                """;
        assertEquals("github.com/acme/widget", GoProject.parse(goMod).moduleName());
    }

    @Test
    void noModuleLineYieldsNull() {
        assertNull(GoProject.parse("go 1.22\n").moduleName(), "a go.work has no module line");
        assertNull(GoProject.parse("").moduleName());
        assertNull(GoProject.parse(null).moduleName());
    }
}
