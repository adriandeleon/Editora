package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a C or C++ file is built for debugging, and which executable that makes. */
class NativeBuildTest {

    private static final byte[] ELF = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};

    @Test
    void theCmakeProjectIsTheTopOfTheRunOfFoldersWithACmakeListsAndMakeTheNearest(@TempDir Path dir) throws Exception {
        Path lib = Files.createDirectories(dir.resolve("ws/proj/lib"));
        Files.writeString(dir.resolve("ws/proj/CMakeLists.txt"), "project(p)\n");
        Files.writeString(lib.resolve("CMakeLists.txt"), "add_library(l x.c)\n");
        Path x = Files.writeString(lib.resolve("x.c"), "");
        assertEquals(dir.resolve("ws/proj").toAbsolutePath(), NativeBuild.cmakeRoot(x), "the project, not lib/");
        assertNull(NativeBuild.cmakeRoot(Files.writeString(dir.resolve("ws/y.c"), "")));

        Path src = Files.createDirectories(dir.resolve("m/src"));
        Files.writeString(dir.resolve("m/GNUmakefile"), "all:\n");
        assertEquals(
                dir.resolve("m").toAbsolutePath(), NativeBuild.makeRoot(Files.writeString(src.resolve("z.c"), "")));
        assertNull(NativeBuild.makeRoot(Files.writeString(dir.resolve("loose.c"), "")));
    }

    @Test
    void eachToolHasItsSteps(@TempDir Path dir) {
        Path root = dir.resolve("p");
        Path file = root.resolve("src/main.cpp");
        NativeBuild.Plan cmake = NativeBuild.plan(file, true, root, null, dir.resolve("out"));
        assertEquals(NativeBuild.Tool.CMAKE, cmake.tool());
        Path build = root.resolve(NativeBuild.CMAKE_BUILD_DIR);
        assertEquals(build, cmake.buildDir());
        assertEquals(
                List.of(
                        List.of("cmake", "-S", root.toString(), "-B", build.toString(), "-DCMAKE_BUILD_TYPE=Debug"),
                        List.of("cmake", "--build", build.toString())),
                cmake.steps());
        assertEquals("main.cpp", cmake.sessionName());

        NativeBuild.Plan make = NativeBuild.plan(file, true, null, root, dir.resolve("out"));
        assertEquals(List.of(List.of("make", "-C", root.toString())), make.steps());
        assertEquals(root, make.root());

        NativeBuild.Plan c = NativeBuild.plan(dir.resolve("hello.c"), false, null, null, dir.resolve("out"));
        assertEquals(NativeBuild.Tool.COMPILER, c.tool());
        List<String> cc = c.steps().getFirst();
        assertEquals(List.of("cc", "-g", "-O0", "-o"), cc.subList(0, 4));
        assertTrue(c.output().startsWith(dir.resolve("out")));
        assertTrue(c.output().getFileName().toString().startsWith("hello-"));
        assertEquals(
                "c++",
                NativeBuild.plan(file, true, null, null, dir).steps().getFirst().getFirst());

        assertNull(NativeBuild.plan(dir.resolve("shelf.hpp"), true, null, null, dir), "a lone header is not built");
        assertTrue(NativeBuild.isHeader(Path.of("a.h")));
        assertFalse(NativeBuild.isHeader(Path.of("a.c")));
        assertEquals(
                NativeBuild.Tool.CMAKE,
                NativeBuild.plan(dir.resolve("p/shelf.hpp"), true, root, null, dir)
                        .tool(),
                "a header of a project builds the project");
    }

    @Test
    void cmakesFileApiNamesTheExecutableHoldingTheFile(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src-root");
        Path build = dir.resolve("build");
        NativeBuild.requestCodemodel(build);
        assertTrue(Files.exists(build.resolve(".cmake/api/v1/query/codemodel-v2")));
        NativeBuild.requestCodemodel(build); // asking twice is harmless
        Path reply = Files.createDirectories(build.resolve(".cmake/api/v1/reply"));
        Files.writeString(reply.resolve("index-2026-01-01T00-00-00-0000.json"), "{\"objects\":[]}");
        Files.writeString(
                reply.resolve("index-2026-11-08T00-00-00-0000.json"),
                "{\"objects\":[{\"kind\":\"cache\",\"jsonFile\":\"cache.json\"},"
                        + "{\"kind\":\"codemodel\",\"jsonFile\":\"codemodel-v2.json\"}]}");
        Files.writeString(
                reply.resolve("codemodel-v2.json"),
                "{\"paths\":{\"source\":\"" + json(source) + "\",\"build\":\"" + json(build) + "\"},"
                        + "\"configurations\":[{\"name\":\"Debug\",\"targets\":["
                        + "{\"name\":\"lib\",\"jsonFile\":\"target-lib.json\"},"
                        + "{\"name\":\"app\",\"jsonFile\":\"target-app.json\"},"
                        + "{\"name\":\"tool\",\"jsonFile\":\"target-tool.json\"}]}]}");
        Files.writeString(
                reply.resolve("target-lib.json"),
                "{\"type\":\"STATIC_LIBRARY\",\"artifacts\":[{\"path\":\"liblib.a\"}],"
                        + "\"sources\":[{\"path\":\"lib/greet.cpp\"}]}");
        Files.writeString(
                reply.resolve("target-app.json"),
                "{\"type\":\"EXECUTABLE\",\"artifacts\":[{\"path\":\"app/app\"}],"
                        + "\"sources\":[{\"path\":\"app/main.cpp\"},{\"path\":\"app/util.cpp\"}]}");
        Files.writeString(
                reply.resolve("target-tool.json"),
                "{\"type\":\"EXECUTABLE\",\"artifacts\":[{\"path\":\"tool\"}],\"sources\":[{\"path\":\"tool.cpp\"}]}");

        assertEquals(build.resolve("app/app"), NativeBuild.cmakeExecutable(build, source.resolve("app/util.cpp")));
        assertEquals(build.resolve("tool"), NativeBuild.cmakeExecutable(build, source.resolve("tool.cpp")));
        assertNull(
                NativeBuild.cmakeExecutable(build, source.resolve("lib/greet.cpp")),
                "a library's file, with two programs to choose from: none is chosen");
        assertNull(NativeBuild.cmakeExecutable(dir.resolve("never-built"), source.resolve("tool.cpp")));
    }

    private static String json(Path p) {
        return p.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    @Test
    void makesProgramIsTheOneNamedAfterTheFileElseTheOnlyElseTheNewest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shelf.c");
        Path script = Files.writeString(dir.resolve("run.sh"), "#!/bin/sh\n");
        assertTrue(script.toFile().setExecutable(true));
        assertNull(NativeBuild.makeExecutable(dir, file), "a script is no program");
        Path other = Files.write(Files.createDirectories(dir.resolve("bin")).resolve("other"), ELF);
        assertTrue(other.toFile().setExecutable(true));
        assertEquals(other, NativeBuild.makeExecutable(dir, file), "the only one");
        Path newer = Files.write(Files.createDirectories(dir.resolve("build")).resolve("newer"), ELF);
        assertTrue(newer.toFile().setExecutable(true));
        Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.fromMillis(1_000));
        assertEquals(newer, NativeBuild.makeExecutable(dir, file), "the newest");
        Path named = Files.write(dir.resolve("shelf"), ELF);
        assertTrue(named.toFile().setExecutable(true));
        Files.setLastModifiedTime(named, java.nio.file.attribute.FileTime.fromMillis(1_000));
        assertEquals(named, NativeBuild.makeExecutable(dir, file), "the one named after the file");
        assertTrue(NativeBuild.isNativeProgram(named));
        assertFalse(NativeBuild.isNativeProgram(script));
        assertFalse(NativeBuild.isNativeProgram(Files.write(dir.resolve("tiny"), new byte[] {0x7f})));
        assertTrue(NativeBuild.isNativeProgram(Files.write(dir.resolve("pe"), new byte[] {'M', 'Z', 0, 0})));
        assertTrue(NativeBuild.isNativeProgram(
                Files.write(dir.resolve("macho"), new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe})));
    }

    @Test
    void codelldbsRequestsAreForCAndCpp() {
        Map<String, Object> launch = NativeBuild.launch(Path.of("/p/app"), List.of("-v"), Path.of("/p"), false);
        assertEquals("lldb", launch.get("type"));
        assertEquals(Path.of("/p/app").toString(), launch.get("program"));
        assertEquals(List.of("cpp"), launch.get("sourceLanguages"));
        assertEquals("console", launch.get("terminal"));
        assertEquals(
                "integrated",
                NativeBuild.launch(Path.of("/p/app"), List.of(), Path.of("/p"), true)
                        .get("terminal"));
        Map<String, Object> attach = NativeBuild.attach(42, "rust");
        assertEquals("attach", attach.get("request"));
        assertEquals(42L, attach.get("pid"));
        assertEquals(List.of("rust"), attach.get("sourceLanguages"));
    }
}
