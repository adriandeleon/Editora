package com.editora.dap;

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

    /** A test at the caret: its namespace ("" for none), class, and method (null for the whole class). */
    public record Target(
            String namespace, String className, @Nullable String method) {
        /** The class's full name ({@code Shop.Tests.PriceTests}). */
        public String fullClassName() {
            return namespace.isEmpty() ? className : namespace + "." + className;
        }

        /** What the Debug window names the session: the method, else the class. */
        public String displayName() {
            return method == null ? className : className + "." + method;
        }
    }

    /** The attributes that make a method a test: xUnit's, NUnit's, MSTest's. */
    private static final Pattern TEST_ATTRIBUTE = Pattern.compile(
            "\\[\\s*(?:[\\w.]+\\.)?(Fact|Theory|Test|TestCase|TestCaseSource|TestMethod|DataTestMethod)\\b");

    private static final Pattern NAMESPACE = Pattern.compile("^\\s*namespace\\s+([\\w.]+)");
    private static final Pattern CLASS = Pattern.compile(
            "^\\s*(?:(?:public|internal|private|protected|static|sealed|abstract|partial|file)\\s+)*(?:class|record)\\s+(\\w+)");
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
     * to the next method), else the class it is in when that class has tests. Null outside every test class.
     */
    public static @Nullable Target testAt(String text, int line) {
        List<String> lines = text.lines().toList();
        String namespace = "";
        String currentClass = null;
        boolean classHasTests = false;
        boolean pendingTest = false;
        Target found = null;
        Target classTarget = null;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            Matcher ns = NAMESPACE.matcher(l);
            if (ns.find()) {
                namespace = ns.group(1);
                continue;
            }
            Matcher cls = CLASS.matcher(l);
            if (cls.find()) {
                if (i > line) {
                    break; // a class after the caret: the caret's class (if any) is decided
                }
                currentClass = cls.group(1);
                classHasTests = false;
                pendingTest = false;
                found = null;
                continue;
            }
            if (TEST_ATTRIBUTE.matcher(l).find()) {
                pendingTest = true;
            }
            Matcher m = METHOD.matcher(l);
            if (currentClass != null
                    && m.find()
                    && !l.contains(" new ")
                    && !l.strip().startsWith("[")) {
                if (pendingTest) {
                    classHasTests = true;
                    if (attributeStart(lines, i) <= line) {
                        found = new Target(namespace, currentClass, m.group(1));
                    }
                } else if (i <= line) {
                    found = null; // the caret is in a method that is not a test
                }
                pendingTest = false;
            }
            if (currentClass != null) {
                classTarget = new Target(namespace, currentClass, null);
            }
        }
        if (currentClass == null) {
            return null;
        }
        if (found != null) {
            return found;
        }
        // Outside a test: the class, when it has any (one after the caret counts).
        if (!classHasTests) {
            for (int i = Math.max(0, line); i < lines.size(); i++) {
                if (CLASS.matcher(lines.get(i)).find() && i > line) {
                    break;
                }
                if (TEST_ATTRIBUTE.matcher(lines.get(i)).find()) {
                    classHasTests = true;
                    break;
                }
            }
        }
        return classHasTests ? classTarget : null;
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
