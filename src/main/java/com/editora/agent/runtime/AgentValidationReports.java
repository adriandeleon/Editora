package com.editora.agent.runtime;

import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded fresh JUnit/Surefire reports. XML entities, DTDs and external resources are forbidden. */
public final class AgentValidationReports {
    private AgentValidationReports() {}

    public record TestCase(String className, String name, boolean failed, boolean skipped, String report) {
        public TestCase(String className, String name, boolean failed, boolean skipped) {
            this(className, name, failed, skipped, "");
        }
    }

    public record Report(ObjectNode summary, List<TestCase> cases) {
        public Report {
            cases = List.copyOf(cases);
        }
    }

    private record Scan(Map<Path, String> stamps, boolean truncated) {}

    public static Map<Path, String> stamps(Path root) throws Exception {
        return scan(root).stamps();
    }

    private static Scan scan(Path root) throws Exception {
        var stamps = new LinkedHashMap<Path, String>();
        boolean truncated = false;
        try (var paths = Files.walk(root, 20)) {
            var it = paths.iterator();
            int entries = 0;
            while (it.hasNext()) {
                if (++entries > 100000 || stamps.size() >= 1000) {
                    truncated = true;
                    break;
                }
                Path p = it.next();
                String name = p.toString().replace('\\', '/');
                if (p.getFileName().toString().startsWith("TEST-")
                        && name.endsWith(".xml")
                        && (name.contains("/surefire-reports/")
                                || name.contains("/failsafe-reports/")
                                || name.contains("/test-results/"))
                        && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    stamps.put(p, Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS) + ":" + Files.size(p));
                if (root.relativize(p).getNameCount() == 20 && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    truncated = true;
            }
        }
        return new Scan(stamps, truncated);
    }

    public static ObjectNode read(AgentWorkspace workspace, Path root, Map<Path, String> before) throws Exception {
        return readDetailed(workspace, root, before).summary();
    }

    public static Report readDetailed(AgentWorkspace workspace, Path root, Map<Path, String> before) throws Exception {
        var identities = new ArrayList<TestCase>();
        var json = new ObjectMapper();
        var out = json.createObjectNode();
        var failures = out.putArray("failures");
        int tests = 0, failed = 0, skipped = 0, unreadable = 0, reports = 0, totalBytes = 0;
        var scan = scan(root);
        for (var entry : scan.stamps().entrySet()) {
            if (entry.getValue().equals(before.get(entry.getKey()))) continue;
            try {
                Path p = workspace.resolve(entry.getKey().toString());
                long size = Files.size(p);
                if (size > 1_000_000 || totalBytes + size > 25_000_000) {
                    unreadable++;
                    continue;
                }
                totalBytes += (int) size;
                var f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                var builder = f.newDocumentBuilder();
                builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
                byte[] bytes;
                try (var input = Files.newInputStream(p)) {
                    bytes = input.readNBytes(1_000_001);
                }
                if (bytes.length > 1_000_000) {
                    unreadable++;
                    continue;
                }
                var doc = builder.parse(new java.io.ByteArrayInputStream(bytes));
                reports++;
                var cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    var t = (org.w3c.dom.Element) cases.item(i);
                    tests++;
                    if (t.getElementsByTagName("skipped").getLength() > 0) skipped++;
                    var errors = t.getElementsByTagName("failure");
                    if (errors.getLength() == 0) errors = t.getElementsByTagName("error");
                    if (identities.size() < 512)
                        identities.add(new TestCase(
                                AgentContext.bounded(t.getAttribute("classname"), 300),
                                AgentContext.bounded(t.getAttribute("name"), 300),
                                errors.getLength() > 0,
                                t.getElementsByTagName("skipped").getLength() > 0,
                                AgentContext.bounded(root.relativize(p).toString(), 300)));
                    if (errors.getLength() > 0) {
                        failed++;
                        if (failures.size() < 4) {
                            var error = (org.w3c.dom.Element) errors.item(0);
                            failures.addObject()
                                    .put("class", AgentContext.bounded(t.getAttribute("classname"), 200))
                                    .put("test", AgentContext.bounded(t.getAttribute("name"), 200))
                                    .put("type", AgentContext.bounded(error.getAttribute("type"), 150))
                                    .put("message", AgentContext.bounded(error.getAttribute("message"), 250));
                        }
                    }
                }
            } catch (Exception badReport) {
                unreadable++;
            }
        }
        out.put("caseIdentitiesTruncated", tests > identities.size());
        return new Report(
                out.put("scanTruncated", scan.truncated())
                        .put("freshReports", reports)
                        .put("tests", tests)
                        .put("failed", failed)
                        .put("skipped", skipped)
                        .put("unreadableReports", unreadable),
                identities);
    }
}
