package com.editora.editor;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.fxmisc.richtext.model.StyleSpan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bundled grammar must actually tokenize. A grammar <em>loads</em> even when one of its regexes is
 * rejected by the joni backend (regexes compile lazily), and {@link TextMateHighlighter} degrades a line
 * that throws to plain text — so a single rejected look-behind in the Ruby grammar's root pattern list left
 * every Ruby file unhighlighted, silently, from the day the grammar was bundled. {@code GrammarRegistryTest}
 * only proves the grammars load; this proves they run.
 */
class BundledGrammarsTokenizeTest {

    /** Lines every grammar must survive: its root scanner is compiled on the first one. */
    private static final List<String> GENERIC = List.of(
            "# comment", "name = \"value\" // x", "def f(a, b) { return a + 1; }", "<tag attr='1'>text</tag>", "");

    /** Samples that enter the rules whose regexes were rejected (and a few neighbours). */
    private static final Map<String, String> SAMPLES = Map.of(
            "ruby",
            "class Foo\n  def bar(x)\n    [1, 2].each { |i| puts \"#{i}\" }\n    list.each do |item|\n"
                    + "      p item # note\n    end\n  end\nend\n",
            "desktop",
            "[Desktop Entry]\n# A launcher\nType=Application\nVersion=1.0\nName[es]=Editora\n"
                    + "Exec=editora --new-window %F\nIcon=editora\nTerminal=false\nCategories=Development;TextEditor;\n",
            "typescript",
            "function f() {\n  using res = open();\n  await using conn = connect();\n  const x = 1;\n}\n",
            "typescriptreact",
            "function f() {\n  using res = open();\n  await using conn = connect();\n  return <div/>;\n}\n",
            "markdown",
            "# T\n\n```ruby\nputs [1].map { |i| i }\n```\n\nAfter **bold**.\n\n```python\nx = 1\n```\n",
            "python",
            "def f(a):\n    s = r'''x'''\n    return f\"{a}\"\n",
            "shell",
            "cat <<EOF\nhello $USER\nEOF\nif [ -f x ]; then echo \"y\"; fi\n");

    private static List<String> failures(IGrammar grammar, String text) {
        List<String> failures = new ArrayList<>();
        IStateStack state = null;
        for (String line : text.split("\n", -1)) {
            try {
                state = grammar.tokenizeLine(line + "\n", state, Duration.ZERO).getRuleStack();
            } catch (Exception | LinkageError e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                failures.add("`" + line + "` -> " + root);
            }
        }
        return failures;
    }

    @Test
    void everyBundledGrammarTokenizesWithoutThrowing() {
        GrammarRegistry registry = GrammarRegistry.shared();
        List<String> names = new ArrayList<>(new TreeSet<>(registry.availableLanguageNames()));
        assertTrue(names.size() > 40, "the bundled grammars are discovered: " + names);
        assertTrue(names.containsAll(SAMPLES.keySet()), "every sample names a bundled grammar: " + names);
        List<String> broken = new ArrayList<>();
        for (String name : names) {
            IGrammar grammar = registry.forLanguageName(name);
            assertNotNull(grammar, name + " loads");
            String text = String.join("\n", GENERIC) + "\n" + SAMPLES.getOrDefault(name, "");
            for (String failure : failures(grammar, text)) {
                broken.add(name + ": " + failure);
            }
        }
        assertEquals(List.of(), broken, "grammars that throw while tokenizing");
    }

    /**
     * The static half: every {@code match}/{@code begin}/{@code end}/{@code while} regex of every grammar
     * compiles with the class tm4e itself uses. This reaches rules a short sample never enters. An
     * {@code end}/{@code while} holding a back-reference to its {@code begin} is skipped — tm4e substitutes
     * the captured text before compiling, so it is not a regex until then.
     */
    @Test
    void everyBundledGrammarRegexCompiles() throws Exception {
        Constructor<?> onig = Class.forName("org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp")
                .getDeclaredConstructor(String.class);
        onig.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();
        List<String> rejected = new ArrayList<>();
        int[] compiled = {0};
        for (String name : new TreeSet<>(GrammarRegistry.shared().availableLanguageNames())) {
            String resource = "/com/editora/grammars/" + name + ".tmLanguage.json";
            try (InputStream in = GrammarRegistry.class.getResourceAsStream(resource)) {
                assertNotNull(in, resource);
                walk(mapper.readTree(in), name, onig, rejected, compiled);
            }
        }
        assertTrue(compiled[0] > 5000, "the grammars were actually scanned: " + compiled[0]);
        assertEquals(List.of(), rejected, "regexes the joni backend rejects");
    }

    private static void walk(JsonNode node, String name, Constructor<?> onig, List<String> rejected, int[] compiled)
            throws ReflectiveOperationException {
        if (node.isObject()) {
            for (String key : List.of("match", "begin", "end", "while")) {
                JsonNode pattern = node.get(key);
                if (pattern == null || !pattern.isTextual()) {
                    continue;
                }
                String regex = pattern.asText();
                boolean substituted = (key.equals("end") || key.equals("while")) && regex.matches("(?s).*\\\\\\d.*");
                if (substituted) {
                    continue;
                }
                compiled[0]++;
                try {
                    onig.newInstance(regex);
                } catch (InvocationTargetException e) {
                    Throwable root = e.getCause();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    rejected.add(name + " [" + key + "] " + root.getMessage() + ": "
                            + regex.substring(0, Math.min(120, regex.length())));
                }
            }
        }
        for (JsonNode child : node) {
            walk(child, name, onig, rejected, compiled);
        }
    }

    /** The rewritten patterns still do their job — not merely "no exception". */
    @Test
    void theRewrittenRubyAndDesktopPatternsStillHighlight() {
        assertTrue(classesOf("a.rb", SAMPLES.get("ruby")).containsAll(List.of("keyword", "string", "comment")));

        String desktop = SAMPLES.get("desktop");
        TextMateHighlighter.Analysis analysis =
                TextMateHighlighter.analyze(desktop, GrammarRegistry.shared().forFileName("app.desktop"));
        // The Exec/Version/Categories line rules must close at their line end: the line after each is a key.
        for (String key : List.of("Icon", "Name", "Terminal")) {
            assertTrue(!styleAt(analysis, desktop.indexOf("\n" + key) + 1).isEmpty(), key + " is styled as a key");
        }
        assertTrue(!styleAt(analysis, desktop.indexOf("--new-window")).isEmpty(), "an Exec option is styled");
        assertTrue(!styleAt(analysis, desktop.indexOf("Development")).isEmpty(), "a known category is styled");
        assertTrue(!styleAt(analysis, desktop.indexOf("=Application")).isEmpty(), "the key's = is styled");
        assertTrue(styleAt(analysis, desktop.indexOf("Application")).isEmpty(), "a plain value is not");
    }

    /** A swallowed tokenizer failure is logged once per grammar instead of vanishing. */
    @Test
    void aTokenizerFailureIsReportedOncePerGrammar() {
        IGrammar real = GrammarRegistry.shared().forLanguageName("json");
        IGrammar throwing = (IGrammar) java.lang.reflect.Proxy.newProxyInstance(
                IGrammar.class.getClassLoader(), new Class<?>[] {IGrammar.class}, (proxy, method, args) -> {
                    if (method.getName().equals("tokenizeLine")) {
                        throw new IllegalStateException("invalid pattern in look-behind");
                    }
                    if (method.getName().equals("getScopeName")) {
                        return "source.test-broken";
                    }
                    return method.invoke(real, args);
                });
        TextMateHighlighter.reportedFailures().remove("source.test-broken");
        List<java.util.logging.LogRecord> records = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(TextMateHighlighter.class.getName());
        log.addHandler(handler);
        boolean parent = log.getUseParentHandlers();
        log.setUseParentHandlers(false);
        try {
            TextMateHighlighter.analyze("a\nb\nc\n", throwing); // three failing lines …
            TextMateHighlighter.analyze("a\n", throwing); // … and a second pass
        } finally {
            log.removeHandler(handler);
            log.setUseParentHandlers(parent);
        }
        assertEquals(1, records.size(), "one record for the grammar, not one per line");
        assertTrue(records.get(0).getMessage().contains("source.test-broken"));
        assertNotNull(records.get(0).getThrown());
    }

    private static Collection<String> classesOf(String file, String text) {
        TextMateHighlighter.Analysis analysis =
                TextMateHighlighter.analyze(text, GrammarRegistry.shared().forFileName(file));
        List<String> classes = new ArrayList<>();
        for (StyleSpan<Collection<String>> span : analysis.spans()) {
            classes.addAll(span.getStyle());
        }
        return classes;
    }

    private static Collection<String> styleAt(TextMateHighlighter.Analysis analysis, int offset) {
        int pos = 0;
        for (StyleSpan<Collection<String>> span : analysis.spans()) {
            if (offset < pos + span.getLength()) {
                return span.getStyle();
            }
            pos += span.getLength();
        }
        return List.of();
    }
}
