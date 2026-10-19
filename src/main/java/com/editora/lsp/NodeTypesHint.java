package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Whether a JavaScript file deserves the one-time hint that Node.js APIs have no types: it uses Node's built-in
 * modules or globals, and no {@code @types/node} is installed where TypeScript would find it. Without one, and
 * with automatic type acquisition off (the default), hover says {@code any} and {@code fs.} completes only words
 * from the file, with nothing saying why (Node review B9). Pure apart from {@link #hasNodeTypes(Path)}.
 */
public final class NodeTypesHint {

    private NodeTypesHint() {}

    /** How much of a file is looked at: the imports are at the top. */
    static final int SCAN_CHARS = 20_000;

    private static final String BUILTINS = "assert|buffer|child_process|cluster|crypto|dgram|dns|events|fs|fs/promises"
            + "|http|http2|https|net|os|path|perf_hooks|process|readline|stream|string_decoder|timers|tls|url|util"
            + "|v8|vm|worker_threads|zlib|test";

    private static final Pattern NODE_USE = Pattern.compile("require\\(\\s*['\"](?:node:[^'\"]+|(?:" + BUILTINS
            + ")(?:/[^'\"]*)?)['\"]"
            + "|from\\s+['\"](?:node:[^'\"]+|(?:" + BUILTINS + ")(?:/[^'\"]*)?)['\"]"
            + "|import\\(\\s*['\"]node:[^'\"]+['\"]"
            + "|\\b__dirname\\b|\\b__filename\\b|\\bprocess\\.(?:argv|env|exit|cwd|stdin|stdout|stderr)\\b");

    /** Whether {@code text} (its first {@link #SCAN_CHARS} characters) uses Node.js built-ins. */
    public static boolean usesNodeApis(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return NODE_USE.matcher(text.length() > SCAN_CHARS ? text.substring(0, SCAN_CHARS) : text)
                .find();
    }

    /** Whether a {@code node_modules/@types/node} is in {@code file}'s folder or any folder above it. */
    public static boolean hasNodeTypes(Path file) {
        return hasNodeTypes(file, Files::isRegularFile);
    }

    static boolean hasNodeTypes(Path file, Predicate<Path> isFile) {
        if (file == null) {
            return false;
        }
        for (Path dir = file.toAbsolutePath().normalize().getParent(); dir != null; dir = dir.getParent()) {
            if (isFile.test(dir.resolve("node_modules/@types/node/package.json"))) {
                return true;
            }
        }
        return false;
    }
}
