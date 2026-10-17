package com.editora.test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Reads the JUnit XML report {@code pytest --junitxml=FILE -o junit_family=xunit1} writes into the Test Results
 * tree. Pytest wraps its one {@code <testsuite>} in {@code <testsuites>} (which {@link JUnitXmlParser} rejects),
 * and names a test by its module path ({@code classname="tests.test_app"} or {@code tests.test_app.TestX}). The
 * xunit1 family adds the file (relative to pytest's root) and the 0-based line, which give the jump-to-source
 * location and the node id to rerun ({@link #nodeId}). One suite per module or test class, in report order.
 *
 * <p>XXE-hardened like {@link JUnitXmlParser}. Pure.
 */
public final class PytestReportParser {

    private PytestReportParser() {}

    /** The suites in {@code xml}; empty when it is not a (complete) report. */
    public static List<ParsedSuite> parse(String xml) {
        Element root;
        try {
            root = document(xml);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null) {
            return List.of();
        }
        List<Element> suites = "testsuite".equals(root.getNodeName()) ? List.of(root) : children(root, "testsuite");
        Map<String, List<ParsedTest>> byClass = new LinkedHashMap<>();
        for (Element suite : suites) {
            for (Element tc : children(suite, "testcase")) {
                ParsedTest t = parseCase(tc);
                byClass.computeIfAbsent(t.className(), k -> new ArrayList<>()).add(t);
            }
        }
        List<ParsedSuite> out = new ArrayList<>();
        byClass.forEach((name, tests) -> out.add(new ParsedSuite(name, List.copyOf(tests))));
        return out;
    }

    private static ParsedTest parseCase(Element tc) {
        String name = attr(tc, "name", "");
        String className = attr(tc, "classname", "");
        if (className.isEmpty()) {
            className = attr(tc, "file", "tests"); // a collection error has no class
        }
        long durationMs;
        try {
            durationMs = Math.round(Double.parseDouble(attr(tc, "time", "0")) * 1000.0);
        } catch (NumberFormatException e) {
            durationMs = 0;
        }
        TestStatus status = TestStatus.PASSED;
        String type = null;
        String message = null;
        String trace = null;
        Element error = first(tc, "error");
        Element failure = first(tc, "failure");
        Element skipped = first(tc, "skipped");
        if (failure != null) {
            status = TestStatus.FAILED;
            type = attr(failure, "type", null);
            message = attr(failure, "message", null);
            trace = text(failure);
        } else if (error != null) {
            status = TestStatus.ERROR;
            type = attr(error, "type", null);
            message = attr(error, "message", null);
            trace = text(error);
        } else if (skipped != null) {
            status = TestStatus.SKIPPED;
            message = attr(skipped, "message", null);
        }
        String out = text(first(tc, "system-out"));
        String err = text(first(tc, "system-err"));
        String captured = out == null ? err : err == null ? out : out + "\n" + err;
        String file = attr(tc, "file", null);
        int line = 0;
        try {
            line = Integer.parseInt(attr(tc, "line", "-1")) + 1; // xunit1 lines are 0-based
        } catch (NumberFormatException ignored) {
            // no line
        }
        return new ParsedTest(className, name, status, durationMs, type, message, trace, captured, file, line);
    }

    /**
     * The pytest node id of a result: {@code file::Class::name}, or {@code file::name} for a module-level test
     * ({@code name} keeps a parametrize id, {@code test_x[1-2]}); {@code file} alone for a module's suite row and
     * {@code file::Class} for a class's. Null without a file.
     *
     * @param file the test's file relative to pytest's root
     * @param className the report's {@code classname}: the dotted module, plus the class when there is one
     * @param name the test's name, or null for a suite row
     */
    public static String nodeId(String file, String className, String name) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String path = file.replace('\\', '/');
        String module =
                path.endsWith(".py") ? path.substring(0, path.length() - 3).replace('/', '.') : path;
        StringBuilder id = new StringBuilder(path);
        if (className != null && className.startsWith(module + ".")) {
            for (String cls : className.substring(module.length() + 1).split("\\.")) {
                id.append("::").append(cls);
            }
        }
        if (name != null && !name.isEmpty()) {
            id.append("::").append(name);
        }
        return id.toString();
    }

    private static Element document(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element first(Element parent, String tag) {
        List<Element> all = children(parent, tag);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String attr(Element el, String name, String fallback) {
        return el != null && el.hasAttribute(name) ? el.getAttribute(name) : fallback;
    }

    private static String text(Element el) {
        if (el == null || el.getTextContent() == null) {
            return null;
        }
        String t = el.getTextContent().strip();
        return t.isEmpty() ? null : t;
    }
}
