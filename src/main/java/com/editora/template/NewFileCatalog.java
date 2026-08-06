package com.editora.template;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The file kinds offered by the Project tree's "New ▸" menu (and the {@code file.newFileOfType}
 * palette picker) — the IDE-standard "New ▸ Java Class / Python File / YAML File" catalog.
 *
 * <p>Pure data plus lookups, so the whole menu is one table: adding a type is a row, and the menu,
 * the palette picker and the tests all pick it up. Every entry names a type Editora already
 * understands (its extension resolves through {@link com.editora.editor.LanguageRegistry} /
 * {@code ConfigFileType} to a real language, so the new file opens highlighted) — the catalog is
 * deliberately a curated subset of "one obvious file per language" rather than every extension
 * alias: nobody looks for "New ▸ .mjs".
 *
 * <p>Distinct from {@link TemplateRegistry}: templates are user-extensible, variable-driven
 * documents behind a wizard ("New From Template…"), which is the right tool for a Maven skeleton and
 * the wrong one for "give me an empty YAML file".
 */
public final class NewFileCatalog {

    private NewFileCatalog() {}

    /** One submenu of the "New ▸" menu. Its label is translated via {@code newfile.category.<id>}. */
    public record Category(String id, List<NewFileType> types) {

        public String labelKey() {
            return "newfile.category." + id;
        }
    }

    // --- initial contents -------------------------------------------------------------------------
    // Only where a bare empty file would be useless or a header is genuinely conventional. Everything
    // else starts empty: guessing at boilerplate the user then has to delete is worse than nothing.

    private static final String JAVA_CLASS = """
            {package}public class {name} {

                {cursor}
            }
            """;

    private static final String JAVA_INTERFACE = """
            {package}public interface {name} {

                {cursor}
            }
            """;

    private static final String JAVA_RECORD = """
            {package}public record {name}({cursor}) {}
            """;

    private static final String JAVA_ENUM = """
            {package}public enum {name} {
                {cursor}
            }
            """;

    private static final String JAVA_ANNOTATION = """
            {package}public @interface {name} {
                {cursor}
            }
            """;

    private static final String JAVA_PACKAGE_INFO = """
            /**
             * {cursor}
             */
            {package}
            """;

    private static final String BASH = """
            #!/usr/bin/env bash
            set -euo pipefail

            {cursor}
            """;

    private static final String ZSH = """
            #!/usr/bin/env zsh

            {cursor}
            """;

    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{name}</title>
            </head>
            <body>
                {cursor}
            </body>
            </html>
            """;

    /**
     * The generic entry: whatever name the user types decides the type. Not in any category — it is
     * the first item of the menu, above the type list.
     */
    public static final NewFileType PLAIN = NewFileType.translated("file", "", "");

    /** Plain text — a top-level entry, since "a text file" is the most common thing asked for. */
    public static final NewFileType TEXT = NewFileType.translated("text", "untitled", "txt");

    /** Markdown — the other top-level entry, for the same reason. */
    public static final NewFileType MARKDOWN = NewFileType.translated("markdown", "untitled", "md");

    private static final List<Category> CATEGORIES = List.of(
            new Category(
                    "java",
                    List.of(
                            NewFileType.translated("java.class", "Main", "java").withTemplate(JAVA_CLASS),
                            NewFileType.translated("java.interface", "MyInterface", "java")
                                    .withTemplate(JAVA_INTERFACE),
                            NewFileType.translated("java.record", "MyRecord", "java")
                                    .withTemplate(JAVA_RECORD),
                            NewFileType.translated("java.enum", "MyEnum", "java")
                                    .withTemplate(JAVA_ENUM),
                            NewFileType.translated("java.annotation", "MyAnnotation", "java")
                                    .withTemplate(JAVA_ANNOTATION),
                            NewFileType.named("java.packageInfo", "package-info.java", "package-info", "java")
                                    .withTemplate(JAVA_PACKAGE_INFO))),
            new Category(
                    "web",
                    List.of(
                            NewFileType.named("web.html", "HTML", "index", "html")
                                    .withTemplate(HTML),
                            NewFileType.named("web.css", "CSS", "style", "css"),
                            NewFileType.named("web.javascript", "JavaScript", "script", "js"),
                            NewFileType.named("web.typescript", "TypeScript", "script", "ts"),
                            NewFileType.named("web.jsx", "JavaScript (JSX)", "Component", "jsx"),
                            NewFileType.named("web.tsx", "TypeScript (TSX)", "Component", "tsx"),
                            NewFileType.named("web.xml", "XML", "untitled", "xml"),
                            NewFileType.named("web.svg", "SVG", "image", "svg"))),
            new Category(
                    "scripts",
                    List.of(
                            NewFileType.named("scripts.python", "Python", "script", "py"),
                            NewFileType.named("scripts.shell", "Shell", "script", "sh")
                                    .withTemplate(BASH),
                            NewFileType.named("scripts.zsh", "Zsh", "script", "zsh")
                                    .withTemplate(ZSH),
                            NewFileType.named("scripts.ruby", "Ruby", "script", "rb"),
                            NewFileType.named("scripts.powershell", "PowerShell", "script", "ps1"),
                            NewFileType.named("scripts.batch", "Batch", "script", "bat"),
                            NewFileType.named("scripts.lua", "Lua", "script", "lua"),
                            NewFileType.named("scripts.groovy", "Groovy", "script", "groovy"))),
            new Category(
                    "languages",
                    List.of(
                            NewFileType.named("lang.c", "C", "main", "c"),
                            NewFileType.named("lang.cpp", "C++", "main", "cpp"),
                            NewFileType.translated("lang.cheader", "header", "h"),
                            NewFileType.named("lang.csharp", "C#", "Program", "cs"),
                            NewFileType.named("lang.go", "Go", "main", "go"),
                            NewFileType.named("lang.rust", "Rust", "main", "rs"),
                            NewFileType.named("lang.kotlin", "Kotlin", "Main", "kt"),
                            NewFileType.named("lang.php", "PHP", "index", "php"))),
            new Category(
                    "data",
                    List.of(
                            NewFileType.named("data.json", "JSON", "data", "json"),
                            NewFileType.named("data.yaml", "YAML", "config", "yaml"),
                            NewFileType.named("data.toml", "TOML", "config", "toml"),
                            NewFileType.named("data.ini", "INI", "config", "ini"),
                            NewFileType.named("data.properties", "Properties", "config", "properties"),
                            NewFileType.named("data.csv", "CSV", "data", "csv"),
                            NewFileType.named("data.tsv", "TSV", "data", "tsv"),
                            NewFileType.named("data.sql", "SQL", "query", "sql"),
                            NewFileType.named("data.graphql", "GraphQL", "schema", "graphql"),
                            NewFileType.named("data.proto", "Protocol Buffers", "schema", "proto"),
                            NewFileType.named("data.dotenv", ".env", ".env", ""))),
            new Category(
                    "docs",
                    List.of(
                            NewFileType.named("docs.typst", "Typst", "document", "typ"),
                            NewFileType.named("docs.mermaid", "Mermaid", "diagram", "mmd"),
                            NewFileType.named("docs.dot", "Graphviz DOT", "diagram", "dot"),
                            NewFileType.named("docs.plantuml", "PlantUML", "diagram", "puml"),
                            NewFileType.named("docs.markwhen", "Markwhen", "timeline", "mw"),
                            NewFileType.translated("docs.http", "requests", "http"))),
            new Category(
                    "build",
                    List.of(
                            NewFileType.named("build.dockerfile", "Dockerfile", "Dockerfile", ""),
                            NewFileType.named("build.makefile", "Makefile", "Makefile", ""),
                            NewFileType.named("build.justfile", "justfile", "justfile", ""),
                            NewFileType.named("build.gitignore", ".gitignore", ".gitignore", ""),
                            NewFileType.named("build.editorconfig", ".editorconfig", ".editorconfig", ""))));

    /** The top-level entries shown directly on the "New ▸" menu, above the category submenus. */
    public static List<NewFileType> topLevel() {
        return List.of(TEXT, MARKDOWN);
    }

    /** The category submenus, in menu order. */
    public static List<Category> categories() {
        return CATEGORIES;
    }

    /** Every type, including {@link #PLAIN} and the top-level entries — the palette picker's list. */
    public static List<NewFileType> all() {
        return Stream.concat(
                        Stream.concat(Stream.of(PLAIN), topLevel().stream()),
                        CATEGORIES.stream().flatMap(c -> c.types().stream()))
                .toList();
    }

    private static final Map<String, NewFileType> BY_ID =
            all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(NewFileType::id, t -> t));

    /** The type with this id, or null. */
    public static NewFileType byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** The id of the category holding {@code type}, or null for {@link #PLAIN} / a top-level entry. */
    public static String categoryOf(NewFileType type) {
        for (Category c : CATEGORIES) {
            if (c.types().contains(type)) {
                return c.id();
            }
        }
        return null;
    }
}
