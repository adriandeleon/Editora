package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which project file uses which, read from import lines (Map code browser gap 14). */
class ProjectMapImportsTest {

    /** Every "from -> to" the files (path, text, path, text, …) give, sorted. */
    private static Set<String> edges(Map<String, String> goModules, String... pathsAndTexts) {
        Map<String, ProjectMapImports.Parsed> parsed = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndTexts.length; i += 2) {
            parsed.put(
                    pathsAndTexts[i],
                    Objects.requireNonNull(ProjectMapImports.parse(pathsAndTexts[i], pathsAndTexts[i + 1])));
        }
        Set<String> found = new TreeSet<>();
        for (ProjectMapImports.Edge edge : ProjectMapImports.resolve(parsed, goModules)) {
            found.add(edge.from() + " -> " + edge.to());
        }
        return found;
    }

    private static Set<String> edges(String... pathsAndTexts) {
        return edges(Map.of(), pathsAndTexts);
    }

    @Test
    void languagesAreKnownByTheirExtension() {
        assertEquals(ProjectMapImports.Lang.JAVA, ProjectMapImports.languageOf("src/A.java"));
        assertEquals(ProjectMapImports.Lang.SCRIPT, ProjectMapImports.languageOf("web/App.TSX"));
        assertEquals(ProjectMapImports.Lang.C, ProjectMapImports.languageOf("include/x.hpp"));
        assertNull(ProjectMapImports.languageOf("README.md"));
        assertNull(ProjectMapImports.languageOf("Makefile"));
        assertNull(ProjectMapImports.languageOf("dir.d/file"));
        assertNull(ProjectMapImports.parse("notes.txt", "import a.b.C;"));
    }

    @Test
    void javaImportsTypesWholePackagesAndItsOwnPackagesTypes() {
        Set<String> found = edges(
                "src/a/App.java",
                """
                package a;

                import b.Store;
                import static b.Util.helper;
                import c.*;
                // import d.Ghost;

                class App {
                    Store store = new Store();
                    Model model;   // a.Model: same package, no import
                    Shape shape;   // c.Shape: the wildcard
                }
                """,
                "src/a/Model.java",
                "package a;\npublic record Model(int x) {}\n",
                "src/b/Store.java",
                "package b;\npublic class Store {\n    Inner inner;\n    class Inner {}\n}\n",
                "src/a/Fixture.java",
                "package a;\nclass Fixture {\n    String source = \"\"\"\n        class Shape {}\n        \"\"\";\n}\n",
                "src/b/Util.java",
                "package b;\nfinal class Util { static void helper() {} }\n",
                "src/c/Shape.java",
                "package c;\npublic interface Shape {}\n",
                "src/c/Unused.java",
                "package c;\nclass Unused {}\n",
                "src/d/Ghost.java",
                "package d;\nclass Ghost {}\n");
        assertEquals(
                Set.of(
                        "src/a/App.java -> src/a/Model.java",
                        "src/a/App.java -> src/b/Store.java",
                        "src/a/App.java -> src/b/Util.java",
                        "src/a/App.java -> src/c/Shape.java"),
                found,
                "a commented import is no use; a wildcard counts only the types named");
    }

    @Test
    void anImportOfANestedTypeIsItsFilesAndTheLineIsKept() {
        Map<String, ProjectMapImports.Parsed> parsed = new LinkedHashMap<>();
        parsed.put("A.kt", ProjectMapImports.parse("A.kt", "package x\n\nimport y.Outer.Inner as I\nval i = I()\n"));
        parsed.put("Outer.kt", ProjectMapImports.parse("Outer.kt", "package y\nclass Outer {\n    class Inner\n}\n"));
        List<ProjectMapImports.Edge> edges = ProjectMapImports.resolve(parsed, Map.of());
        assertEquals(1, edges.size());
        assertEquals("Outer.kt", edges.get(0).to());
        assertEquals(2, edges.get(0).line());
        assertEquals("import y.Outer.Inner as I", edges.get(0).text());
    }

    @Test
    void scalaGroupsAndCsharpUsingsAndPhpUses() {
        assertEquals(
                Set.of("app/Main.scala -> lib/A.scala", "app/Main.scala -> lib/B.scala"),
                edges(
                        "app/Main.scala",
                        "package app\nimport lib.{A, B => Bee}\nobject Main { val a = A; val b = Bee }\n",
                        "lib/A.scala",
                        "package lib\nobject A\n",
                        "lib/B.scala",
                        "package lib\nclass B\n"));
        assertEquals(
                Set.of("Program.cs -> Models/User.cs", "Program.cs -> Util/Fmt.cs"),
                edges(
                        "Program.cs",
                        "using System;\nusing Shop.Models;\nusing F = Shop.Util.Fmt;\nnamespace Shop;\n"
                                + "class Program { User u; }\n",
                        "Models/User.cs",
                        "namespace Shop.Models;\npublic class User {}\n",
                        "Util/Fmt.cs",
                        "namespace Shop.Util {\n    static class Fmt {}\n}\n"));
        assertEquals(
                Set.of("src/Http/Controller.php -> src/Model/User.php", "src/Http/Controller.php -> src/boot.php"),
                edges(
                        "src/Http/Controller.php",
                        "<?php\nnamespace App\\Http;\nuse App\\Model\\User;\nrequire_once __DIR__ . '/../boot.php';\n",
                        "src/Model/User.php",
                        "<?php\nnamespace App\\Model;\nclass User {}\n",
                        "src/boot.php",
                        "<?php\n"));
    }

    @Test
    void pythonAbsoluteAndRelativeImports() {
        assertEquals(
                Set.of(
                        "pkg/app.py -> pkg/models/__init__.py",
                        "pkg/app.py -> pkg/util.py",
                        "pkg/models/__init__.py -> pkg/models/user.py",
                        "pkg/models/user.py -> pkg/util.py"),
                edges(
                        "pkg/app.py",
                        "import os, logging\nfrom pkg import util\nfrom .models import User\n",
                        "pkg/util.py",
                        "def helper(): pass\n",
                        "pkg/models/__init__.py",
                        "from .user import User\n",
                        "pkg/models/user.py",
                        "from ..util import helper\n",
                        "tools/logging.py",
                        "# a project's own logging is not the standard one\n"));
    }

    @Test
    void scriptsResolveRelativePathsExtensionsIndexesAndAliases() {
        assertEquals(
                Set.of(
                        "src/app/main.ts -> src/app/store.ts",
                        "src/app/main.ts -> src/components/index.tsx",
                        "src/app/main.ts -> src/lib/api.js",
                        "src/app/main.ts -> src/lib/legacy.cjs",
                        "src/app/main.ts -> src/styles/theme.ts"),
                edges(
                        "src/app/main.ts",
                        """
                        import { a } from './store.js';
                        import {
                          Button,
                        } from "../components";
                        import "@/styles/theme";
                        import React from 'react';
                        const api = await import('../lib/api');
                        const old = require("../lib/legacy.cjs");
                        """,
                        "src/app/store.ts",
                        "export const a = 1;\n",
                        "src/components/index.tsx",
                        "export function Button() {}\n",
                        "src/lib/api.js",
                        "",
                        "src/lib/legacy.cjs",
                        "",
                        "src/styles/theme.ts",
                        ""));
    }

    @Test
    void goImportsResolveThroughTheModulePath() {
        assertEquals(
                Set.of("cmd/tool/main.go -> internal/store/store.go", "cmd/tool/main.go -> internal/store/types.go"),
                edges(
                        Map.of("", "example.com/shop"),
                        "cmd/tool/main.go",
                        "package main\n\nimport (\n\t\"fmt\"\n\tst \"example.com/shop/internal/store\"\n)\n",
                        "internal/store/store.go",
                        "package store\n",
                        "internal/store/types.go",
                        "package store\n",
                        "internal/store/store_test.go",
                        "package store\n"));
        assertEquals("example.com/shop", ProjectMapImports.goModule("// x\nmodule example.com/shop\n\ngo 1.22\n"));
        assertNull(ProjectMapImports.goModule("go 1.22\n"));
    }

    @Test
    void rustModsAndUsePaths() {
        assertEquals(
                Set.of(
                        "src/lib.rs -> src/net.rs",
                        "src/lib.rs -> src/net/http.rs",
                        "src/lib.rs -> src/util/mod.rs",
                        "src/net.rs -> src/net/http.rs",
                        "src/net/http.rs -> src/util/mod.rs",
                        "src/net/http.rs -> src/net.rs"),
                edges(
                        "src/lib.rs",
                        "pub mod net;\nmod util;\nuse crate::net::{http::Client, self};\nuse std::io;\n",
                        "src/net.rs",
                        "pub mod http;\n",
                        "src/net/http.rs",
                        "use super::super::util::Helper;\nuse super::Thing;\npub struct Client;\n",
                        "src/util/mod.rs",
                        "pub struct Helper;\n"));
        assertEquals(List.of("a::b", "a::c::D", "a"), ProjectMapImports.rustPaths("a::{b,c::D,self}"));
        assertEquals(List.of("a::b"), ProjectMapImports.rustPaths("a::b::*"));
    }

    @Test
    void cIncludesRubyRequiresAndDartImports() {
        assertEquals(
                Set.of("src/main.c -> include/proj/api.h", "src/main.c -> src/util.h"),
                edges(
                        "src/main.c",
                        "#include <stdio.h>\n#include \"util.h\"\n#include <proj/api.h>\n",
                        "src/util.h",
                        "",
                        "include/proj/api.h",
                        ""));
        assertEquals(
                Set.of("lib/shop.rb -> lib/shop/cart.rb", "spec/cart_spec.rb -> lib/shop/cart.rb"),
                edges(
                        "lib/shop.rb",
                        "require 'json'\nrequire_relative 'shop/cart'\n",
                        "lib/shop/cart.rb",
                        "class Cart; end\n",
                        "spec/cart_spec.rb",
                        "require 'shop/cart'\n"));
        assertEquals(
                Set.of("lib/main.dart -> lib/src/a.dart", "test/a_test.dart -> lib/src/a.dart"),
                edges(
                        "lib/main.dart",
                        "import 'package:flutter/material.dart';\nimport 'src/a.dart';\n",
                        "lib/src/a.dart",
                        "",
                        "test/a_test.dart",
                        "import 'package:app/src/a.dart';\n"));
    }

    @Test
    void pathsJoinAndFindTheNearest() {
        assertEquals("a/c", ProjectMapImports.join("a/b", "../c"));
        assertEquals("x", ProjectMapImports.join("", "./x"));
        assertNull(ProjectMapImports.join("a", "../../x"));
        assertEquals("", ProjectMapImports.folderOf("x.c"));
        assertEquals(
                "lib/a/x.h",
                ProjectMapImports.nearest("lib/a/src", List.of("vendor/deep/x.h", "lib/a/x.h", "lib/b/x.h")));
        assertEquals("b/x.h", ProjectMapImports.nearest("", List.of("a/b/x.h", "b/x.h")));
        assertNull(ProjectMapImports.nearest("a", List.of()));
    }

    @Test
    void aLongImportLineIsCut() {
        String name = "a." + "b".repeat(200);
        ProjectMapImports.Parsed parsed =
                Objects.requireNonNull(ProjectMapImports.parse("A.java", "import " + name + ";\n"));
        String text = parsed.specs().get(0).text();
        assertTrue(text.endsWith("…") && text.length() == 161, text);
    }

    @Test
    void theJvmLanguagesEdgeCases() {
        assertEquals(
                Set.of("a/A.java -> b/B.java", "a/A.java -> c/C.kt", "c/C.kt -> b/B.java", "s/S.scala -> b/B.java"),
                edges(
                        "a/A.java",
                        "/* header */\n#!shebang\npackage a;\npackage ignored;\nimport x.y.Unknown;\n"
                                + "import c.C; // trailing\nclass A { B b; }\n",
                        "a/B.java",
                        "package elsewhere;\nclass Nothing {}\n",
                        "b/B.java",
                        "package a;\npublic class B {}\n",
                        "c/C.kt",
                        "package c\n\nimport a.*\n\ndata class C(val b: B)\n",
                        "s/S.scala",
                        "package s\nimport a.{_, }\nobject S { val b: B = null }\n"));
        assertEquals(
                Set.of("Cs.cs -> Lib/Fmt.cs"),
                edges(
                        "Cs.cs",
                        "// a comment\nusing static Shop.Util.Fmt;\nnamespace One;\nnamespace Two;\nclass Cs {}\n",
                        "Lib/Fmt.cs",
                        "namespace Shop.Util;\n\tpublic static class Fmt {}\n"));
        assertEquals(
                Set.of(
                        "src/P.php -> src/Model/Role.php",
                        "src/P.php -> src/Model/User.php",
                        "src/P.php -> src/lib.php"),
                edges(
                        "src/P.php",
                        "<?php\n// note\nnamespace App;\nnamespace Other;\nuse App\\Model\\{User, Role as R, };\n"
                                + "require 'lib.php';\n",
                        "src/Model/User.php",
                        "<?php\nnamespace App\\Model;\nclass User {}\n",
                        "src/Model/Role.php",
                        "<?php\nnamespace App\\Model;\nclass Role {}\n",
                        "src/lib.php",
                        "<?php\n"));
    }

    @Test
    void pythonScriptAndGoEdgeCases() {
        assertEquals(
                Set.of("p/a.py -> p/__init__.py", "p/a.py -> p/b.py", "p/a.py -> p/q/__init__.py", "top.py -> p/b.py"),
                edges(
                        "p/a.py",
                        "from . import (b as bee, *)\nfrom .q import missing\nfrom .... import far\n"
                                + "from .gone import x\nimport p,\n",
                        "p/__init__.py",
                        "",
                        "p/b.py",
                        "",
                        "p/q/__init__.py",
                        "",
                        "top.py",
                        "from p import nothing_here, b\n"));
        assertEquals(
                Set.of("web/app.js -> src/x.ts", "web/app.js -> web/lib/index.js", "web/app.js -> web/t.tsx"),
                edges(
                        "web/app.js",
                        "import a from './lib';\nimport b from './lib';\nimport c from '~/x';\n"
                                + "import d from './t.js';\nimport e from './none';\nimport f from '@/none';\n"
                                + "import g from '../../../out';\n",
                        "web/lib/index.js",
                        "",
                        "web/t.tsx",
                        "",
                        "src/x.ts",
                        ""));
        assertEquals(
                Set.of("svc/main.go -> svc/util/u.go", "tool/t.go -> svc/main.go", "tool/t.go -> svc/s.go"),
                edges(
                        Map.of("svc", "example.com/svc", "", "example.com"),
                        "svc/main.go",
                        "package main\nimport \"example.com/svc/util\"\nimport \"other.org/x\"\n",
                        "svc/util/u.go",
                        "package util\n",
                        "svc/s.go",
                        "package svc\n",
                        "tool/t.go",
                        "package tool\nimport \"example.com/svc\"\n"));
    }

    @Test
    void rustCAndOtherEdgeCases() {
        assertEquals(
                Set.of(
                        "src/a/mod.rs -> src/a/inner.rs",
                        "src/lib.rs -> src/a/mod.rs",
                        "src/a/inner.rs -> src/lib.rs",
                        "src/a/mod.rs -> src/lib.rs"), // super::missing: the parent's own file
                edges(
                        "src/lib.rs",
                        "// c\nmod a;\nmod gone;\nuse crate::Nothing;\n",
                        "src/a/mod.rs",
                        "mod inner;\nuse self::inner::X;\nuse super::missing::Y;\n",
                        "src/a/inner.rs",
                        "use super::super::Top;\nuse crate::nowhere::Z;\n",
                        "loose.rs",
                        "use crate::x::Y;\nuse self::y;\n"));
        assertEquals(List.of("a::b", "a::c"), ProjectMapImports.rustPaths("a::{b,c::{d,e}}"));
        assertEquals(List.of("a::b"), ProjectMapImports.rustPaths("a::{b,}"));
        assertEquals(List.of("a::b"), ProjectMapImports.rustPaths("a::{b"), "unclosed");
        assertEquals(
                Set.of("src/m.c -> src/sub/x.h"),
                edges(
                        "src/m.c",
                        "#include \"missing.h\"\n#include <dup.h>\n#include \"./sub/x.h\"\n",
                        "src/sub/x.h",
                        "",
                        "a/dup.h",
                        "",
                        "b/dup.h",
                        ""));
        assertEquals(
                Set.of("r/a.rb -> r/b.rb", "r/a.rb -> lib/c.rb"),
                edges("r/a.rb", "require_relative 'b.rb'\nrequire 'c.rb'\n", "r/b.rb", "", "lib/c.rb", ""));
        ProjectMapImports.Parsed dart = Objects.requireNonNull(
                ProjectMapImports.parse("x.dart", "import 'dart:core';\nimport 'package:solo';\n"));
        assertTrue(dart.specs().isEmpty(), dart.specs().toString());
        assertEquals(ProjectMapImports.Lang.KOTLIN, ProjectMapImports.languageOf("build.gradle.kts"));
        assertNull(ProjectMapImports.languageOf(".hidden"));
    }
}
