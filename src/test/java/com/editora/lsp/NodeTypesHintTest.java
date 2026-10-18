package com.editora.lsp;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a JavaScript file gets the one-time "Node.js APIs have no types" hint (Node review B9). */
class NodeTypesHintTest {

    @Test
    void nodeBuiltinsAndGlobalsCount() {
        assertTrue(NodeTypesHint.usesNodeApis("const fs = require(\"fs\");"));
        assertTrue(NodeTypesHint.usesNodeApis("const p = require('node:path');"));
        assertTrue(NodeTypesHint.usesNodeApis("import { readFile } from \"node:fs/promises\";"));
        assertTrue(NodeTypesHint.usesNodeApis("import os from 'os';"));
        assertTrue(NodeTypesHint.usesNodeApis("console.log(process.argv.length);"));
        assertTrue(NodeTypesHint.usesNodeApis("console.log(__dirname);"));
    }

    @Test
    void browserCodeAndPackagesDoNot() {
        assertFalse(NodeTypesHint.usesNodeApis("document.querySelector('a');"));
        assertFalse(NodeTypesHint.usesNodeApis("import React from 'react';"));
        assertFalse(NodeTypesHint.usesNodeApis("const x = require('./fs');"));
        assertFalse(NodeTypesHint.usesNodeApis("const x = require('fsevents');"));
        assertFalse(NodeTypesHint.usesNodeApis(null));
    }

    @Test
    void typesAnywhereAboveTheFileCount() {
        Path root = Path.of("/").toAbsolutePath(); // a drive on Windows: the walk works on absolute paths
        Set<Path> files = Set.of(root.resolve("p/node_modules/@types/node/package.json"));
        assertTrue(NodeTypesHint.hasNodeTypes(root.resolve("p/src/deep/a.js"), files::contains));
        assertFalse(NodeTypesHint.hasNodeTypes(root.resolve("q/a.js"), files::contains));
        assertFalse(NodeTypesHint.hasNodeTypes(null, files::contains));
    }
}
