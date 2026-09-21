package com.editora.agent.runtime;

import java.net.URI;
import java.util.*;
import javax.tools.*;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/** Parse only: no annotation processors, compilation, class loading or project build scripts. */
final class AgentJavaDeclarations {
    private AgentJavaDeclarations() {}

    static Set<String> methods(String source) {
        return bodies(source).keySet();
    }

    static Map<String, String> bodies(String source) {
        if (source.length() > AgentWorkspace.MAX_FILE_CHARS) return Map.of();
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return Map.of();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///AgentEvidence.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var names = new LinkedHashMap<String, String>();
        var ambiguous = new HashSet<String>();
        try (var manager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask)
                    compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(file));
            var scanner = new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree method, Void ignored) {
                    if (!method.getName().contentEquals("<init>") && method.getBody() != null && names.size() < 2048) {
                        String name = method.getName().toString();
                        if (names.put(name, method.getBody().toString()) != null) ambiguous.add(name);
                    }
                    return super.visitMethod(method, ignored);
                }
            };
            for (var unit : task.parse()) scanner.scan(unit, null);
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                return Map.of();
            ambiguous.forEach(names::remove);
            return Map.copyOf(names);
        } catch (Exception unavailable) {
            return Map.of();
        }
    }
}
