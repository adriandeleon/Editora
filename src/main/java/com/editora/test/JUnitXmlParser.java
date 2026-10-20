package com.editora.test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.editora.build.BuildTool;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses a JUnit XML report ({@code TEST-*.xml}) — the authoritative Surefire/Failsafe (Maven) and
 * {@code build/test-results} (Gradle) format, both the same schema — into a {@link ParsedSuite}. One file =
 * one test class. Returns {@code null} for anything that isn't a complete {@code <testsuite>} (a half-written
 * file mid-run), so the coordinator's poller simply retries on the next pass.
 *
 * <p>XXE-hardened exactly like {@code com.editora.maven.PomParser#parseDocument}: no DOCTYPE, no external or
 * parameter entities, no external DTD, no XInclude.
 */
public final class JUnitXmlParser implements TestResultParser {

    private final BuildTool tool;

    public JUnitXmlParser(BuildTool tool) {
        this.tool = tool;
    }

    @Override
    public ParsedSuite parseReportFile(Path file) {
        try {
            String xml = Files.readString(file);
            return parse(xml);
        } catch (Exception e) {
            return null; // unreadable / still being written / not a report — retry next poll
        }
    }

    /** Parses a JUnit XML {@code <testsuite>} document; {@code null} if the root isn't a testsuite. */
    public ParsedSuite parse(String xml) throws IOException, SAXException, ParserConfigurationException {
        Element suite = document(xml);
        if (suite == null || !"testsuite".equals(suite.getNodeName())) {
            return null;
        }
        String suiteName = attr(suite, "name", "");
        List<ParsedTest> tests = new ArrayList<>();
        for (Element tc : childElements(suite, "testcase")) {
            tests.add(parseCase(tc, suiteName));
        }
        return new ParsedSuite(
                suiteName, tests, text(firstChild(suite, "system-out")), text(firstChild(suite, "system-err")));
    }

    private ParsedTest parseCase(Element tc, String suiteName) {
        String method = attr(tc, "name", "");
        String className = attr(tc, "classname", suiteName);
        long durationMs = seconds(attr(tc, "time", ""));
        TestStatus status = TestStatus.PASSED;
        String failureType = null;
        String failureMessage = null;
        String stackTrace = null;

        Element failure = firstChild(tc, "failure");
        Element error = firstChild(tc, "error");
        Element skipped = firstChild(tc, "skipped");
        if (error != null) {
            status = TestStatus.ERROR;
            failureType = attr(error, "type", null);
            failureMessage = attr(error, "message", null);
            stackTrace = text(error);
        } else if (failure != null) {
            status = TestStatus.FAILED;
            failureType = attr(failure, "type", null);
            failureMessage = attr(failure, "message", null);
            stackTrace = text(failure);
        } else if (skipped != null) {
            status = TestStatus.SKIPPED;
            failureMessage = attr(skipped, "message", null);
        }
        // Surefire's reruns (rerunFailingTestsCount): a <flakyFailure>/<flakyError> per failed try before the
        // pass, a <rerunFailure>/<rerunError> per failed retry after the first failure. The leaf keeps the
        // count and the traces of every try (review D16).
        List<Element> reruns = new ArrayList<>();
        for (String tag : RERUN_TAGS) {
            reruns.addAll(childElements(tc, tag));
        }
        if (!reruns.isEmpty()) {
            StringBuilder tries = new StringBuilder();
            int n = 0;
            for (Element rerun : reruns) {
                tries.append(tries.isEmpty() ? "" : "\n\n")
                        .append("--- ")
                        .append(rerun.getNodeName())
                        .append(' ')
                        .append(++n);
                String type = attr(rerun, "type", null);
                String message = attr(rerun, "message", null);
                if (type != null || message != null) {
                    tries.append(": ")
                            .append(type != null ? type : "")
                            .append(type != null && message != null ? ": " : "")
                            .append(message != null ? message : "");
                }
                String trace = text(rerun);
                if (trace != null) {
                    tries.append('\n').append(trace);
                }
            }
            stackTrace = stackTrace == null ? tries.toString() : stackTrace + "\n\n" + tries;
        }
        String fileHint = TestSourceLocator.fileHint(className, tool);
        // Per-testcase captured output when the runner writes it (Surefire's is usually suite-level, but a
        // <testcase><system-out> does occur) — without this a passing test's detail pane had nothing to show.
        String out = text(firstChild(tc, "system-out"));
        String err = text(firstChild(tc, "system-err"));
        String captured = out == null ? err : (err == null ? out : out + "\n" + err);
        return new ParsedTest(
                className,
                method,
                status,
                durationMs,
                failureType,
                failureMessage,
                stackTrace,
                captured,
                fileHint,
                0,
                reruns.size());
    }

    /** Surefire's rerun elements, in the order they are read. */
    static final List<String> RERUN_TAGS = List.of("flakyFailure", "flakyError", "rerunFailure", "rerunError");

    private static long seconds(String time) {
        if (time == null || time.isBlank()) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(time.replace(",", "")) * 1000.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- DOM helpers (XXE-hardened; mirrors maven/PomParser) ------------------------------------------

    private static Element document(String xml) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent != null) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                    out.add((Element) n);
                }
            }
        }
        return out;
    }

    private static String attr(Element el, String name, String fallback) {
        if (el == null || !el.hasAttribute(name)) {
            return fallback;
        }
        return el.getAttribute(name);
    }

    private static String text(Element el) {
        if (el == null) {
            return null;
        }
        String content = el.getTextContent();
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
