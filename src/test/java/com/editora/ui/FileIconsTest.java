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
        assertEquals("json", FileIcons.iconKeyFor("package.json"));
        assertEquals("shell", FileIcons.iconKeyFor("build.sh"));
        assertEquals("mermaid", FileIcons.iconKeyFor("diagram.mmd"));
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
    void pathsAreReducedToTheLastSegment() {
        assertEquals("java", FileIcons.iconKeyFor("/home/me/src/Main.java"));
        assertEquals("python", FileIcons.iconKeyFor("C:\\project\\script.py"));
        assertEquals("image", FileIcons.iconKeyFor("assets/img/banner.webp"));
    }

    @Test
    void unknownAndNullFallBackToGeneric() {
        assertEquals("generic", FileIcons.iconKeyFor("data.unknownext"));
        assertEquals("generic", FileIcons.iconKeyFor("Makefile")); // no extension, not a known special
        assertEquals("generic", FileIcons.iconKeyFor("noextension"));
        assertEquals("generic", FileIcons.iconKeyFor(null));
    }
}
