package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.editops.Commenter;
import com.editora.editor.language.LanguageRegistry;
import com.editora.editor.language.Shebang;
import com.editora.editor.spell.SpellMode;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The files a Python user opens every day get a language (review findings A8 and B14's detection half): they
 * used to open as plain text, prose-spell-checked, with no highlighting.
 */
class PythonFileDetectionTest {

    @Test
    void pythonInterpretersAndRunnersInAShebang() {
        for (String line : List.of(
                "#!/usr/bin/env python3",
                "#!/usr/bin/env -S uv run --script",
                "#!/usr/bin/env -S uv run",
                "#!/usr/bin/env -S pipx run",
                "#!/usr/bin/env -S poetry run python",
                "#!/usr/bin/env -S pdm run python3.12",
                "#!/usr/bin/env -S hatch run python",
                "#!/usr/bin/env pypy3",
                "#!/usr/bin/pypy",
                "#!/usr/local/bin/python3.14t",
                "#!/usr/bin/env python3-dbg",
                "#!/usr/bin/python3.12-dbg")) {
            assertEquals("py", Shebang.extension(line), line);
        }
        assertEquals(null, Shebang.extension("#!/usr/bin/env -S poetry run pytest"), "pytest is not Python source");
        assertEquals(null, Shebang.extension("#!/usr/bin/env -S uv pip install"));
        assertEquals("sh", Shebang.extension("#!/bin/bash"));
    }

    @Test
    void packagingAndNotebookFilesGetTheirFormat() {
        assertEquals("pip-requirements", LanguageRegistry.forFileName("requirements.txt"));
        assertEquals("pip-requirements", LanguageRegistry.forFileName("requirements-dev.txt"));
        assertEquals("pip-requirements", LanguageRegistry.forFileName("dev-requirements.txt"));
        assertEquals("pip-requirements", LanguageRegistry.forFileName("requirements.in"));
        assertEquals("pip-requirements", LanguageRegistry.forFileName("constraints.txt"));
        assertEquals("pip-requirements", LanguageRegistry.forFileName("/p/requirements/base.txt"));
        assertEquals("toml", LanguageRegistry.forFileName("Pipfile"));
        assertEquals("toml", LanguageRegistry.forFileName("poetry.lock"));
        assertEquals("toml", LanguageRegistry.forFileName("uv.lock"));
        assertEquals("toml", LanguageRegistry.forFileName("pdm.lock"));
        assertEquals("json", LanguageRegistry.forFileName("Pipfile.lock"));
        assertEquals("json", LanguageRegistry.forFileName("analysis.ipynb"));
        for (String name : List.of("requirements.txt", "Pipfile", "uv.lock", "Pipfile.lock", "nb.ipynb")) {
            assertTrue(GrammarRegistry.shared().hasGrammarFor(name), name);
        }
        assertEquals("plaintext", LanguageRegistry.forFileName("notes.txt"));
    }

    @Test
    void aRequirementsFileHighlightsAndOnlyItsCommentsAreSpellChecked() {
        IGrammar g = GrammarRegistry.shared().forLanguageName("pip-requirements");
        assertNotNull(g);
        List<String> tokens = tokens(
                g,
                "# core: recieve\n-r dev.txt\nfastapi[standard]>=0.110,<1 ; python_version < \"3.13\"  # web\n"
                        + "httpx @ https://example.invalid/x.whl\n--index-url https://example.invalid/simple");
        assertTrue(tokens.contains("# core: recieve|comment"), tokens.toString());
        assertTrue(tokens.contains("-r|keyword"), tokens.toString());
        assertTrue(tokens.contains("fastapi|type"), tokens.toString());
        assertTrue(tokens.contains("0.110|number"), tokens.toString());
        assertTrue(tokens.contains("python_version|keyword"), tokens.toString());
        assertTrue(tokens.contains("# web|comment"), tokens.toString());
        assertTrue(tokens.contains("https://example.invalid/x.whl|link"), tokens.toString());
        assertTrue(tokens.contains("https://example.invalid/simple|link"), tokens.toString());
        SpellMode mode = SpellMode.forLanguage("pip-requirements");
        assertTrue(mode.eligible(List.of("comment")));
        assertFalse(mode.eligible(List.of("type")), "a package name is not prose");
        assertEquals("#", Commenter.styleFor("pip-requirements").line());
    }

    @Test
    void anIniFileThatUsesHashCommentsTogglesWithHash() {
        String toxIni = "# tox configuration\n[tox]\nenvlist = py312\n";
        Commenter.Edit edit = Commenter.toggle(toxIni, toxIni.indexOf("envlist"), toxIni.indexOf("envlist"), "ini");
        String after = toxIni.substring(0, edit.from()) + edit.replacement() + toxIni.substring(edit.to());
        assertEquals("# tox configuration\n[tox]\n# envlist = py312\n", after);
        String plain = "; settings\n[a]\nb = 1\n";
        Commenter.Edit semi = Commenter.toggle(plain, plain.indexOf("b ="), plain.indexOf("b ="), "ini");
        assertTrue(semi.replacement().startsWith(";"), "a file with ; comments keeps ;");
    }

    private static List<String> tokens(IGrammar g, String source) {
        List<String> out = new ArrayList<>();
        IStateStack state = null;
        for (String line : source.split("\n", -1)) {
            var result = g.tokenizeLine(line + "\n", state, Duration.ofSeconds(5));
            state = result.getRuleStack();
            for (IToken t : result.getTokens()) {
                int s = Math.min(t.getStartIndex(), line.length());
                int e = Math.min(t.getEndIndex(), line.length());
                String text = line.substring(s, e).strip();
                if (!text.isEmpty()) {
                    out.add(text + "|" + TextMateHighlighter.styleForScopes(t.getScopes()));
                }
            }
        }
        return out;
    }
}
