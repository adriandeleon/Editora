package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JS review C102: how a TypeScript file runs. */
class TsRunRouteTest {

    @TempDir
    Path tmp;

    private Path write(String rel, String text) throws Exception {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    private TsRunRoute route(Path file) {
        return TsRunRoute.choose(
                file,
                tmp,
                Files::isRegularFile,
                p -> {
                    try {
                        return Optional.of(Files.readString(p));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                },
                p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis();
                    } catch (Exception e) {
                        return 0;
                    }
                });
    }

    @Test
    void theProjectsTsxComesFirst() throws Exception {
        write("node_modules/tsx/package.json", "{}");
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
        write("dist/index.js", "");
        Path src = write("src/index.ts", "");
        assertEquals(TsRunRoute.Kind.TSX, route(src).kind());
        assertEquals(src, route(src).target());
    }

    @Test
    void elseTheCompiledFileRunsAndAStaleOneIsFlagged() throws Exception {
        write(
                "tsconfig.json",
                "{\n  // comments and trailing commas are fine\n"
                        + "  \"compilerOptions\": {\"outDir\": \"./dist\", \"rootDir\": \"src\",},\n}");
        Path out = write("dist/lib/a.js", "");
        Path src = write("src/lib/a.ts", "");
        Files.setLastModifiedTime(out, FileTime.fromMillis(2_000_000));
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_000_000));
        TsRunRoute r = route(src);
        assertEquals(TsRunRoute.Kind.BUILT, r.kind());
        assertEquals(out, r.target());
        assertFalse(r.stale());
        Files.setLastModifiedTime(src, FileTime.fromMillis(3_000_000));
        assertTrue(route(src).stale());
    }

    @Test
    void withNoRootDirTheCommonSourceFolderIsDropped() throws Exception {
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\"},\"include\":[\"src\"]}");
        Path out = write("dist/m.mjs", "");
        Path src = write("src/m.mts", "");
        assertEquals(out, route(src).target());
    }

    @Test
    void elsePlainNode() throws Exception {
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
        Path src = write("src/index.ts", ""); // not built yet
        assertEquals(TsRunRoute.Kind.PLAIN, route(src).kind());
        Path loose = write("tool.ts", "");
        assertEquals(TsRunRoute.Kind.PLAIN, route(loose).kind());
        assertEquals(
                TsRunRoute.Kind.PLAIN,
                route(write("x/bad/tsconfig.json", "{oops").resolveSibling("y.ts"))
                        .kind());
    }

    @Test
    void tsxLoadsWithImportOnANewNodeAndLoaderOnAnOldOne() {
        assertEquals(List.of("--import", "tsx"), TsRunRoute.tsxFlags("v24.18.1"));
        assertEquals(List.of("--import", "tsx"), TsRunRoute.tsxFlags("v20.6.0"));
        assertEquals(List.of("--import", "tsx"), TsRunRoute.tsxFlags("v18.19.0"));
        assertEquals(List.of("--loader", "tsx"), TsRunRoute.tsxFlags("v18.18.2"));
        assertEquals(List.of("--import", "tsx"), TsRunRoute.tsxFlags(null));
    }

    @Test
    void theJsImportFailureNamesTheTsFile() throws Exception {
        Path ts = write("src/greet.ts", "");
        String js = tmp.resolve("src/greet.js").toString();
        String line = "Error [ERR_MODULE_NOT_FOUND]: Cannot find module '" + js + "' imported from "
                + tmp.resolve("src/index.ts");
        assertEquals(Optional.of(ts), TsRunRoute.missingJsImport(line, Files::isRegularFile));
        String other = "Error [ERR_MODULE_NOT_FOUND]: Cannot find module '" + tmp.resolve("src/nope.js")
                + "' imported from " + tmp.resolve("src/index.ts");
        assertTrue(TsRunRoute.missingJsImport(other, Files::isRegularFile).isEmpty(), "no .ts beside it");
        assertTrue(
                TsRunRoute.missingJsImport("console.log", Files::isRegularFile).isEmpty());
        assertEquals("a.cjs", TsRunRoute.jsName("a.cts"));
    }
}
