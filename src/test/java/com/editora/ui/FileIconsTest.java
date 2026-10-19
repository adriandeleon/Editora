package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileIconsTest {

    @Test
    void languageGlyphsByExtension() {
        assertEquals("java", FileIcons.iconKeyFor("Main.java"));
        assertEquals("python", FileIcons.iconKeyFor("script.py"));
        assertEquals("javascript", FileIcons.iconKeyFor("app.js"));
        assertEquals("javascript", FileIcons.iconKeyFor("App.jsx")); // javascriptreact -> javascript
        assertEquals("typescript", FileIcons.iconKeyFor("app.ts"));
        assertEquals("typescript", FileIcons.iconKeyFor("App.tsx")); // typescriptreact -> typescript
        assertEquals("go", FileIcons.iconKeyFor("main.go"));
        assertEquals("php", FileIcons.iconKeyFor("index.php"));
        assertEquals("ruby", FileIcons.iconKeyFor("app.rb"));
        assertEquals("c", FileIcons.iconKeyFor("main.c"));
        assertEquals("cpp", FileIcons.iconKeyFor("main.cpp"));
        assertEquals("csharp", FileIcons.iconKeyFor("Program.cs"));
        assertEquals("kotlin", FileIcons.iconKeyFor("Main.kt"));
        assertEquals("html", FileIcons.iconKeyFor("index.html"));
        assertEquals("css", FileIcons.iconKeyFor("style.css"));
        assertEquals("markdown", FileIcons.iconKeyFor("README.md"));
        assertEquals("yaml", FileIcons.iconKeyFor("config.yml"));
        assertEquals("json", FileIcons.iconKeyFor("data.json"));
        assertEquals("npm", FileIcons.iconKeyFor("package.json"));
        assertEquals("npm", FileIcons.iconKeyFor("/p/package-lock.json"));
        assertEquals("npm", FileIcons.iconKeyFor(".npmrc"));
        assertEquals("shell", FileIcons.iconKeyFor("build.sh"));
        assertEquals("mermaid", FileIcons.iconKeyFor("diagram.mmd"));
        assertEquals("markdown", FileIcons.iconKeyFor("report.typ")); // typst -> document glyph
        assertEquals("terraform", FileIcons.iconKeyFor("main.tf"));
    }

    @Test
    void groupedLanguageKeys() {
        assertEquals("code", FileIcons.iconKeyFor("pom.xml")); // xml -> generic code glyph
        assertEquals("code", FileIcons.iconKeyFor("lib.rs")); // rust shares the code glyph
        assertEquals("code", FileIcons.iconKeyFor("api.http"));
        assertEquals("terminal", FileIcons.iconKeyFor("run.bat")); // batchfile
        assertEquals("terminal", FileIcons.iconKeyFor("task.ps1")); // powershell
        assertEquals("storage", FileIcons.iconKeyFor("schema.sql"));
        assertEquals("settings", FileIcons.iconKeyFor("app.ini"));
        assertEquals("settings", FileIcons.iconKeyFor("Cargo.toml"));
        assertEquals("settings", FileIcons.iconKeyFor("app.properties"));
    }

    @Test
    void nonLanguageTypesResolveByExtensionFirst() {
        // .svg is XML to the highlighter, but an image to the user.
        assertEquals("image", FileIcons.iconKeyFor("logo.svg"));
        assertEquals("image", FileIcons.iconKeyFor("photo.PNG")); // case-insensitive
        assertEquals("image", FileIcons.iconKeyFor("icon.jpeg"));
        assertEquals("pdf", FileIcons.iconKeyFor("manual.pdf"));
        assertEquals("archive", FileIcons.iconKeyFor("release.zip"));
        assertEquals("archive", FileIcons.iconKeyFor("lib.jar"));
        assertEquals("table", FileIcons.iconKeyFor("data.csv"));
        assertEquals("lock", FileIcons.iconKeyFor("Gemfile.lock"));
        assertEquals("text", FileIcons.iconKeyFor("notes.txt"));
        assertEquals("text", FileIcons.iconKeyFor("output.log"));
    }

    @Test
    void specialFilenameAndDockerfile() {
        assertEquals("docker", FileIcons.iconKeyFor("Dockerfile"));
        assertEquals("docker", FileIcons.iconKeyFor("Dockerfile.dev"));
    }

    @Test
    void plainTextFormats() {
        assertEquals("code", FileIcons.iconKeyFor("changes.diff"));
        assertEquals("code", FileIcons.iconKeyFor("fix.patch"));
        assertEquals("code", FileIcons.iconKeyFor("api.proto"));
        assertEquals("code", FileIcons.iconKeyFor("schema.graphql"));
        assertEquals("code", FileIcons.iconKeyFor("query.gql"));
        assertEquals("terminal", FileIcons.iconKeyFor("Makefile"));
        assertEquals("terminal", FileIcons.iconKeyFor("rules.mk"));
        assertEquals("terminal", FileIcons.iconKeyFor("justfile"));
        assertEquals("settings", FileIcons.iconKeyFor(".gitattributes"));
    }

    @Test
    void pathsAreReducedToTheLastSegment() {
        assertEquals("java", FileIcons.iconKeyFor("/home/me/src/Main.java"));
        assertEquals("python", FileIcons.iconKeyFor("C:\\project\\script.py"));
        assertEquals("image", FileIcons.iconKeyFor("assets/img/banner.webp"));
    }

    @Test
    void unknownAndNullFallBackToGeneric() {
        assertEquals("generic", FileIcons.iconKeyFor("data.unknownext"));
        assertEquals("generic", FileIcons.iconKeyFor("noextension"));
        assertEquals("generic", FileIcons.iconKeyFor(null));
    }
    /**
     * An open buffer's icon follows its resolved language when its name says nothing — an extension-less
     * shebang script, a picked language — but a name that does decide (an image, a .log) keeps its own glyph.
     */
    @Test
    void openBuffersUseTheirResolvedLanguage() {
        assertEquals("generic", FileIcons.iconKeyFor("deploy"));
        assertEquals("shell", FileIcons.iconKeyFor("deploy", "shell"));
        assertEquals("python", FileIcons.iconKeyFor("tool", "python"));
        assertEquals("generic", FileIcons.iconKeyFor("notes", "plaintext"));
        assertEquals("generic", FileIcons.iconKeyFor("notes", null));
        assertEquals("image", FileIcons.iconKeyFor("logo.png", "shell"));
        assertEquals("text", FileIcons.iconKeyFor("app.log", "log"));
        assertEquals("java", FileIcons.iconKeyFor("A.java", "java"));
    }

    @Test
    void jvmLanguagesHaveBrandGlyphs() {
        assertEquals("groovy", FileIcons.iconKeyFor("build.gradle"));
        assertEquals("groovy", FileIcons.iconKeyFor("Jenkinsfile"));
        assertEquals("scala", FileIcons.iconKeyFor("Main.scala"));
        assertEquals("scala", FileIcons.iconKeyFor("build.sbt"));
        assertEquals("clojure", FileIcons.iconKeyFor("core.clj"));
        assertEquals("clojure", FileIcons.iconKeyFor("deps.edn"));
        assertEquals("kotlin", FileIcons.iconKeyFor("build.gradle.kts"));
        assertEquals("scala", FileIcons.iconKeyFor("script", "scala"), "a shebang-detected buffer");
        assertEquals("clojure", FileIcons.iconKeyFor("tool", "clojure"));
    }
}
