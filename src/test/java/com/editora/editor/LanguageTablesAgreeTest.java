package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.editor.language.LanguageRegistry;
import com.editora.editor.language.ShellFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The language table ({@link LanguageRegistry}: status bar, comments, Run, language servers) and the grammar table
 * ({@link GrammarRegistry}: colours) must agree on every extension. When they did not, a {@code .ksh} file or
 * {@code /etc/bash.bashrc} was coloured as shell but was "Plain Text" to everything else — no comment toggle, no
 * Run, no language server.
 */
class LanguageTablesAgreeTest {

    private static final GrammarRegistry GRAMMARS = GrammarRegistry.shared();

    @Test
    void everyExtensionWithAGrammarHasTheMatchingLanguage() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : GRAMMARS.extensionLanguages().entrySet()) {
            String file = "x." + e.getKey();
            String language = LanguageRegistry.forFileName(file);
            if (LanguageRegistry.PLAINTEXT.equals(language)) {
                problems.add("." + e.getKey() + " has the " + e.getValue() + " grammar but no language");
                continue;
            }
            // js/jsx reuse the TypeScript grammars under their own language names, which have no grammar of their
            // own; every other language with a grammar must be coloured by that grammar.
            if (GRAMMARS.availableLanguageNames().contains(language) && !language.equals(e.getValue())) {
                problems.add(
                        "." + e.getKey() + " is " + language + " but coloured with the " + e.getValue() + " grammar");
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void everyLanguageExtensionWhoseLanguageHasAGrammarIsColoured() {
        List<String> problems = new ArrayList<>();
        for (String ext : LanguageRegistry.extensions()) {
            String language = LanguageRegistry.forFileName("x." + ext);
            if (GRAMMARS.forLanguageName(language) != null && !GRAMMARS.hasGrammarFor("x." + ext)) {
                problems.add("." + ext + " is " + language + " but has no grammar");
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void shellExtensionsAreShellInBothTables() {
        for (String ext : ShellFiles.EXTENSIONS) {
            assertEquals("shell", LanguageRegistry.forFileName("x." + ext), ext);
            assertEquals("source.shell", GRAMMARS.forFileName("x." + ext).getScopeName(), ext);
        }
        // Real files that used to be coloured as shell yet called plain text.
        assertEquals("shell", LanguageRegistry.forFileName("/etc/bash.bashrc"));
        assertEquals("shell", LanguageRegistry.forFileName("/home/u/bin/backup.ksh"));
        // Bats test files and macOS .command scripts.
        assertEquals("shell", LanguageRegistry.forFileName("test/install.bats"));
        assertEquals("shell", LanguageRegistry.forFileName("/Users/u/Desktop/Start Server.command"));
        assertEquals("source.shell", GRAMMARS.forFileName("x.bats").getScopeName());
    }

    @Test
    void jvmLanguageExtensionsAgreeInBothTables() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("scala", "scala"),
                Map.entry("sc", "scala"),
                Map.entry("sbt", "scala"),
                Map.entry("mill", "scala"),
                Map.entry("clj", "clojure"),
                Map.entry("cljs", "clojure"),
                Map.entry("cljc", "clojure"),
                Map.entry("cljx", "clojure"),
                Map.entry("edn", "clojure"),
                Map.entry("bb", "clojure"),
                Map.entry("groovy", "groovy"),
                Map.entry("kts", "kotlin"));
        expected.forEach((ext, language) -> {
            assertEquals(language, LanguageRegistry.forFileName("x." + ext), ext);
            assertEquals(language, GRAMMARS.extensionLanguages().get(ext), ext);
        });
    }
}
