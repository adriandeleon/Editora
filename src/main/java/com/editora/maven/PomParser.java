package com.editora.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses one pom.xml into a {@link PomModel} — no Maven model resolution, no parent inheritance beyond a
 * direct {@code <parent>} fallback for {@code groupId}/{@code version} (see {@link PomInheritance} for the
 * on-disk parent chain), no default-lifecycle-binding lookup. Uses the JDK's own {@code javax.xml.parsers} DOM parser (no third-party XML dependency), hardened
 * against XXE: DOCTYPE declarations are disallowed outright, so a pom containing one fails to parse rather
 * than silently expanding an external entity.
 */
public final class PomParser {

    /** What a pom carrying a {@code <!DOCTYPE>} is reported as (the XML parser's own message names a feature flag). */
    public static final String DOCTYPE_MESSAGE =
            "pom.xml declares a DOCTYPE, which Editora does not read (Maven accepts it; remove it to see this pom here)";

    private PomParser() {}

    /**
     * Reads and parses {@code pomXml}, letting the XML parser decide the encoding.
     *
     * <p>The bytes go to the parser as bytes. Decoding them as UTF-8 first — as this did — took the decision
     * away from the one component that knows the answer: a pom saved with a UTF-8 byte-order mark then began
     * with a stray U+FEFF ("content is not allowed in prolog"), and one declaring
     * {@code encoding="ISO-8859-1"} with an accented developer name was not valid UTF-8 at all. Both are
     * poms Maven builds without complaint, and both were reported as malformed.
     */
    public static PomModel parseFile(Path pomXml) throws IOException, PomParseException {
        try (InputStream in = Files.newInputStream(pomXml)) {
            return model(parseDocument(new InputSource(in)));
        }
    }

    public static PomModel parse(String xml) throws PomParseException {
        return model(parseDocument(xml));
    }

    private static PomModel model(Document document) throws PomParseException {
        Element project = document.getDocumentElement();
        if (project == null || !"project".equals(project.getTagName())) {
            throw new PomParseException("Not a Maven pom.xml (missing <project> root element)");
        }
        Element parent = firstChildElement(project, "parent");

        String groupId = firstChildText(project, "groupId");
        if (isBlank(groupId)) {
            groupId = firstChildText(parent, "groupId");
        }
        String artifactId = firstChildText(project, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            throw new PomParseException("Malformed pom.xml: missing <artifactId>");
        }
        String version = firstChildText(project, "version");
        if (isBlank(version)) {
            version = firstChildText(parent, "version");
        }
        String packaging = firstChildText(project, "packaging");
        if (packaging == null || packaging.isBlank()) {
            packaging = "jar";
        }

        Element build = firstChildElement(project, "build");
        List<PomModel.Plugin> plugins = parsePlugins(build);
        List<PomModel.Plugin> managed = parsePlugins(firstChildElement(build, "pluginManagement"));

        List<PomModel.Profile> profiles = new ArrayList<>();
        Element profilesEl = firstChildElement(project, "profiles");
        if (profilesEl != null) {
            for (Element profileEl : childElements(profilesEl, "profile")) {
                String id = firstChildText(profileEl, "id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                boolean activeByDefault = false;
                List<PomModel.Activation> activations = List.of();
                Element activation = firstChildElement(profileEl, "activation");
                if (activation != null) {
                    activeByDefault = "true".equalsIgnoreCase(strip(firstChildText(activation, "activeByDefault")));
                    activations = parseActivations(activation);
                }
                profiles.add(new PomModel.Profile(
                        id.strip(), activeByDefault, parsePlugins(firstChildElement(profileEl, "build")), activations));
            }
        }

        PomModel.Parent parentRef = parent == null
                ? null
                : new PomModel.Parent(
                        strip(firstChildText(parent, "groupId")),
                        strip(firstChildText(parent, "artifactId")),
                        strip(firstChildText(parent, "version")),
                        strip(firstChildText(parent, "relativePath")));

        return new PomModel(
                strip(groupId),
                artifactId.strip(),
                strip(version),
                packaging.strip(),
                plugins,
                List.copyOf(profiles),
                parentRef,
                parseProperties(firstChildElement(project, "properties")),
                managed,
                List.of());
    }

    /** {@code <properties>} in document order; a repeated name keeps its last value, as Maven does. */
    private static Map<String, String> parseProperties(@Nullable Element propertiesEl) {
        if (propertiesEl == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Node n = propertiesEl.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.put(n.getNodeName(), strip(text((Element) n)));
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * The {@code <activation>} conditions besides {@code activeByDefault}, as written: {@code <jdk>} and
     * {@code <os>} ranges, a {@code <property>} ({@code name}, or {@code name=value}; a leading {@code !}
     * means "absent", as in Maven), and a {@code <file>} that must exist or be missing. Rendered as the
     * profile row's note so the tree can say <em>why</em> a profile would be active instead of showing a
     * bare unchecked box for one that Maven activates on its own.
     */
    static List<PomModel.Activation> parseActivations(Element activation) {
        List<PomModel.Activation> out = new ArrayList<>();
        String jdk = strip(firstChildText(activation, "jdk"));
        if (!jdk.isEmpty()) {
            out.add(new PomModel.Activation(PomModel.Activation.Kind.JDK, jdk));
        }
        Element os = firstChildElement(activation, "os");
        if (os != null) {
            List<String> parts = new ArrayList<>();
            for (String tag : List.of("name", "family", "arch", "version")) {
                String value = strip(firstChildText(os, tag));
                if (!value.isEmpty()) {
                    parts.add(tag + "=" + value);
                }
            }
            if (!parts.isEmpty()) {
                out.add(new PomModel.Activation(PomModel.Activation.Kind.OS, String.join(" ", parts)));
            }
        }
        Element property = firstChildElement(activation, "property");
        if (property != null) {
            String name = strip(firstChildText(property, "name"));
            String value = strip(firstChildText(property, "value"));
            if (!name.isEmpty()) {
                out.add(new PomModel.Activation(
                        PomModel.Activation.Kind.PROPERTY, value.isEmpty() ? name : name + "=" + value));
            }
        }
        Element file = firstChildElement(activation, "file");
        if (file != null) {
            String exists = strip(firstChildText(file, "exists"));
            String missing = strip(firstChildText(file, "missing"));
            if (!exists.isEmpty()) {
                out.add(new PomModel.Activation(PomModel.Activation.Kind.FILE_EXISTS, exists));
            }
            if (!missing.isEmpty()) {
                out.add(new PomModel.Activation(PomModel.Activation.Kind.FILE_MISSING, missing));
            }
        }
        return List.copyOf(out);
    }

    private static List<PomModel.Plugin> parsePlugins(@Nullable Element build) {
        List<PomModel.Plugin> out = new ArrayList<>();
        Element pluginsEl = firstChildElement(build, "plugins");
        if (pluginsEl == null) {
            return List.of();
        }
        for (Element pluginEl : childElements(pluginsEl, "plugin")) {
            String artifactId = firstChildText(pluginEl, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                continue; // malformed <plugin> entry with no artifactId — skip rather than fail the parse
            }
            String groupId = firstChildText(pluginEl, "groupId");
            String version = firstChildText(pluginEl, "version");
            out.add(new PomModel.Plugin(
                    groupId == null || groupId.isBlank() ? "org.apache.maven.plugins" : groupId.strip(),
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
            out.add(new PomModel.Execution(
                    id == null || id.isBlank() ? "default" : id.strip(), strip(phase), List.copyOf(goals)));
        }
        return List.copyOf(out);
    }

    // --- DOM helpers -----------------------------------------------------------------------------
    // Package-private (not private) so {@link PomSummary} — the other pom.xml reader in this package, which
    // needs a different, display-oriented slice of the same document — shares one XXE-hardened parse and one
    // set of child-element accessors rather than carrying a second copy of them.

    static Document parseDocument(String xml) throws PomParseException {
        // Already-decoded text has no encoding left to detect, but it can still carry the byte-order mark
        // its decoder passed through (an editor buffer does not) — which is not XML.
        String text = xml != null && !xml.isEmpty() && xml.charAt(0) == '\uFEFF' ? xml.substring(1) : xml;
        return parseDocument(new InputSource(new StringReader(text)));
    }

    /** The one XXE-hardened parse; the source carries either characters or bytes plus their own encoding. */
    private static Document parseDocument(InputSource source) throws PomParseException {
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
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // no "[Fatal Error]" on stderr (CM4)
            return builder.parse(source);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            String message = e.getMessage();
            // The parser's own wording for the DOCTYPE ban names a feature flag; say what it means instead.
            // (Maven accepts a DOCTYPE; Editora does not read one, because it could carry an external entity.)
            if (message != null && message.contains("DOCTYPE")) {
                throw new PomParseException(DOCTYPE_MESSAGE, e);
            }
            throw new PomParseException("Malformed pom.xml: " + message, e);
        }
    }

    static @Nullable Element firstChildElement(@Nullable Element parent, String tagName) {
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

    static List<Element> childElements(@Nullable Element parent, String tagName) {
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

    static @Nullable String firstChildText(@Nullable Element parent, String tagName) {
        Element el = firstChildElement(parent, tagName);
        return el == null ? null : text(el);
    }

    static String text(Element el) {
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

    static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    static String strip(@Nullable String s) {
        return s == null ? "" : s.strip();
    }
}
