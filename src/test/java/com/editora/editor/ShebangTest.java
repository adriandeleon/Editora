package com.editora.editor;

import com.editora.editor.Shebang.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShebangTest {

    private static String ext(String line) {
        return Shebang.extension(line);
    }

    @Test
    void directInterpreterPaths() {
        assertEquals("sh", ext("#!/bin/bash"));
        assertEquals("sh", ext("#!/bin/sh"));
        assertEquals("sh", ext("#!/bin/zsh"));
        assertEquals("sh", ext("#!/usr/bin/dash"));
        assertEquals("py", ext("#!/usr/bin/python3"));
        assertEquals("rb", ext("#!/usr/bin/ruby"));
        assertEquals("js", ext("#!/usr/local/bin/node"));
        assertEquals("php", ext("#!/usr/bin/php"));
        assertEquals("lua", ext("#!/usr/bin/lua"));
        assertEquals("ps1", ext("#!/usr/bin/pwsh"));
    }

    @Test
    void envLookup() {
        assertEquals("py", ext("#!/usr/bin/env python3"));
        assertEquals("sh", ext("#!/usr/bin/env bash"));
        assertEquals("js", ext("#!/usr/bin/env node"));
        assertEquals("rb", ext("#!/usr/bin/env ruby"));
        assertEquals("ts", ext("#!/usr/bin/env deno"));
        assertEquals("ts", ext("#!/usr/bin/env bun"));
        assertEquals("groovy", ext("#!/usr/bin/env groovy"));
    }

    @Test
    void envWithFlagsAndAssignments() {
        assertEquals("py", ext("#!/usr/bin/env -S python3"));
        assertEquals("py", ext("#!/usr/bin/env FOO=bar python3"));
        assertEquals("ts", ext("#!/usr/bin/env -S deno run --allow-net"));
    }

    @Test
    void versionSuffixesAreStripped() {
        assertEquals("py", ext("#!/usr/bin/python3.12"));
        assertEquals("py", ext("#!/usr/bin/env python2"));
        assertEquals("php", ext("#!/usr/bin/php8"));
        assertEquals("lua", ext("#!/usr/bin/lua5.4"));
    }

    @Test
    void tsRuntimes() {
        assertEquals("ts", ext("#!/usr/bin/env ts-node"));
        assertEquals("ts", ext("#!/usr/bin/env tsx"));
    }

    @Test
    void javaCompactSourceNeedsSourceFlag() {
        Result r = Shebang.parse("#!/usr/bin/env -S java --source 25");
        assertEquals("java", r.extension());
        assertEquals(25, r.javaSource());

        assertEquals(21, Shebang.parse("#!/usr/bin/env -S java --source=21").javaSource());
        assertEquals(
                25,
                Shebang.parse("#!/usr/bin/env -S java --enable-preview --source 25")
                        .javaSource());
    }

    @Test
    void bareJavaWithoutSourceIsNotCompact() {
        assertNull(Shebang.parse("#!/usr/bin/env java"));
        assertNull(Shebang.parse("#!/usr/bin/java"));
        assertNull(Shebang.parse("#!/usr/bin/env -S java --source")); // missing version
    }

    @Test
    void unknownInterpretersAndNonShebangs() {
        assertNull(ext("#!/usr/bin/perl"));
        assertNull(ext("#!/usr/bin/env fish"));
        assertNull(ext("#!/usr/bin/env Rscript"));
        assertNull(ext("echo hello"));
        assertNull(ext("// not a shebang"));
        assertNull(ext(""));
        assertNull(ext(null));
        assertNull(ext("#!"));
    }

    @Test
    void leadingAndTrailingWhitespaceTolerated() {
        assertEquals("sh", ext("  #!/bin/bash  "));
        assertEquals("py", ext("#!  /usr/bin/env   python3"));
    }
}
