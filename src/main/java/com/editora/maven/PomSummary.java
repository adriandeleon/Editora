package com.editora.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

/**
 * A pom.xml read for <em>display</em>: coordinates, parent, modules, properties, dependencies (declared and
 * managed), plugins (declared and managed), and profiles — the stripped-down, section-per-concern view the
 * pom preview renders instead of the generic XML DOM tree.
 *
 * <p>Deliberately a second reader beside {@link PomParser}/{@link PomModel} rather than an extension of them:
 * {@code PomModel} exists to answer "what can this project <em>run</em>" (it models only {@code
 * <build>/<plugins>} and their executions, because that is what the build-tool actions tree needs), while this
 * one answers "what does this project <em>declare</em>". Widening the record would have changed its canonical
 * constructor for every existing caller to serve a use case that shares none of its fields.
 *
 * <p>Like {@code structured/XmlParser} — and unlike {@code PomParser}, whose callers want a hard failure —
 * parsing here is <b>tolerant</b>: a malformed document comes back as an otherwise-empty summary carrying an
 * {@link #error()} message, so the preview can show the reason instead of a blank pane. Pure (no toolkit),
 * called off the FX thread, and XXE-hardened via {@link PomParser#parseDocument} (DOCTYPE disallowed).
 *
 * <p>This is <b>not</b> an effective pom: there is no parent resolution beyond the {@code <parent>} element
 * written in the file itself, so a version inherited from a parent's {@code dependencyManagement} shows as
 * blank ("inherited") rather than as the number Maven would compute. What it <em>does</em> resolve is what
 * can be answered from this one file: a {@code ${property}} version (from {@code <properties>} plus the
 * {@code project.*} built-ins) and a blank version filled in from this file's own {@code
 * <dependencyManagement>}/{@code <pluginManagement>} — the two indirections that otherwise leave the reader
 * scrolling the file to answer "which version is this?".
 */
public record PomSummary(
        Coordinates parent,
        Coordinates coordinates,
        String packaging,
        String name,
        String description,
        List<String> modules,
        List<Property> properties,
        List<Dependency> dependencies,
        List<Dependency> managedDependencies,
        List<Plugin> plugins,
        List<Plugin> managedPlugins,
        List<Profile> profiles,
        String error) {

    /** A {@code groupId:artifactId:version} triple; any part may be {@code ""} when not written. */
    public record Coordinates(String groupId, String artifactId, String version) {}

    /** One {@code <properties>} entry, in document order. */
    public record Property(String name, String value) {}

    /**
     * One declared dependency.
     *
     * @param version the version exactly as written ({@code ""} when the element is absent, {@code
     *     "${junit.version}"} when it is a property reference)
     * @param effectiveVersion what that resolves to within this file — the expanded property, or the version
     *     this file's {@code <dependencyManagement>} supplies for a blank one; {@code ""} when unknowable
     *     here (inherited from a parent pom, or an unresolvable property)
     * @param managed whether {@code effectiveVersion} came from {@code <dependencyManagement>}
     */
    public record Dependency(
            String groupId,
            String artifactId,
            String version,
            String effectiveVersion,
            boolean managed,
            String scope,
            String type,
            String classifier,
            boolean optional) {}

    /** One declared plugin; {@code version}/{@code effectiveVersion}/{@code managed} as for {@link Dependency}.
     *  {@code goals} is the distinct goal list across its {@code <executions>}, in document order. */
    public record Plugin(
            String groupId,
            String artifactId,
            String version,
            String effectiveVersion,
            boolean managed,
            List<String> goals) {}

    /** A {@code <profile>} with its own properties/dependencies/plugins (not merged into the top level). */
    public record Profile(
            String id,
            boolean activeByDefault,
            List<Property> properties,
            List<Dependency> dependencies,
            List<Plugin> plugins) {}

    /** Maven's own default for a {@code <plugin>} with no {@code <groupId>}. */
    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    /** Bounded so a self-referential property ({@code a=${b}}, {@code b=${a}}) terminates instead of spinning. */
    private static final int MAX_PROPERTY_HOPS = 10;

    public boolean ok() {
        return error == null;
    }

    /** Whether there is nothing but coordinates to show (an empty or minimal pom). */
    public boolean isEmpty() {
        return modules.isEmpty()
                && properties.isEmpty()
                && dependencies.isEmpty()
                && managedDependencies.isEmpty()
                && plugins.isEmpty()
                && managedPlugins.isEmpty()
                && profiles.isEmpty();
    }

    /** Total declared dependencies, including every profile's — what the header count reports. */
    public int totalDependencies() {
        int n = dependencies.size();
        for (Profile p : profiles) {
            n += p.dependencies().size();
        }
        return n;
    }

    private static PomSummary failed(String message) {
        return new PomSummary(
                null,
                new Coordinates("", "", ""),
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                message);
    }

    public static PomSummary parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return failed("empty document");
        }
        Element project;
        try {
            project = PomParser.parseDocument(xml).getDocumentElement();
        } catch (PomParseException e) {
            return failed(firstLine(e.getMessage()));
        } catch (RuntimeException e) {
            return failed(firstLine(e.toString()));
        }
        if (project == null || !"project".equals(project.getTagName())) {
            return failed("not a Maven pom.xml (missing <project> root element)");
        }

        Element parentEl = PomParser.firstChildElement(project, "parent");
        Coordinates parent = parentEl == null
                ? null
                : new Coordinates(
                        childText(parentEl, "groupId"),
                        childText(parentEl, "artifactId"),
                        childText(parentEl, "version"));

        String groupId = childText(project, "groupId");
        String version = childText(project, "version");
        // Maven's own inheritance rule, and the only one this reader applies: a project that omits groupId or
        // version takes the parent's. Anything deeper (managed versions from the parent) needs the parent file.
        if (groupId.isEmpty() && parent != null) {
            groupId = parent.groupId();
        }
        if (version.isEmpty() && parent != null) {
            version = parent.version();
        }
        Coordinates coordinates = new Coordinates(groupId, childText(project, "artifactId"), version);

        String packaging = childText(project, "packaging");
        List<Property> properties = parseProperties(PomParser.firstChildElement(project, "properties"));

        // The substitution map: <properties> plus the project.* built-ins a version string may reference.
        Map<String, String> props = new LinkedHashMap<>();
        for (Property p : properties) {
            props.put(p.name(), p.value());
        }
        putBuiltIn(props, "project.groupId", coordinates.groupId());
        putBuiltIn(props, "project.artifactId", coordinates.artifactId());
        putBuiltIn(props, "project.version", coordinates.version());
        putBuiltIn(props, "project.packaging", packaging.isEmpty() ? "jar" : packaging);
        if (parent != null) {
            putBuiltIn(props, "project.parent.version", parent.version());
            putBuiltIn(props, "project.parent.groupId", parent.groupId());
        }
        // "pom." is the (deprecated but still common) alias for "project.".
        for (Map.Entry<String, String> e : new LinkedHashMap<>(props).entrySet()) {
            if (e.getKey().startsWith("project.")) {
                putBuiltIn(props, "pom." + e.getKey().substring("project.".length()), e.getValue());
            }
        }

        List<Dependency> managedDependencies = parseDependencies(
                PomParser.firstChildElement(
                        PomParser.firstChildElement(project, "dependencyManagement"), "dependencies"),
                props,
                Map.of());
        Map<String, String> managedVersions = versionIndex(managedDependencies);

        Element build = PomParser.firstChildElement(project, "build");
        List<Plugin> managedPlugins = parsePlugins(
                PomParser.firstChildElement(PomParser.firstChildElement(build, "pluginManagement"), "plugins"),
                props,
                Map.of());
        Map<String, String> managedPluginVersions = pluginVersionIndex(managedPlugins);

        List<Profile> profiles = new ArrayList<>();
        Element profilesEl = PomParser.firstChildElement(project, "profiles");
        if (profilesEl != null) {
            for (Element profileEl : PomParser.childElements(profilesEl, "profile")) {
                Element activation = PomParser.firstChildElement(profileEl, "activation");
                boolean activeByDefault =
                        activation != null && "true".equalsIgnoreCase(childText(activation, "activeByDefault"));
                Element profileBuild = PomParser.firstChildElement(profileEl, "build");
                profiles.add(new Profile(
                        childText(profileEl, "id"),
                        activeByDefault,
                        parseProperties(PomParser.firstChildElement(profileEl, "properties")),
                        parseDependencies(
                                PomParser.firstChildElement(profileEl, "dependencies"), props, managedVersions),
                        parsePlugins(
                                PomParser.firstChildElement(profileBuild, "plugins"), props, managedPluginVersions)));
            }
        }

        return new PomSummary(
                parent,
                coordinates,
                packaging.isEmpty() ? "jar" : packaging,
                childText(project, "name"),
                collapseWhitespace(childText(project, "description")),
                parseModules(PomParser.firstChildElement(project, "modules")),
                properties,
                parseDependencies(PomParser.firstChildElement(project, "dependencies"), props, managedVersions),
                managedDependencies,
                parsePlugins(PomParser.firstChildElement(build, "plugins"), props, managedPluginVersions),
                managedPlugins,
                List.copyOf(profiles),
                null);
    }

    private static void putBuiltIn(Map<String, String> props, String key, String value) {
        // A real <properties> entry of the same name wins — that is what Maven itself resolves to.
        if (!value.isEmpty()) {
            props.putIfAbsent(key, value);
        }
    }

    private static List<String> parseModules(Element modulesEl) {
        List<String> out = new ArrayList<>();
        for (Element m : PomParser.childElements(modulesEl, "module")) {
            String name = PomParser.strip(PomParser.text(m));
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    private static List<Property> parseProperties(Element propertiesEl) {
        List<Property> out = new ArrayList<>();
        if (propertiesEl == null) {
            return List.of();
        }
        // Every child element is a property, whatever it is named — so this is the one place that can't use
        // PomParser.childElements(parent, tagName).
        for (org.w3c.dom.Node n = propertiesEl.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                out.add(new Property(
                        n.getNodeName(), collapseWhitespace(PomParser.strip(PomParser.text((Element) n)))));
            }
        }
        return List.copyOf(out);
    }

    private static List<Dependency> parseDependencies(
            Element dependenciesEl, Map<String, String> props, Map<String, String> managedVersions) {
        List<Dependency> out = new ArrayList<>();
        for (Element el : PomParser.childElements(dependenciesEl, "dependency")) {
            String artifactId = childText(el, "artifactId");
            if (artifactId.isEmpty()) {
                continue; // malformed entry — skip it rather than fail the whole preview
            }
            String groupId = resolve(childText(el, "groupId"), props);
            String written = childText(el, "version");
            String resolved = resolve(written, props);
            boolean managed = false;
            if (resolved.isEmpty() || containsPlaceholder(resolved)) {
                String fromManagement = managedVersions.get(groupId + ":" + artifactId);
                if (fromManagement != null && !fromManagement.isEmpty()) {
                    resolved = fromManagement;
                    managed = true;
                }
            }
            out.add(new Dependency(
                    groupId,
                    artifactId,
                    written,
                    containsPlaceholder(resolved) ? "" : resolved,
                    managed,
                    childText(el, "scope"),
                    childText(el, "type"),
                    childText(el, "classifier"),
                    "true".equalsIgnoreCase(childText(el, "optional"))));
        }
        return List.copyOf(out);
    }

    private static List<Plugin> parsePlugins(
            Element pluginsEl, Map<String, String> props, Map<String, String> managedVersions) {
        List<Plugin> out = new ArrayList<>();
        for (Element el : PomParser.childElements(pluginsEl, "plugin")) {
            String artifactId = childText(el, "artifactId");
            if (artifactId.isEmpty()) {
                continue;
            }
            String groupId = childText(el, "groupId");
            groupId = resolve(groupId.isEmpty() ? DEFAULT_PLUGIN_GROUP : groupId, props);
            String written = childText(el, "version");
            String resolved = resolve(written, props);
            boolean managed = false;
            if (resolved.isEmpty() || containsPlaceholder(resolved)) {
                String fromManagement = managedVersions.get(groupId + ":" + artifactId);
                if (fromManagement != null && !fromManagement.isEmpty()) {
                    resolved = fromManagement;
                    managed = true;
                }
            }
            out.add(new Plugin(
                    groupId, artifactId, written, containsPlaceholder(resolved) ? "" : resolved, managed, goalsOf(el)));
        }
        return List.copyOf(out);
    }

    /** The distinct goals across a plugin's {@code <executions>}, in document order. */
    private static List<String> goalsOf(Element pluginEl) {
        List<String> goals = new ArrayList<>();
        Element executions = PomParser.firstChildElement(pluginEl, "executions");
        for (Element exec : PomParser.childElements(executions, "execution")) {
            Element goalsEl = PomParser.firstChildElement(exec, "goals");
            for (Element goal : PomParser.childElements(goalsEl, "goal")) {
                String g = PomParser.strip(PomParser.text(goal));
                if (!g.isEmpty() && !goals.contains(g)) {
                    goals.add(g);
                }
            }
        }
        return List.copyOf(goals);
    }

    private static Map<String, String> versionIndex(List<Dependency> deps) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Dependency d : deps) {
            if (!d.effectiveVersion().isEmpty()) {
                out.putIfAbsent(d.groupId() + ":" + d.artifactId(), d.effectiveVersion());
            }
        }
        return out;
    }

    private static Map<String, String> pluginVersionIndex(List<Plugin> plugins) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Plugin p : plugins) {
            if (!p.effectiveVersion().isEmpty()) {
                out.putIfAbsent(p.groupId() + ":" + p.artifactId(), p.effectiveVersion());
            }
        }
        return out;
    }

    /**
     * Expands every {@code ${name}} in {@code value} from {@code props}, repeatedly so a property defined in
     * terms of another resolves too. An unknown name is left as written (the preview then shows the reference
     * itself, which is more honest than an empty cell), and the pass count is bounded so a property cycle
     * terminates.
     */
    public static String resolve(String value, Map<String, String> props) {
        String current = PomParser.strip(value);
        for (int hop = 0; hop < MAX_PROPERTY_HOPS && containsPlaceholder(current); hop++) {
            String next = expandOnce(current, props);
            if (next.equals(current)) {
                return current; // nothing left this pass resolves — every remaining ${…} is unknown
            }
            current = next;
        }
        return current;
    }

    private static String expandOnce(String value, Map<String, String> props) {
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int open = value.indexOf("${", i);
            if (open < 0) {
                sb.append(value, i, value.length());
                break;
            }
            int close = value.indexOf('}', open + 2);
            if (close < 0) {
                sb.append(value, i, value.length());
                break;
            }
            String name = value.substring(open + 2, close);
            String replacement = props.get(name);
            sb.append(value, i, open);
            sb.append(replacement != null ? replacement : value.substring(open, close + 1));
            i = close + 1;
        }
        return sb.toString();
    }

    private static boolean containsPlaceholder(String value) {
        int open = value.indexOf("${");
        return open >= 0 && value.indexOf('}', open + 2) > open;
    }

    private static String childText(Element parent, String tagName) {
        return PomParser.strip(PomParser.firstChildText(parent, tagName));
    }

    /** Squeezes the newlines + indentation out of a wrapped element body (a {@code <description>}). */
    private static String collapseWhitespace(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "could not be parsed";
        }
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).strip();
    }
}
