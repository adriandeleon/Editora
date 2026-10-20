package com.editora.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * A pom.xml read for <em>display</em>: coordinates, parent, modules, properties, dependencies (declared and
 * managed), plugins (declared, managed and reporting), repositories, and profiles — the stripped-down,
 * section-per-concern view the pom preview renders instead of the generic XML DOM tree.
 *
 * <p>Deliberately a second reader beside {@link PomParser}/{@link PomModel} rather than an extension of them:
 * {@code PomModel} exists to answer "what can this project <em>run</em>" (it models only {@code
 * <build>/<plugins>} and their executions, because that is what the build-tool actions tree needs), while this
 * one answers "what does this project <em>declare</em>". Widening the record would have changed its canonical
 * constructor for every existing caller to serve a use case that shares none of its fields.
 *
 * <p>Like {@code structured/XmlParser} — and unlike {@code PomParser}, whose callers want a hard failure —
 * parsing here is <b>tolerant</b>: a malformed document comes back as an otherwise-empty summary carrying an
 * {@link #error()} message (and the line/column SAX knew). Pure (no toolkit), called off the FX thread, and
 * XXE-hardened via {@link PomDom} (DOCTYPE disallowed). Every element row remembers the line it starts on
 * so the preview can take the reader to it.
 *
 * <p>This is <b>not</b> an effective pom. {@link #parse(String)} reads one file: a {@code ${property}}
 * version (from {@code <properties>} plus the {@code project.*} built-ins) and a blank version filled in from
 * this file's own {@code <dependencyManagement>}/{@code <pluginManagement>}. {@link #parse(String, Path)}
 * also reads what sits <em>beside</em> a local file — the parent chain on disk and the BOMs imported from the
 * local repository ({@link PomNeighbours}) — and tags a version it took from there with its source. A
 * version no readable file supplies is reported honestly: "inherited" when a {@code <parent>} is declared,
 * "unresolved" when a {@code ${…}} has no definition.
 */
public record PomSummary(
        @Nullable Coordinates parent,
        @Nullable String parentRelativePath,
        Coordinates coordinates,
        String packaging,
        String name,
        String description,
        String url,
        String scmUrl,
        List<String> modules,
        List<Integer> moduleLines,
        List<Property> properties,
        List<Dependency> dependencies,
        List<Dependency> managedDependencies,
        List<Plugin> plugins,
        List<Plugin> managedPlugins,
        List<Plugin> reportingPlugins,
        List<Repository> repositories,
        List<Profile> profiles,
        List<String> inheritedFrom,
        @Nullable String error,
        int errorLine,
        int errorColumn) {

    /** A {@code groupId:artifactId:version} triple; any part may be {@code ""} when not written. */
    public record Coordinates(String groupId, String artifactId, String version) {}

    /** One {@code <properties>} entry, in document order; {@code line} is 1-based (0 = unknown). */
    public record Property(String name, String value, int line) {}

    /**
     * One declared dependency.
     *
     * @param version the version exactly as written ({@code ""} when the element is absent, {@code
     *     "${junit.version}"} when it is a property reference)
     * @param effectiveVersion what that resolves to — the expanded property, or the version a {@code
     *     <dependencyManagement>} (this file's, an imported BOM's, a parent's) supplies for a blank one;
     *     {@code ""} when unknowable here
     * @param managed whether {@code effectiveVersion} came from a {@code <dependencyManagement>}
     * @param managedBy which one, when not this file's own: {@code "junit-bom 5.11.0 (import)"}, {@code
     *     "reactor-parent 2.0.0"}; {@code ""} for this file's. With {@code managed == false} and a blank
     *     {@code effectiveVersion} it names the BOM import the version <em>probably</em> comes from (the BOM
     *     itself was not readable)
     * @param line the 1-based line of the {@code <dependency>} element (0 = unknown)
     */
    public record Dependency(
            String groupId,
            String artifactId,
            String version,
            String effectiveVersion,
            boolean managed,
            String managedBy,
            String scope,
            String type,
            String classifier,
            boolean optional,
            int line) {

        /** A BOM import ({@code <scope>import</scope><type>pom</type>}) in a {@code <dependencyManagement>}. */
        public boolean bomImport() {
            return "import".equals(scope) && "pom".equals(type);
        }
    }

    /** One declared plugin; {@code version}/{@code effectiveVersion}/{@code managed}/{@code managedBy} as for
     *  {@link Dependency}. {@code goals} is the distinct goal list across its {@code <executions>}, in document
     *  order. */
    public record Plugin(
            String groupId,
            String artifactId,
            String version,
            String effectiveVersion,
            boolean managed,
            String managedBy,
            List<String> goals,
            int line) {}

    /** A {@code <repositories>}/{@code <pluginRepositories>} entry ({@code plugin} tells which). */
    public record Repository(String id, String url, boolean plugin, int line) {

        /** Whether the URL is Maven Central (or one of its official mirrors) — anything else is a trust question. */
        public boolean central() {
            String u = url.toLowerCase(java.util.Locale.ROOT);
            return u.contains("://repo.maven.apache.org/")
                    || u.contains("://repo1.maven.org/")
                    || u.contains("://repo.maven.org/")
                    || u.contains("://central.sonatype.com/")
                    || u.contains("://maven.apache.org/");
        }
    }

    /** A {@code <profile>} with its own properties/dependencies/plugins (not merged into the top level). */
    public record Profile(
            String id,
            boolean activeByDefault,
            ProfileActivation activation,
            List<Property> properties,
            List<Dependency> dependencies,
            List<Plugin> plugins,
            int line) {}

    /** Maven's own default for a {@code <plugin>} with no {@code <groupId>}. */
    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    /** Bounded so a self-referential property ({@code a=${b}}, {@code b=${a}}) terminates instead of spinning. */
    private static final int MAX_PROPERTY_HOPS = 10;

    /** The prefix {@link PomParser} puts on every parse failure; the preview adds its own ("Invalid pom.xml"). */
    private static final String MALFORMED_PREFIX = "Malformed pom.xml: ";

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
                && reportingPlugins.isEmpty()
                && repositories.isEmpty()
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

    private static PomSummary failed(String message, int line, int column) {
        return new PomSummary(
                null,
                "",
                new Coordinates("", "", ""),
                "",
                "",
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
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                message,
                line,
                column);
    }

    /** The one-file summary: nothing outside {@code xml} is read. */
    public static PomSummary parse(String xml) {
        return parse(xml, null, null);
    }

    /**
     * The summary of the pom at {@code path} (null ⇒ one-file): a local file's parent chain and imported BOMs
     * are read from disk and the local repository ({@link PomNeighbours}) to fill managed versions in.
     */
    public static PomSummary parse(String xml, @Nullable Path path) {
        return parse(xml, path, localRepository());
    }

    /** As {@link #parse(String, Path)} with the local repository supplied (tests). */
    static PomSummary parse(String xml, @Nullable Path path, @Nullable Path localRepo) {
        if (xml == null || xml.isBlank()) {
            return failed("empty document", 0, 0);
        }
        Element project;
        try {
            project = PomDom.parse(xml).getDocumentElement();
        } catch (PomDom.Failure e) {
            return failed(stripPrefix(firstLine(e.getMessage())), e.line, e.column);
        } catch (RuntimeException e) {
            return failed(firstLine(e.toString()), 0, 0);
        }
        if (project == null || !"project".equals(project.getTagName())) {
            return failed("not a Maven pom.xml (missing <project> root element)", 0, 0);
        }

        Element parentEl = PomParser.firstChildElement(project, "parent");
        Coordinates parent = parentEl == null
                ? null
                : new Coordinates(
                        childText(parentEl, "groupId"),
                        childText(parentEl, "artifactId"),
                        childText(parentEl, "version"));
        String parentRelativePath = parentEl == null ? "" : childText(parentEl, "relativePath");

        String groupId = childText(project, "groupId");
        String version = childText(project, "version");
        // Maven's own inheritance rule, and the only one this reader applies on its own: a project that omits
        // groupId or version takes the parent's. Managed versions from the parent need the parent file.
        if (groupId.isEmpty() && parent != null) {
            groupId = parent.groupId();
        }
        if (version.isEmpty() && parent != null) {
            version = parent.version();
        }
        Coordinates coordinates = new Coordinates(groupId, childText(project, "artifactId"), version);

        String packaging = childText(project, "packaging");
        List<Property> properties = parseProperties(PomParser.firstChildElement(project, "properties"));

        PomNeighbours.Resolved chain = parent == null || path == null
                ? PomNeighbours.Resolved.NONE
                : PomNeighbours.parentChain(path, parent, parentRelativePath, localRepo);

        // The substitution map: <properties>, then each parent's (nearest first — a child's definition wins),
        // plus the project.* built-ins a version string may reference.
        Map<String, String> props = new LinkedHashMap<>();
        for (Property p : properties) {
            props.put(p.name(), p.value());
        }
        for (PomNeighbours.Source s : chain.parents()) {
            s.properties().forEach(props::putIfAbsent);
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
                Management.NONE,
                parent != null);
        Management management = management(managedDependencies, chain, path == null ? null : localRepo);

        Element build = PomParser.firstChildElement(project, "build");
        List<Plugin> managedPlugins = parsePlugins(
                PomParser.firstChildElement(PomParser.firstChildElement(build, "pluginManagement"), "plugins"),
                props,
                Management.NONE);
        Management pluginManagement = pluginManagement(managedPlugins, chain);

        List<Profile> profiles = new ArrayList<>();
        Element profilesEl = PomParser.firstChildElement(project, "profiles");
        if (profilesEl != null) {
            for (Element profileEl : PomParser.childElements(profilesEl, "profile")) {
                ProfileActivation activation =
                        ProfileActivation.of(PomParser.firstChildElement(profileEl, "activation"));
                Element profileBuild = PomParser.firstChildElement(profileEl, "build");
                profiles.add(new Profile(
                        childText(profileEl, "id"),
                        activation.activeByDefault(),
                        activation,
                        parseProperties(PomParser.firstChildElement(profileEl, "properties")),
                        parseDependencies(
                                PomParser.firstChildElement(profileEl, "dependencies"),
                                props,
                                management,
                                parent != null),
                        parsePlugins(PomParser.firstChildElement(profileBuild, "plugins"), props, pluginManagement),
                        PomDom.lineOf(profileEl)));
            }
        }

        List<String> modules = new ArrayList<>();
        List<Integer> moduleLines = new ArrayList<>();
        for (Element m : PomParser.childElements(PomParser.firstChildElement(project, "modules"), "module")) {
            String moduleName = PomParser.strip(PomParser.text(m));
            if (!moduleName.isEmpty()) {
                modules.add(moduleName);
                moduleLines.add(PomDom.lineOf(m));
            }
        }

        List<Repository> repositories = new ArrayList<>();
        parseRepositories(PomParser.firstChildElement(project, "repositories"), "repository", false, repositories);
        parseRepositories(
                PomParser.firstChildElement(project, "pluginRepositories"), "pluginRepository", true, repositories);

        List<String> inheritedFrom = new ArrayList<>();
        for (PomNeighbours.Source s : chain.parents()) {
            inheritedFrom.add(s.label());
        }

        Element scm = PomParser.firstChildElement(project, "scm");
        String scmUrl = scm == null ? "" : childText(scm, "url");
        if (scmUrl.isEmpty() && scm != null) {
            scmUrl = childText(scm, "connection");
        }

        return new PomSummary(
                parent,
                parentRelativePath,
                coordinates,
                packaging.isEmpty() ? "jar" : packaging,
                childText(project, "name"),
                collapseWhitespace(childText(project, "description")),
                childText(project, "url"),
                scmUrl,
                List.copyOf(modules),
                List.copyOf(moduleLines),
                properties,
                parseDependencies(
                        PomParser.firstChildElement(project, "dependencies"), props, management, parent != null),
                managedDependencies,
                parsePlugins(PomParser.firstChildElement(build, "plugins"), props, pluginManagement),
                managedPlugins,
                parsePlugins(
                        PomParser.firstChildElement(PomParser.firstChildElement(project, "reporting"), "plugins"),
                        props,
                        pluginManagement),
                List.copyOf(repositories),
                List.copyOf(profiles),
                List.copyOf(inheritedFrom),
                null,
                0,
                0);
    }

    // --- managed-version sources --------------------------------------------------------------------

    /** A managed version and where it came from ({@code ""} = this file). */
    private record Managed(String version, String source) {}

    /**
     * Where a blank version may come from, in Maven's precedence: this file's own management, then the BOMs
     * it imports (read from the local repository), then the parent chain. {@code bomGuesses} are the BOM
     * imports that could not be read — a parentless pom's blank versions are attributed to them by name.
     */
    private record Management(Map<String, Managed> versions, List<String> bomGuesses) {
        static final Management NONE = new Management(Map.of(), List.of());

        @Nullable
        Managed lookup(String key) {
            return versions.get(key);
        }
    }

    private static Management management(List<Dependency> own, PomNeighbours.Resolved chain, @Nullable Path localRepo) {
        Map<String, Managed> versions = new LinkedHashMap<>();
        List<String> guesses = new ArrayList<>();
        for (Dependency d : own) {
            if (d.bomImport()) {
                continue; // the BOM artifact itself is not a managed version of anything
            }
            if (!d.effectiveVersion().isEmpty()) {
                versions.putIfAbsent(d.groupId() + ":" + d.artifactId(), new Managed(d.effectiveVersion(), ""));
            }
        }
        for (Dependency d : own) {
            if (!d.bomImport()) {
                continue;
            }
            Optional<PomNeighbours.Source> bom = localRepo == null
                    ? Optional.empty()
                    : PomNeighbours.bom(localRepo, d.groupId(), d.artifactId(), d.effectiveVersion());
            if (bom.isPresent()) {
                PomNeighbours.Source s = bom.get();
                s.managedDependencyVersions().forEach((k, v) -> versions.putIfAbsent(k, new Managed(v, s.label())));
            } else {
                String v = d.effectiveVersion().isEmpty() ? d.version() : d.effectiveVersion();
                guesses.add(d.artifactId() + (v.isEmpty() ? "" : " " + v) + " (import)");
            }
        }
        for (PomNeighbours.Source s : chain.parents()) {
            s.managedDependencyVersions().forEach((k, v) -> versions.putIfAbsent(k, new Managed(v, s.label())));
        }
        return new Management(versions, guesses);
    }

    private static Management pluginManagement(List<Plugin> own, PomNeighbours.Resolved chain) {
        Map<String, Managed> versions = new LinkedHashMap<>();
        for (Plugin p : own) {
            if (!p.effectiveVersion().isEmpty()) {
                versions.putIfAbsent(p.groupId() + ":" + p.artifactId(), new Managed(p.effectiveVersion(), ""));
            }
        }
        for (PomNeighbours.Source s : chain.parents()) {
            s.managedPluginVersions().forEach((k, v) -> versions.putIfAbsent(k, new Managed(v, s.label())));
        }
        return new Management(versions, List.of());
    }

    // --- element readers ----------------------------------------------------------------------------

    private static void putBuiltIn(Map<String, String> props, String key, String value) {
        // A real <properties> entry of the same name wins — that is what Maven itself resolves to.
        if (!value.isEmpty()) {
            props.putIfAbsent(key, value);
        }
    }

    private static List<Property> parseProperties(@Nullable Element propertiesEl) {
        List<Property> out = new ArrayList<>();
        if (propertiesEl == null) {
            return List.of();
        }
        // Every child element is a property, whatever it is named — so this is the one place that can't use
        // PomParser.childElements(parent, tagName).
        for (org.w3c.dom.Node n = propertiesEl.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                Element el = (Element) n;
                out.add(new Property(
                        n.getNodeName(), collapseWhitespace(PomParser.strip(PomParser.text(el))), PomDom.lineOf(el)));
            }
        }
        return List.copyOf(out);
    }

    private static List<Dependency> parseDependencies(
            @Nullable Element dependenciesEl, Map<String, String> props, Management management, boolean hasParent) {
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
            String managedBy = "";
            if (resolved.isEmpty() || containsPlaceholder(resolved)) {
                Managed m = management.lookup(groupId + ":" + artifactId);
                if (m != null && !m.version().isEmpty()) {
                    resolved = m.version();
                    managed = true;
                    managedBy = m.source();
                } else if (written.isEmpty()
                        && !hasParent
                        && !management.bomGuesses().isEmpty()) {
                    // No parent to inherit from: a BOM import is the only place the version can come from.
                    managedBy = guessBom(groupId, management.bomGuesses());
                }
            }
            out.add(new Dependency(
                    groupId,
                    artifactId,
                    written,
                    containsPlaceholder(resolved) ? "" : resolved,
                    managed,
                    managedBy,
                    childText(el, "scope"),
                    childText(el, "type"),
                    childText(el, "classifier"),
                    "true".equalsIgnoreCase(childText(el, "optional")),
                    PomDom.lineOf(el)));
        }
        return List.copyOf(out);
    }

    /** The one BOM when there is one; else the BOM whose name shares the dependency's group ("junit" in
     *  "org.junit.jupiter" ↔ "junit-bom"); else the first. A guess, and labelled as one by the preview. */
    private static String guessBom(String groupId, List<String> guesses) {
        if (guesses.size() == 1) {
            return guesses.get(0);
        }
        for (String g : guesses) {
            String bomName = g.contains(" ") ? g.substring(0, g.indexOf(' ')) : g;
            String stem = bomName.endsWith("-bom") ? bomName.substring(0, bomName.length() - 4) : bomName;
            if (!stem.isEmpty() && groupId.contains(stem)) {
                return g;
            }
        }
        return guesses.get(0);
    }

    private static List<Plugin> parsePlugins(
            @Nullable Element pluginsEl, Map<String, String> props, Management management) {
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
            String managedBy = "";
            if (resolved.isEmpty() || containsPlaceholder(resolved)) {
                Managed m = management.lookup(groupId + ":" + artifactId);
                if (m != null && !m.version().isEmpty()) {
                    resolved = m.version();
                    managed = true;
                    managedBy = m.source();
                }
            }
            out.add(new Plugin(
                    groupId,
                    artifactId,
                    written,
                    containsPlaceholder(resolved) ? "" : resolved,
                    managed,
                    managedBy,
                    goalsOf(el),
                    PomDom.lineOf(el)));
        }
        return List.copyOf(out);
    }

    private static void parseRepositories(@Nullable Element listEl, String tag, boolean plugin, List<Repository> out) {
        for (Element el : PomParser.childElements(listEl, tag)) {
            String url = childText(el, "url");
            String id = childText(el, "id");
            if (url.isEmpty() && id.isEmpty()) {
                continue;
            }
            out.add(new Repository(id, url, plugin, PomDom.lineOf(el)));
        }
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

    /** Whether {@code value} still carries a {@code ${…}} reference (an unresolved property). */
    public static boolean containsPlaceholder(String value) {
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

    private static String stripPrefix(String message) {
        return message.startsWith(MALFORMED_PREFIX) ? message.substring(MALFORMED_PREFIX.length()) : message;
    }

    private static String firstLine(@Nullable String message) {
        if (message == null) {
            return "could not be parsed";
        }
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).strip();
    }

    private static @Nullable Path localRepository() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        try {
            return MavenPluginGoals.localRepository(Path.of(home));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
