package ci;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Reads the {@code TEST-*.xml} files Surefire writes, one per test class. */
final class SurefireReports {

    private SurefireReports() {}

    /** The report files in {@code dir}, sorted by name; none when the directory does not exist. */
    static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("TEST-") && name.endsWith(".xml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every {@code <testcase>} element of a report, at any depth. */
    static List<Element> testCases(Path report) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            NodeList cases = factory.newDocumentBuilder().parse(report.toFile()).getElementsByTagName("testcase");
            var found = new ArrayList<Element>(cases.getLength());
            for (int i = 0; i < cases.getLength(); i++) {
                found.add((Element) cases.item(i));
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(report + ": " + e.getMessage(), e);
        }
    }

    /** The direct child elements of {@code parent} named {@code tag}. */
    static List<Element> children(Element parent, String tag) {
        var found = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(tag)) {
                found.add(element);
            }
        }
        return found;
    }
}
