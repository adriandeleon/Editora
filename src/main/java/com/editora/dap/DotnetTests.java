package com.editora.dap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Debug Test at Caret in C# (Visual Studio's and Rider's Debug Test, VS Code C# Dev Kit's): {@code dotnet test} runs the
 * test at the caret — or its class's, outside any — filtered to it ({@link #filter}), with {@code VSTEST_HOST_DEBUG=1},
 * so the test host stops before running anything and prints its process id ({@link #processId}); netcoredbg then
 * attaches to it, and the test runs under the debugger. Works for xUnit, NUnit and MSTest, which all run on VSTest.
 * Pure.
 */
public final class DotnetTests {

    /**
     * A test at the caret: its namespace ("" for none), class (a nested one as VSTest names it, {@code Outer+Inner}),
     * and method (null for the whole class).
     */
    public record Target(
            String namespace, String className, @Nullable String method) {
        /** The class's full name ({@code Shop.Tests.PriceTests}). */
        public String fullClassName() {
            return namespace.isEmpty() ? className : namespace + "." + className;
        }

        /** What the Debug window names the session: the method, else the class (a nested one by its own name). */
        public String displayName() {
            String simple = className.substring(className.lastIndexOf('+') + 1);
            return method == null ? simple : simple + "." + method;
        }
    }

    /** The attributes that make a method a test: xUnit's, NUnit's, MSTest's. */
    private static final Pattern TEST_ATTRIBUTE = Pattern.compile(
            "\\[\\s*(?:[\\w.]+\\.)?(Fact|Theory|Test|TestCase|TestCaseSource|TestMethod|DataTestMethod)\\b");

    private static final Pattern NAMESPACE = Pattern.compile("^\\s*namespace\\s+([\\w.]+)");
    private static final Pattern CLASS = Pattern.compile(
            "^\\s*(?:(?:public|internal|private|protected|static|sealed|abstract|partial|file|readonly|ref|unsafe|new)\\s+)*"
                    + "(?:class|struct|interface|enum|record(?:\\s+(?:class|struct))?)\\s+(\\w+)");
    private static final Pattern METHOD =
            Pattern.compile("^\\s*(?:(?:public|internal|private|protected|static|async|virtual|override|new)\\s+)*"
                    + "[\\w<>\\[\\],.?]+\\s+(\\w+)\\s*\\(");

    /** The test host's line naming the process to attach to: {@code Process Id: 2013826, Name: dotnet}. */
    private static final Pattern PROCESS_ID = Pattern.compile("Process Id:\\s*(\\d+)");

    /** The package references and SDKs that make a project a test project. */
    private static final Pattern TEST_PROJECT =
            Pattern.compile("(?i)Microsoft\\.NET\\.Test\\.Sdk|\"xunit|\"nunit|MSTest|<IsTestProject>\\s*true");

    private DotnetTests() {}

    /** Whether the project file's text ({@code .csproj}) makes it a test project. */
    public static boolean isTestProject(@Nullable String csproj) {
        return csproj != null && TEST_PROJECT.matcher(csproj).find();
    }

    /**
     * The test at 0-based {@code line} of C# source {@code text}: the test method the line is in (from its attributes
     * to the end of its body), else the innermost class it is in that has tests. Null outside every test class. A type
     * nested in a test class (a helper record, a fake) counts only inside its braces; a test class nested in another
     * is named as VSTest names it, {@code Outer+Inner}.
     */
    public static @Nullable Target testAt(String text, int line) {
        List<String> lines = text.lines().toList();
        List<Type> types = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        Deque<Type> open = new ArrayDeque<>();
        String namespace = "";
        @Nullable Type pendingType = null; // declared, its "{" not yet seen
        @Nullable Method pendingMethod = null; // declared, its body not yet seen
        @Nullable Method inMethod = null; // the method whose body the scan is in
        boolean pendingTest = false;
        int depth = 0;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            @Nullable Type top = open.peek();
            boolean atTypeBody = inMethod == null && pendingMethod == null && (top == null || depth == top.bodyDepth);
            if (atTypeBody) {
                Matcher ns = NAMESPACE.matcher(l);
                String decl = withoutAttributes(l);
                Matcher cls = CLASS.matcher(decl);
                Matcher m = METHOD.matcher(decl);
                if (TEST_ATTRIBUTE.matcher(l).find()) {
                    pendingTest = true;
                }
                if (ns.find()) {
                    namespace = ns.group(1);
                } else if (cls.find()) {
                    String path = top == null ? cls.group(1) : top.path + "+" + cls.group(1);
                    pendingType = new Type(namespace, path, i);
                    types.add(pendingType);
                    pendingTest = false;
                } else if (top != null && m.find() && !decl.contains(" new ")) {
                    pendingMethod = new Method(top, m.group(1), attributeStart(lines, i), pendingTest);
                    methods.add(pendingMethod);
                    pendingTest = false;
                }
            }
            String code = code(l);
            for (int c = 0; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '{') {
                    depth++;
                    if (pendingType != null) {
                        pendingType.bodyDepth = depth;
                        open.push(pendingType);
                        pendingType = null;
                    } else if (pendingMethod != null) {
                        pendingMethod.bodyDepth = depth;
                        inMethod = pendingMethod;
                        pendingMethod = null;
                    }
                } else if (ch == '}') {
                    if (inMethod != null && depth == inMethod.bodyDepth) {
                        inMethod.end = i;
                        inMethod = null;
                    } else if (inMethod == null) {
                        Type inner = open.peek();
                        if (inner != null && depth == inner.bodyDepth) {
                            open.pop().end = i;
                        }
                    }
                    depth = Math.max(0, depth - 1);
                } else if (ch == ';' && inMethod == null) {
                    if (pendingType != null) {
                        pendingType.end = i; // record Row(string Name);: no body
                        pendingType = null;
                    } else if (pendingMethod != null) {
                        pendingMethod.end = i; // an expression body or none
                        pendingMethod = null;
                    }
                }
            }
        }
        for (Type t : open) {
            t.end = lines.size();
        }
        for (Method m : methods) {
            if (m.end < 0) {
                m.end = lines.size();
            }
            if (m.test) {
                m.type.hasTests = true;
            }
        }
        for (Method m : methods) {
            if (m.test && m.start <= line && line <= m.end && innermost(types, line) == m.type) {
                return new Target(m.type.namespace, m.type.path, m.name);
            }
        }
        // Outside a test: the innermost class around the line that has tests.
        Type best = null;
        for (Type t : types) {
            if (t.hasTests && t.start <= line && line <= t.end && (best == null || t.start >= best.start)) {
                best = t;
            }
        }
        return best == null ? null : new Target(best.namespace, best.path, null);
    }

    /** A type declared in the source: its full nested name ({@code Outer+Inner}) and its lines. */
    private static final class Type {
        final String namespace;
        final String path;
        final int start;
        int end = -1;
        int bodyDepth = -1;
        boolean hasTests;

        Type(String namespace, String path, int start) {
            this.namespace = namespace;
            this.path = path;
            this.start = start;
        }
    }

    /** A method declared directly in a type: its lines, from its attributes to the end of its body. */
    private static final class Method {
        final Type type;
        final String name;
        final int start;
        final boolean test;
        int end = -1;
        int bodyDepth = -1;

        Method(Type type, String name, int start, boolean test) {
            this.type = type;
            this.name = name;
            this.start = start;
            this.test = test;
        }
    }

    /** The innermost type whose lines hold {@code line}, or null. */
    private static @Nullable Type innermost(List<Type> types, int line) {
        Type best = null;
        for (Type t : types) {
            if (t.start <= line && line <= t.end && (best == null || t.start >= best.start)) {
                best = t;
            }
        }
        return best;
    }

    /** {@code line} without the attributes it starts with ({@code [Fact] public void X()} → {@code public void X()}). */
    static String withoutAttributes(String line) {
        String s = line.strip();
        while (s.startsWith("[")) {
            int nesting = 0;
            int close = -1;
            for (int c = 0; c < s.length() && close < 0; c++) {
                if (s.charAt(c) == '[') {
                    nesting++;
                } else if (s.charAt(c) == ']' && --nesting == 0) {
                    close = c;
                }
            }
            if (close < 0) {
                return "";
            }
            s = s.substring(close + 1).strip();
        }
        return s;
    }

    /** {@code line} without its comment and its string and character literals, so only real braces are counted. */
    private static String code(String line) {
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        for (int c = 0; c < line.length(); c++) {
            char ch = line.charAt(c);
            if (quote != 0) {
                if (ch == '\\') {
                    c++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '/' && c + 1 < line.length() && line.charAt(c + 1) == '/') {
                break;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** The first line of the attributes above the method declared on line {@code method}. */
    private static int attributeStart(List<String> lines, int method) {
        int start = method;
        while (start > 0) {
            String above = lines.get(start - 1).strip();
            if (above.startsWith("[") || above.startsWith("//")) {
                start--;
            } else {
                break;
            }
        }
        return start;
    }

    /**
     * {@code dotnet test}'s {@code --filter} for {@code target}: the method by its full name — a parameterized one's
     * cases too, whose names add their arguments ({@code Method(1, 2)}) — else every test of the class.
     */
    public static String filter(Target target) {
        String full = target.fullClassName();
        if (target.method() == null) {
            return "FullyQualifiedName~" + full + ".";
        }
        String method = full + "." + target.method();
        return "FullyQualifiedName=" + method + "|FullyQualifiedName~" + method + "\\(";
    }

    /** The process id a test host waiting for a debugger prints, or -1 when {@code line} is not that line. */
    public static long processId(String line) {
        Matcher m = PROCESS_ID.matcher(line == null ? "" : line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
