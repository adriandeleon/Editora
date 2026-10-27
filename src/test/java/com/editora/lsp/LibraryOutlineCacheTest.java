package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only a JDK class's outline is kept for the session: a dependency's jar can be rebuilt in place under the same URI
 * (tenth review DO4).
 */
class LibraryOutlineCacheTest {

    @Test
    void onlyAJdkClassIsCached() {
        assertTrue(LspManager.jdkClassFile("jdt://contents/java.base/java.util/ArrayList.java?=demo/"
                + "%5C/home%5C/u%5C/jdk%5C/lib%5C/jrt-fs.jar%60java.base=/javadoc_location=/x"));
        assertFalse(LspManager.jdkClassFile("jdt://contents/guava-33.0.jar/com.google.common.collect/"
                + "ImmutableList.class?=demo/%5C/home%5C/u%5C/.m2%5C/repository%5C/guava-33.0.jar"));
    }
}
