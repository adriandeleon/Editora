package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The source-reading corners of {@link DapManager}: shebang blanking, the declared main class, mixed replies. */
class DapManagerSourceEdgeTest {

    @Test
    void aSourceWithNoShebangIsCompiledAsItIs() {
        String plain = "void main() {\n}\n";

        assertSame(plain, DapManager.withoutShebang(plain));
        assertNull(DapManager.withoutShebang(null));
        assertEquals("", DapManager.withoutShebang("#!/usr/bin/env java"), "a shebang and nothing else");
    }

    @Test
    void aTypeNamedInACommentIsNotTheMainClass(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Tool.java");
        Files.writeString(
                file,
                "// class NotThisOne {}\n/* class NorThis */\n/**\n * class NorThat in the Javadoc\n */\n"
                        + "package tools;\n\npublic final class Real {\n}\n");

        assertEquals("tools.Real", DapManager.mainClassFromFile(file));
    }

    @Test
    void aFileWithNoNameBeforeItsExtensionHasNoMainClass(@TempDir Path dir) {
        assertNull(DapManager.mainClassFromFile(dir.resolve(".java")));
        assertEquals("script", DapManager.mainClassFromFile(dir.resolve("script")), "no extension: the name itself");
    }

    @Test
    void aClasspathReplyOfGsonArraysInsideAPlainListIsRead() {
        JsonArray modules = new JsonArray();
        modules.add("/m/mod.jar");
        modules.add(new JsonArray()); // not a path: passed over
        JsonArray classes = new JsonArray();
        classes.add(new JsonPrimitive("/c/classes"));
        List<Object> reply = new ArrayList<>();
        reply.add(modules);
        reply.add(classes);
        List<String> modulePaths = new ArrayList<>();
        List<String> classPaths = new ArrayList<>();

        FxlessAccess.parseClasspath(reply, modulePaths, classPaths);

        assertEquals(List.of("/m/mod.jar"), modulePaths);
        assertEquals(List.of("/c/classes"), classPaths);
    }

    @Test
    void repliesInGsonFormAreReadAndAnythingElseIsNothing() {
        com.google.gson.JsonObject app = new com.google.gson.JsonObject();
        app.addProperty("mainClass", "demo.App");
        app.add("projectName", new JsonArray()); // not a name: left out
        JsonArray listed = new JsonArray();
        listed.add(app);
        listed.add("stray");

        assertEquals(
                List.of(new DapManager.MainClassOption("demo.App", null, null)), FxlessAccess.parseMainClasses(listed));
        assertEquals(List.of(), FxlessAccess.parseMainClasses(null));
        assertEquals(List.of(), FxlessAccess.parseMainClasses(new JsonPrimitive("none")));

        JsonArray onlyModules = new JsonArray();
        JsonArray modules = new JsonArray();
        modules.add("/m/one.jar");
        onlyModules.add(modules);
        List<String> modulePaths = new ArrayList<>();
        List<String> classPaths = new ArrayList<>();
        FxlessAccess.parseClasspath(onlyModules, modulePaths, classPaths);
        assertEquals(List.of("/m/one.jar"), modulePaths);
        assertEquals(List.of(), classPaths, "a reply with one list has no class path");

        FxlessAccess.parseClasspath(new JsonArray(), modulePaths, classPaths);
        FxlessAccess.parseClasspath(new JsonPrimitive("soon"), modulePaths, classPaths);
        FxlessAccess.parseClasspath(null, modulePaths, classPaths);
        FxlessAccess.parseClasspath(List.of(), modulePaths, classPaths);
        FxlessAccess.parseClasspath(List.of("not a list", new JsonPrimitive("nor this")), modulePaths, classPaths);
        assertEquals(List.of("/m/one.jar"), modulePaths, "nothing more was read from replies that hold no paths");
        assertEquals(List.of(), classPaths);
    }
}
