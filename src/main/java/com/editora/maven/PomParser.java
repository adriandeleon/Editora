package com.editora.maven;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses a pom.xml into a {@link PomModel} — no Maven model resolution, no parent inheritance beyond a
 * direct {@code <parent>} fallback for {@code groupId}/{@code version}, no default-lifecycle-binding
 * lookup. Uses the JDK's own {@code javax.xml.parsers} DOM parser (no third-party XML dependency), hardened
 * against XXE: DOCTYPE declarations are disallowed outright, so a pom containing one fails to parse rather
 * than silently expanding an external entity.
 */
public final class PomParser {

    private PomParser() {}

    /** Reads and parses {@code pomXml} (UTF-8). */
    public static PomModel parseFile(Path pomXml) throws IOException, PomParseException {
        return parse(Files.readString(pomXml, StandardCharsets.UTF_8));
    }

    public static PomModel parse(String xml) throws PomParseException {
        Element project = parseDocument(xml).getDocumentElement();
        if (project == null || !"project".equals(project.getTagName())) {
            throw new PomParseException("Not a Maven pom.xml (missing <project> root element)");
        }
        Element parent = firstChildElement(project, "parent");

        String groupId = firstChildText(project, "groupId");
        if (isBlank(groupId)) {
            groupId = firstChildText(parent, "groupId");
        }
        String artifactId = firstChildText(project, "artifactId");
        if (isBlank(artifactId)) {
            throw new PomParseException("Malformed pom.xml: missing <artifactId>");
        }
        String version = firstChildText(project, "version");
        if (isBlank(version)) {
            version = firstChildText(parent, "version");
        }
        String packaging = firstChildText(project, "packaging");
        if (isBlank(packaging)) {
            packaging = "jar";
        }

        List<PomModel.Plugin> plugins = parsePlugins(firstChildElement(project, "build"));

        List<PomModel.Profile> profiles = new ArrayList<>();
        Element profilesEl = firstChildElement(project, "profiles");
        if (profilesEl != null) {
            for (Element profileEl : childElements(profilesEl, "profile")) {
                String id = firstChildText(profileEl, "id");
                if (isBlank(id)) {
                    continue;
                }
                boolean activeByDefault = false;
                Element activation = firstChildElement(profileEl, "activation");
                if (activation != null) {
                    activeByDefault = "true".equalsIgnoreCase(strip(firstChildText(activation, "activeByDefault")));
                }
                profiles.add(new PomModel.Profile(
                        id.strip(), activeByDefault, parsePlugins(firstChildElement(profileEl, "build"))));
            }
        }

        return new PomModel(
                strip(groupId), artifactId.strip(), strip(version), packaging.strip(), plugins, List.copyOf(profiles));
    }

    private static List<PomModel.Plugin> parsePlugins(Element build) {
        List<PomModel.Plugin> out = new ArrayList<>();
        Element pluginsEl = firstChildElement(build, "plugins");
        if (pluginsEl == null) {
            return List.of();
        }
        for (Element pluginEl : childElements(pluginsEl, "plugin")) {
            String artifactId = firstChildText(pluginEl, "artifactId");
            if (isBlank(artifactId)) {
                continue; // malformed <plugin> entry with no artifactId — skip rather than fail the parse
            }
            String groupId = firstChildText(pluginEl, "groupId");
            String version = firstChildText(pluginEl, "version");
            out.add(new PomModel.Plugin(
                    isBlank(groupId) ? "org.apache.maven.plugins" : groupId.strip(),
                    artifactId.strip(),
                    strip(version),
                    parseExecutions(pluginEl)));
        }
        return List.copyOf(out);
    }

    private static List<PomModel.Execution> parseExecutions(Element pluginEl) {
        List<PomModel.Execution> out = new ArrayList<>();
        Element executionsEl = firstChildElement(pluginEl, "executions");
        if (executionsEl == null) {
            return List.of();
        }
        for (Element execEl : childElements(executionsEl, "execution")) {
            String id = firstChildText(execEl, "id");
            String phase = firstChildText(execEl, "phase");
            List<String> goals = new ArrayList<>();
            Element goalsEl = firstChildElement(execEl, "goals");
            if (goalsEl != null) {
                for (Element goalEl : childElements(goalsEl, "goal")) {
                    String goal = strip(text(goalEl));
                    if (!goal.isEmpty()) {
                        goals.add(goal);
                    }
                }
            }
            out.add(new PomModel.Execution(isBlank(id) ? "default" : id.strip(), strip(phase), List.copyOf(goals)));
        }
        return List.copyOf(out);
    }

    // --- DOM helpers -----------------------------------------------------------------------------

    private static Document parseDocument(String xml) throws PomParseException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE hardening: never resolve a DOCTYPE or an external/parameter entity.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PomParseException("Malformed pom.xml: " + e.getMessage(), e);
        }
    }

    private static Element firstChildElement(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String firstChildText(Element parent, String tagName) {
        Element el = firstChildElement(parent, tagName);
        return el == null ? null : text(el);
    }

    private static String text(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
