package com.editora.build;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure coverage of the wrapper → override → default executable resolution shared by every build tool. */
class BuildExecutableTest {

    @Test
    void wrapperWinsWhenPresent() {
        assertEquals(List.of("./mvnw"), BuildExecutable.resolve(List.of("./mvnw"), "custom-mvn", "mvn"));
        assertEquals(List.of("gradlew.cmd"), BuildExecutable.resolve(List.of("gradlew.cmd"), null, "gradle"));
    }

    @Test
    void overrideIsTokenizedWhenNoWrapper() {
        assertEquals(List.of("custom", "mvn"), BuildExecutable.resolve(List.of(), "custom mvn", "mvn"));
        assertEquals(List.of("/opt/go/bin/go"), BuildExecutable.resolve(null, "/opt/go/bin/go", "go"));
    }

    @Test
    void bareDefaultWhenNoWrapperOrOverride() {
        assertEquals(List.of("cargo"), BuildExecutable.resolve(List.of(), "  ", "cargo"));
        assertEquals(List.of("pnpm"), BuildExecutable.resolve(null, null, "pnpm")); // npm's PM-detected default
    }
}
