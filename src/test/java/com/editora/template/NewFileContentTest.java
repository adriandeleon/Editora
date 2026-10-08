package com.editora.template;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure "New ▸ &lt;type&gt;" core: what the typed name means, where the file lands,
 * what goes in it. These are the rules that turn a typed string into a path on disk, so the refusals
 * (a {@code ..} segment, an absolute name) are pinned as hard as the happy paths.
 */
class NewFileContentTest {

    private static NewFileType type(String id) {
        NewFileType t = NewFileCatalog.byId(id);
        assertTrue(t != null, "no such catalog type: " + id);
        return t;
    }

    // --- plan: plain types ------------------------------------------------------------------------

    @Test
    void blankInputFallsBackToTheTypesSuggestedName() {
        NewFileContent.Plan plan = NewFileContent.plan(NewFileCatalog.TEXT, "   ", "");
        assertEquals("untitled.txt", plan.relativePath());
        assertEquals("untitled", plan.baseName());
    }

    @Test
    void theExtensionIsAppendedOnlyWhenTheTypedNameHasNone() {
        assertEquals(
                "notes.txt",
                NewFileContent.plan(NewFileCatalog.TEXT, "notes", "").relativePath());
        // An explicit extension wins: typing notes.json under "Text File" gives JSON, not notes.json.txt.
        assertEquals(
                "notes.json",
                NewFileContent.plan(NewFileCatalog.TEXT, "notes.json", "").relativePath());
        // A leading dot is a dotfile, not an extensionless name.
        assertEquals(
                ".gitignore",
                NewFileContent.plan(NewFileCatalog.TEXT, ".gitignore", "").relativePath());
    }

    @Test
    void anExtensionlessTypeUsesTheNameVerbatim() {
        NewFileContent.Plan plan = NewFileContent.plan(type("build.dockerfile"), "", "");
        assertEquals("Dockerfile", plan.relativePath());
    }

    @Test
    void aRelativeSubPathIsAllowedAndItsLastSegmentIsTheFile() {
        NewFileContent.Plan plan = NewFileContent.plan(NewFileCatalog.MARKDOWN, "docs/guide", "");
        assertEquals("docs/guide.md", plan.relativePath());
        assertEquals("guide.md", plan.fileName());
        assertEquals("guide", plan.baseName());
    }

    @Test
    void anEscapingOrAbsoluteNameIsRefused() {
        assertNull(NewFileContent.plan(NewFileCatalog.TEXT, "../outside", ""));
        assertNull(NewFileContent.plan(NewFileCatalog.TEXT, "a/../../outside", ""));
        assertNull(NewFileContent.plan(NewFileCatalog.TEXT, "/etc/passwd", ""));
        assertNull(NewFileContent.plan(NewFileCatalog.TEXT, "..\\outside", ""));
        assertNull(NewFileContent.plan(NewFileCatalog.TEXT, "C:\\Windows\\x", ""));
    }

    // --- plan: Java kinds -------------------------------------------------------------------------

    @Test
    void aSimpleJavaNameLandsInTheFoldersOwnPackage() {
        NewFileContent.Plan plan = NewFileContent.plan(type("java.class"), "Slug", "com.demo");
        assertEquals("Slug.java", plan.relativePath());
        assertEquals("Slug", plan.baseName());
        assertEquals("com.demo", plan.packageName());
    }

    @Test
    void aQualifiedJavaNameCreatesSubPackages() {
        NewFileContent.Plan plan = NewFileContent.plan(type("java.class"), "text.Slug", "com.demo");
        assertEquals("text/Slug.java", plan.relativePath());
        assertEquals("Slug", plan.baseName());
        assertEquals("com.demo.text", plan.packageName());
    }

    @Test
    void aTrailingJavaExtensionAndSlashFormAreBothAccepted() {
        assertEquals(
                "text/Slug.java",
                NewFileContent.plan(type("java.class"), "text/Slug.java", "").relativePath());
        assertEquals(
                "Slug.java",
                NewFileContent.plan(type("java.class"), "Slug.JAVA", "").relativePath());
    }

    @Test
    void aJavaNameThatIsNotAnIdentifierIsRefused() {
        // `..` collapses to empty segments rather than escaping, and an empty segment is refused too —
        // creating Escape.java from a name that tried to climb out would be a silent surprise.
        assertNull(NewFileContent.plan(type("java.class"), "../Escape", ""));
        assertNull(NewFileContent.plan(type("java.class"), "a..b.Escape", ""));
        assertNull(NewFileContent.plan(type("java.class"), "Trailing.", ""));
        assertNull(NewFileContent.plan(type("java.class"), "my-class", ""));
        assertNull(NewFileContent.plan(type("java.class"), "9Lives", ""));
    }

    @Test
    void packageInfoAndModuleInfoAreAcceptedDespiteTheirHyphen() {
        // The two legal Java file names that are not identifiers — and only as the final segment.
        NewFileContent.Plan plan = NewFileContent.plan(type("java.packageInfo"), "package-info", "com.demo");
        assertEquals("package-info.java", plan.relativePath());
        assertEquals("com.demo", plan.packageName());
        assertEquals(
                "module-info.java",
                NewFileContent.plan(type("java.class"), "module-info", "").relativePath());
        assertNull(NewFileContent.plan(type("java.class"), "package-info.Nested", ""));
    }

    @Test
    void aJavaFileOutsideASourceRootHasNoPackage() {
        assertEquals("", NewFileContent.plan(type("java.class"), "Slug", "").packageName());
    }

    // --- package inference ------------------------------------------------------------------------

    @Test
    void thePackageComesFromTheFoldersPositionUnderASourceRoot() {
        assertEquals("demo", NewFileContent.packageFor(Path.of("/w/proj/src/main/java/demo")));
        assertEquals("com.demo.util", NewFileContent.packageFor(Path.of("/w/proj/src/main/java/com/demo/util")));
        assertEquals("demo", NewFileContent.packageFor(Path.of("/w/proj/src/test/java/demo")));
        // The source root itself is the default package.
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src/main/java")));
    }

    @Test
    void aPlainSrcLayoutYieldsAPackageOnlyDirectlyUnderTheProjectRoot() {
        Path root = Path.of("/w/proj");
        assertEquals("demo", NewFileContent.packageFor(Path.of("/w/proj/src/demo"), root));
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src"), root));
        // With no project there is nothing to say which `src` is a source root: only the markers count.
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src/demo")));
        // A `src` deeper in the project is a folder name, not a source root.
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/tools/src/demo"), root));
    }

    @Test
    void nothingAtOrAboveTheProjectRootIsEverAPackage() {
        // T4: projects kept under ~/src must not turn the path to the project into a package.
        Path root = Path.of("/home/adl/src/adl/foo");
        assertEquals("", NewFileContent.packageFor(Path.of("/home/adl/src/adl/foo/app"), root));
        assertEquals("", NewFileContent.packageFor(root, root));
        assertEquals("", NewFileContent.packageFor(Path.of("/home/adl/src/adl/foo/app")));
        assertEquals("com.x", NewFileContent.packageFor(Path.of("/home/adl/src/adl/foo/src/main/java/com/x"), root));
        // A project that itself lives under some src/main/java (a fixture) starts from its own root.
        Path nested = Path.of("/w/outer/src/main/java/fixture");
        assertEquals("", NewFileContent.packageFor(Path.of("/w/outer/src/main/java/fixture/docs"), nested));
        assertEquals("app", NewFileContent.packageFor(Path.of("/w/outer/src/main/java/fixture/src/app"), nested));
    }

    @Test
    void aMavenLayoutsOtherFoldersAreNotPackages() {
        Path root = Path.of("/w/proj");
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src/main/resources/com"), root));
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src/main/resources/com")));
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src/test/resources"), root));
        assertEquals("", NewFileContent.packageFor(Path.of("/w/proj/src/main/kotlin/a"), root));
        // A module's own source root, below the project root, still counts.
        assertEquals("a.b", NewFileContent.packageFor(Path.of("/w/proj/core/src/main/java/a/b"), root));
    }

    @Test
    void aFolderOutsideTheProjectRootFallsBackToTheMarkers() {
        Path root = Path.of("/w/proj");
        assertEquals("demo", NewFileContent.packageFor(Path.of("/w/other/src/main/java/demo"), root));
        assertEquals("", NewFileContent.packageFor(Path.of("/w/other/src/demo"), root));
    }

    @Test
    void aKeywordFolderEndsThePackage() {
        assertEquals("com", NewFileContent.packageFor(Path.of("/w/proj/src/main/java/com/int/x")));
    }

    @Test
    void aFolderWithNoSourceRootAboveItHasNoPackage() {
        assertEquals("", NewFileContent.packageFor(Path.of("/w/notes/drafts")));
        assertEquals("", NewFileContent.packageFor(null));
    }

    @Test
    void theWalkStopsAtAFolderThatCannotBeAPackageName() {
        // "my-notes" is not a Java identifier, so nothing below it is a package either.
        assertEquals("com", NewFileContent.packageFor(Path.of("/w/proj/src/main/java/com/my-notes")));
    }

    @Test
    void theInnermostSourceRootWins() {
        // A nested project (a fixture checkout inside src/test/java) belongs to the inner root.
        assertEquals("demo", NewFileContent.packageFor(Path.of("/w/proj/src/test/java/fixture/src/main/java/demo")));
    }

    // --- rendering --------------------------------------------------------------------------------

    @Test
    void aJavaClassGetsItsPackageNameAndCaret() {
        NewFileContent.Rendered r = NewFileContent.render(type("java.class"), "Slug", "com.demo");
        // Written as a concatenation, not a text block: the body line keeps its indent so the caret
        // lands at the column the user types at (what every IDE does), and a text block would strip it.
        assertEquals("package com.demo;\n\npublic class Slug {\n\n    \n}\n", r.text());
        // The caret sits on that indented body line, not at the end of the file.
        assertEquals(r.text().indexOf("    \n}") + 4, r.caret());
    }

    @Test
    void noPackageMeansNoDeclarationLine() {
        NewFileContent.Rendered r = NewFileContent.render(type("java.record"), "Point", "");
        assertEquals("public record Point() {}\n", r.text());
        assertEquals(r.text().indexOf("()") + 1, r.caret());
    }

    @Test
    void anEmptyTemplateRendersAnEmptyFile() {
        NewFileContent.Rendered r = NewFileContent.render(NewFileCatalog.TEXT, "notes", "");
        assertEquals("", r.text());
        assertEquals(0, r.caret());
    }

    @Test
    void renderedTextEndsWithExactlyOneNewlineAndNoLeftoverTokens() {
        for (NewFileType t : NewFileCatalog.all()) {
            NewFileContent.Rendered r = NewFileContent.render(t, "Name", "pkg");
            assertFalse(r.text().contains("{cursor}"), t.id() + " kept its cursor token");
            assertFalse(r.text().contains("{name}"), t.id() + " kept its name token");
            assertFalse(r.text().contains("{package}"), t.id() + " kept its package token");
            if (!r.text().isEmpty()) {
                assertTrue(r.text().endsWith("\n"), t.id() + " does not end with a newline");
                assertFalse(r.text().endsWith("\n\n"), t.id() + " ends with a blank line");
            }
            assertTrue(r.caret() >= 0 && r.caret() <= r.text().length(), t.id() + " has an out-of-range caret");
        }
    }

    @Test
    void packageInfoCarriesItsDeclaration() {
        NewFileContent.Rendered r = NewFileContent.render(type("java.packageInfo"), "package-info", "com.demo");
        assertTrue(r.text().contains("package com.demo;"), r.text());
        assertTrue(r.text().startsWith("/**"), r.text());
    }

    @Test
    void everyPlannedNameIsAlsoAUsableFileName() {
        for (NewFileType t : NewFileCatalog.all()) {
            if (t == NewFileCatalog.PLAIN) {
                continue; // no suggestion: whatever the user types decides the type
            }
            // "pkg": package-info.java is only meaningful inside a package.
            NewFileContent.Plan plan = NewFileContent.plan(t, "", "pkg");
            assertTrue(plan != null, t.id() + " has no plan for its own suggested name");
            assertFalse(plan.fileName().isBlank(), t.id() + " plans a blank file name");
        }
    }

    @Test
    void theGenericEntryNeedsATypedName() {
        assertNull(NewFileContent.plan(NewFileCatalog.PLAIN, "", ""));
        assertEquals(
                "anything.xyz",
                NewFileContent.plan(NewFileCatalog.PLAIN, "anything.xyz", "").relativePath());
    }

    // --- name edge cases (T14) --------------------------------------------------------------------

    private static NewFileContent.Refusal refusal(NewFileType t, String input, String pkg) {
        NewFileContent.Decision d = NewFileContent.decide(t, input, pkg);
        assertNull(d.plan(), input + " should be refused, got " + d.plan());
        return d.refusal();
    }

    @Test
    void aDotInTheNameKeepsTheTypesExtensionUnlessTheSuffixIsAKnownOne() {
        assertEquals(
                "release-1.2.md",
                NewFileContent.plan(NewFileCatalog.MARKDOWN, "release-1.2", "").relativePath());
        assertEquals(
                "notes.v2.md",
                NewFileContent.plan(NewFileCatalog.MARKDOWN, "notes.v2", "").relativePath());
        assertEquals(
                "notes.md",
                NewFileContent.plan(NewFileCatalog.MARKDOWN, "notes.md", "").relativePath());
        assertEquals(
                "data.yaml",
                NewFileContent.plan(NewFileCatalog.MARKDOWN, "data.yaml", "").relativePath());
        assertEquals(
                "run.sh", NewFileContent.plan(NewFileCatalog.TEXT, "run.sh", "").relativePath());
        assertEquals(
                "release-1.2",
                NewFileContent.plan(NewFileCatalog.MARKDOWN, "release-1.2", "").baseName());
    }

    @Test
    void aJavaTypeNamedWithAnotherExtensionIsRefusedNotTurnedIntoAPackage() {
        // "Foo.txt" used to become Foo/txt.java.
        assertEquals(NewFileContent.Refusal.NOT_A_JAVA_FILE, refusal(type("java.class"), "Foo.txt", "base"));
        assertEquals(NewFileContent.Refusal.NOT_A_JAVA_FILE, refusal(type("java.class"), "notes.md", ""));
        // A real qualified name is untouched.
        assertEquals(
                "util/text/Slug.java",
                NewFileContent.plan(type("java.class"), "util.text.Slug", "").relativePath());
    }

    @Test
    void javaKeywordsAreRefusedAsTypeAndPackageNames() {
        for (String bad : new String[] {"class", "int", "new", "true", "null", "util.package.Slug", "var", "record"}) {
            assertEquals(NewFileContent.Refusal.JAVA_KEYWORD, refusal(type("java.class"), bad, ""), bad);
        }
        // Restricted identifiers are fine as package segments.
        assertEquals(
                "record/Slug.java",
                NewFileContent.plan(type("java.class"), "record.Slug", "").relativePath());
        assertEquals(NewFileContent.Refusal.NOT_A_JAVA_NAME, refusal(type("java.class"), "my-class", ""));
    }

    @Test
    void aTrailingSlashIsAFolderNotAFile() {
        assertEquals(NewFileContent.Refusal.INVALID_NAME, refusal(NewFileCatalog.TEXT, "sub/", ""));
        assertEquals(NewFileContent.Refusal.INVALID_NAME, refusal(NewFileCatalog.TEXT, "a//b", ""));
    }

    @Test
    void theHomeShorthandIsRefusedInsteadOfCreatingATildeFolder() {
        assertEquals(NewFileContent.Refusal.HOME_SHORTHAND, refusal(NewFileCatalog.PLAIN, "~/x.txt", ""));
        assertEquals(NewFileContent.Refusal.HOME_SHORTHAND, refusal(type("java.class"), "~/Foo", ""));
        // A tilde inside a name is just a character.
        assertEquals(
                "backup~.txt",
                NewFileContent.plan(NewFileCatalog.PLAIN, "backup~.txt", "").relativePath());
    }

    @Test
    void namesNoPlatformNeutralFileMayHaveAreRefusedOnEveryOs() {
        for (String reserved : new String[] {"CON", "nul", "com1.txt", "sub/LPT9", "aux"}) {
            assertEquals(NewFileContent.Refusal.RESERVED_NAME, refusal(NewFileCatalog.TEXT, reserved, ""), reserved);
        }
        assertEquals(NewFileContent.Refusal.RESERVED_NAME, refusal(type("java.class"), "Con", ""));
        for (String bad : new String[] {"a?b", "a*b", "x\ty", "a<b", "a|b", "quo\"te", "dir./x", "C:x"}) {
            assertEquals(NewFileContent.Refusal.ILLEGAL_CHARACTER, refusal(NewFileCatalog.PLAIN, bad, ""), bad);
        }
        assertEquals(NewFileContent.Refusal.ILLEGAL_CHARACTER, refusal(NewFileCatalog.PLAIN, "a.", ""));
        // Spaces typed around the whole name are trimmed, not an error; inside a name they are legal.
        assertEquals(
                "a b.txt", NewFileContent.plan(NewFileCatalog.TEXT, " a b ", "").relativePath());
    }

    @Test
    void packageInfoNeedsAPackage() {
        assertEquals(NewFileContent.Refusal.NO_PACKAGE, refusal(type("java.packageInfo"), "package-info", ""));
        assertEquals(
                "sub/package-info.java",
                NewFileContent.plan(type("java.packageInfo"), "sub.package-info", "")
                        .relativePath());
    }
}
