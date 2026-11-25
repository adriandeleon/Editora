package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The XML reports of C and C++ test programs: GoogleTest's {@code --gtest_output=xml} (its JUnit dialect, each test
 * with its {@code file} and {@code line}), and Catch2's and doctest's own XML reporters ({@code TestCase} elements with
 * their {@code filename} and {@code line}, a failed check as an {@code Expression}). A suite per source file, named by
 * its path from the run's folder; a GoogleTest row is named {@code Suite.Test} (as {@code --gtest_filter} and CTest name
 * it), a Catch2 or doctest row by its test case's name. Pure.
 */
public final class NativeTestReports {

    private NativeTestReports() {}

    /** GoogleTest's XML report {@code xml}, of a run in {@code dir}; empty when it is not one. */
    public static List<ParsedSuite> googleTest(String xml, Path dir) {
        Element root = root(xml);
        if (root == null) {
            return List.of();
        }
        Map<String, List<ParsedTest>> byFile = new LinkedHashMap<>();
        for (Element suite : descendants(root, "testsuite")) {
            String suiteName = suite.getAttribute("name");
            for (Element tc : children(suite, "testcase")) {
                String file = tc.getAttribute("file");
                String group = file.isEmpty() ? suiteName : JestJsonReport.relative(dir, file);
                String name = suiteName + "." + tc.getAttribute("name");
                Element failure = first(tc, "failure");
                Element skipped = first(tc, "skipped");
                TestStatus status;
                String message = null;
                String detail = null;
                if (failure != null) {
                    status = TestStatus.FAILED;
                    detail = text(failure);
                    message = firstMessageLine(detail.isEmpty() ? failure.getAttribute("message") : detail);
                } else if (skipped != null
                        || "skipped".equals(tc.getAttribute("result"))
                        || "notrun".equals(tc.getAttribute("status"))) {
                    status = TestStatus.SKIPPED;
                    if (skipped != null) {
                        detail = text(skipped);
                        message = firstMessageLine(detail.isEmpty() ? skipped.getAttribute("message") : detail);
                    }
                } else {
                    status = TestStatus.PASSED;
                }
                byFile.computeIfAbsent(group, k -> new ArrayList<>())
                        .add(new ParsedTest(
                                group,
                                name,
                                status,
                                millis(tc.getAttribute("time")),
                                null,
                                message,
                                detail,
                                null,
                                file.isEmpty() ? null : Path.of(file).toString(),
                                integer(tc.getAttribute("line"))));
            }
        }
        return suites(byFile);
    }

    /** Catch2's or doctest's XML report {@code xml}, of a run in {@code dir}; empty when it is not one. */
    public static List<ParsedSuite> catchOrDoctest(String xml, Path dir) {
        Element root = root(xml);
        if (root == null) {
            return List.of();
        }
        Map<String, List<ParsedTest>> byFile = new LinkedHashMap<>();
        for (Element tc : descendants(root, "TestCase")) {
            String file = tc.getAttribute("filename");
            String group = file.isEmpty() ? "tests" : JestJsonReport.relative(dir, file);
            String name = tc.getAttribute("name");
            List<String> failures = new ArrayList<>();
            int failLine = 0;
            for (Element e : descendants(tc, "*")) {
                String what = e.getTagName();
                boolean failed = ("Expression".equals(what) && "false".equals(e.getAttribute("success")))
                        || "Exception".equals(what)
                        || "FatalErrorCondition".equals(what)
                        || ("Failure".equals(what));
                if (!failed) {
                    continue;
                }
                failures.add(failure(e, tc));
                if (failLine == 0) {
                    failLine = integer(e.getAttribute("line"));
                }
            }
            Element overall = first(tc, "OverallResult"); // Catch2
            Element asserts = first(tc, "OverallResultsAsserts"); // doctest
            boolean passed = overall != null
                    ? "true".equals(overall.getAttribute("success"))
                    : asserts == null || "true".equals(asserts.getAttribute("test_case_success"));
            boolean skipped = overall != null && integer(overall.getAttribute("skips")) > 0
                    || "true".equals(tc.getAttribute("skipped")); // doctest: skip(), or left out by a filter
            TestStatus status = !passed || !failures.isEmpty()
                    ? TestStatus.FAILED
                    : skipped ? TestStatus.SKIPPED : TestStatus.PASSED;
            String detail = failures.isEmpty() ? null : String.join("\n\n", failures);
            String message = detail == null ? null : firstMessageLine(detail);
            if (status == TestStatus.SKIPPED) {
                Element skip = first(tc, "Skip");
                message = skip == null ? null : text(skip).strip();
            }
            double seconds = overall != null
                            && !overall.getAttribute("durationInSeconds").isEmpty()
                    ? number(overall.getAttribute("durationInSeconds"))
                    : 0;
            byFile.computeIfAbsent(group, k -> new ArrayList<>())
                    .add(new ParsedTest(
                            group,
                            name,
                            status,
                            Math.round(seconds * 1000),
                            null,
                            message,
                            detail,
                            null,
                            file.isEmpty() ? null : Path.of(file).toString(),
                            failLine > 0 && status == TestStatus.FAILED ? failLine : integer(tc.getAttribute("line"))));
        }
        return suites(byFile);
    }

    /** A failed check as the console says it: {@code REQUIRE( a == b )}, its values, its section, where. */
    private static String failure(Element e, Element testCase) {
        StringBuilder sb = new StringBuilder();
        List<String> sections = new ArrayList<>();
        for (Node n = e.getParentNode(); n != null && n != testCase; n = n.getParentNode()) {
            if (n instanceof Element p && ("Section".equals(p.getTagName()) || "SubCase".equals(p.getTagName()))) {
                sections.addFirst(p.getAttribute("name"));
            }
        }
        if ("Expression".equals(e.getTagName())) {
            Element original = first(e, "Original");
            Element expanded = first(e, "Expanded");
            Element exception = first(e, "Exception");
            String o = original == null ? "" : text(original).strip();
            String x = expanded == null ? "" : text(expanded).strip();
            sb.append(e.getAttribute("type")).append("( ").append(o).append(" )");
            if (!x.isEmpty() && !x.equals(o)) {
                sb.append("\nwith expansion:\n  ").append(x);
            }
            if (exception != null) {
                sb.append("\nthrew: ").append(text(exception).strip());
            }
        } else {
            String t = text(e).strip();
            sb.append(e.getTagName().equals("Exception") ? "threw: " : "").append(t);
        }
        if (!sections.isEmpty()) {
            sb.append("\nin section: ").append(String.join(" / ", sections));
        }
        String file = e.getAttribute("filename");
        String line = e.getAttribute("line");
        if (!file.isEmpty()) {
            sb.append("\nat ").append(file).append(line.isEmpty() ? "" : ":" + line);
        }
        return sb.toString();
    }

    private static String firstMessageLine(String text) {
        for (String l : text.strip().split("\\R")) {
            String s = l.strip();
            if (!s.isEmpty() && !s.matches(".*\\.(c|cc|cpp|cxx|h|hpp):\\d+:?")) {
                return s; // GoogleTest starts with file:line, said beside the row already
            }
        }
        return text.strip();
    }

    private static List<ParsedSuite> suites(Map<String, List<ParsedTest>> byFile) {
        List<ParsedSuite> out = new ArrayList<>();
        byFile.forEach((file, tests) -> out.add(new ParsedSuite(file, tests)));
        return out;
    }

    private static @Nullable Element root(String xml) {
        if (xml.isBlank()) {
            return null;
        }
        try {
            return JUnitXmlParser.document(xml);
        } catch (Exception notXml) {
            return null;
        }
    }

    private static List<Element> descendants(Element root, String tag) {
        List<Element> out = new ArrayList<>();
        var list = root.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getTagName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static @Nullable Element first(Element parent, String tag) {
        List<Element> c = children(parent, tag);
        return c.isEmpty() ? null : c.getFirst();
    }

    private static String text(Element e) {
        String t = e.getTextContent();
        return t == null ? "" : t;
    }

    private static int integer(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long millis(String seconds) {
        return Math.round(number(seconds) * 1000);
    }
}
