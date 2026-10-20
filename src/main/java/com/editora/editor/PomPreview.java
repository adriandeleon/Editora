package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.maven.PomSummary;

/**
 * Renders a {@link PomSummary} as the pom.xml preview: a header (artifact name, coordinates, packaging,
 * parent, url/scm) followed by one section per concern — modules, dependencies, managed dependencies,
 * plugins, managed plugins, reporting plugins, repositories, properties, and each profile's own set. Every
 * row puts the artifact name and its version in aligned columns, which is the whole point of the view: the
 * XML tree can show the same facts, but only spread over four nested rows per dependency.
 *
 * <p>A version that the file writes indirectly is shown <em>resolved</em>, with the indirection kept beside
 * it as a muted tag ({@code ${junit.version}}, {@code managed}, {@code from junit-bom 5.11.0 (import)}) — so
 * the number is readable at a glance without hiding where it came from. A version no readable file supplies
 * says so ("inherited" under a {@code <parent>}, "unresolved" for a dangling {@code ${…}}) rather than
 * showing an empty cell.
 *
 * <p>Every section shares <b>one</b> {@link GridPane}, with section titles as full-width rows, because a
 * {@code GridPane} sizes its columns to its own contents: a grid per section made each one align only with
 * itself, so the version column visibly jumped left and right down the page. The name column never shrinks
 * below its widest {@code groupId:artifactId} and tags wrap only between tags (Maven review C7): a narrow
 * split scrolls sideways instead of breaking "junit-jupiter-api" in two.
 *
 * <p>Interactive (Maven review C9): a name or version cell takes the editor to the element's line, section
 * titles collapse their rows (Properties starts collapsed past {@link #PROPERTIES_COLLAPSED_PAST} entries),
 * and a filter field at the top narrows the rows. {@link #content} renders the same sections without those
 * controls for the PDF/print export.
 *
 * <p>Self-scrolling; hosts as the Split/Preview side like {@link StructuredTree}. Kept in {@code editor}
 * (no {@code ui} dependency), mirroring {@link DockerfilePreview}.
 */
public final class PomPreview {

    /** Floor for the version column, so a short version can't leave its row's tags crowding it. */
    private static final double MIN_VERSION_COLUMN_WIDTH = 110;

    /** A Properties section longer than this starts collapsed: on a real pom it filled the first screen. */
    public static final int PROPERTIES_COLLAPSED_PAST = 12;

    /** Indent applied to a profile's rows, so they read as belonging to the profile heading above them. */
    private static final javafx.geometry.Insets PROFILE_INDENT = new javafx.geometry.Insets(0, 0, 0, 14);

    private PomPreview() {}

    /** The interactive preview without navigation (rows collapse and filter, but go nowhere). */
    public static Node build(PomSummary summary) {
        return build(summary, null);
    }

    /** The interactive preview: {@code onNavigate} receives the 1-based line of the element clicked. */
    public static Node build(PomSummary summary, IntConsumer onNavigate) {
        Renderer r = new Renderer(onNavigate, true);
        VBox body = r.render(summary, -1);
        ScrollPane sp = new ScrollPane(body);
        // Fit to the viewport width, but no narrower than the grid's own minimum: the name column's widest
        // entry sets that, so a cramped split scrolls sideways rather than squeezing the columns together.
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.getStyleClass().add("pom-preview-scroll");
        return sp;
    }

    /** The static rendering (export): every section expanded, no filter, no navigation. */
    public static VBox content(PomSummary s, double width) {
        return new Renderer(null, false).render(s, width);
    }

    /** One rendered row of a section: its grid nodes and the text the filter matches against. */
    private record Row(List<Node> nodes, String searchText) {}

    /** A section: its title node and rows; collapsed hides the rows (unless a filter is active). */
    private static final class Section {
        final Node title;
        final Label titleLabel;
        final List<Row> rows = new ArrayList<>();
        final List<Node> extras = new ArrayList<>(); // full-width lines that belong to the section (goal lists)
        final Section parent; // the profile heading a profile's sub-section sits under, else null
        boolean collapsed;

        Section(Node title, Label titleLabel, Section parent) {
            this.title = title;
            this.titleLabel = titleLabel;
            this.parent = parent;
        }
    }

    private static final class Renderer {
        private final IntConsumer onNavigate;
        private final boolean interactive;
        private final List<Section> sections = new ArrayList<>();
        private Section current;
        private Section profileSection; // set while a profile's sub-sections are added
        private String filter = "";

        Renderer(IntConsumer onNavigate, boolean interactive) {
            this.onNavigate = onNavigate;
            this.interactive = interactive;
        }

        VBox render(PomSummary s, double width) {
            VBox root = new VBox();
            root.getStyleClass().add("pom-preview");
            if (width > 0) {
                root.setPrefWidth(width);
            }

            if (!s.ok()) {
                root.getChildren().add(errorLabel(s));
                return root;
            }

            if (interactive) {
                root.getChildren().add(filterField());
            }
            root.getChildren().add(header(s));

            GridPane g = grid();
            addModules(g, s, false);
            addDependencies(g, Messages.tr("pom.dependencies", s.dependencies().size()), s.dependencies(), s, false);
            addDependencies(
                    g,
                    Messages.tr(
                            "pom.managedDependencies", s.managedDependencies().size()),
                    s.managedDependencies(),
                    s,
                    false);
            addPlugins(g, Messages.tr("pom.plugins", s.plugins().size()), s.plugins(), s, false);
            addPlugins(g, Messages.tr("pom.managedPlugins", s.managedPlugins().size()), s.managedPlugins(), s, false);
            addPlugins(
                    g,
                    Messages.tr("pom.reportingPlugins", s.reportingPlugins().size()),
                    s.reportingPlugins(),
                    s,
                    false);
            addRepositories(g, s.repositories());
            addProperties(
                    g, s.properties(), false, interactive && s.properties().size() > PROPERTIES_COLLAPSED_PAST);

            for (PomSummary.Profile profile : s.profiles()) {
                addProfile(g, profile, s);
            }
            if (g.getRowCount() > 0) {
                root.getChildren().add(g);
            }
            if (s.isEmpty()) {
                root.getChildren().add(styledLabel(Messages.tr("pom.nothingDeclared"), "pom-empty"));
            }
            applyVisibility();
            return root;
        }

        // --- header ----------------------------------------------------------------------------------

        private Node errorLabel(PomSummary s) {
            String text = s.errorLine() > 0
                    ? Messages.tr("pom.invalidAt", s.errorLine(), s.errorColumn(), s.error())
                    : Messages.tr("pom.invalid", s.error());
            Label l = styledLabel(text, "pom-error");
            if (s.errorLine() > 0) {
                link(l, s.errorLine());
            }
            return l;
        }

        private Node filterField() {
            TextField field = new TextField();
            field.setPromptText(Messages.tr("pom.filterPrompt"));
            field.getStyleClass().add("pom-filter");
            field.textProperty().addListener((obs, was, now) -> {
                filter = now == null ? "" : now.strip().toLowerCase(Locale.ROOT);
                applyVisibility();
            });
            return field;
        }

        private Node header(PomSummary s) {
            VBox box = new VBox();
            box.getStyleClass().add("pom-header");

            String artifactId = s.coordinates().artifactId();
            box.getChildren()
                    .add(styledLabel(artifactId.isEmpty() ? Messages.tr("pom.untitled") : artifactId, "pom-title"));
            box.getChildren().add(styledLabel(coordinateLine(s), "pom-coords"));

            // <name> is usually a prettier spelling of the artifactId ("Editora" for "editora") — only worth a
            // line of its own when it actually says something the title doesn't.
            if (!s.name().isEmpty() && !s.name().equalsIgnoreCase(artifactId)) {
                box.getChildren().add(styledLabel(s.name(), "pom-name-line"));
            }
            if (!s.description().isEmpty()) {
                box.getChildren().add(styledLabel(s.description(), "pom-desc"));
            }
            if (s.parent() != null) {
                String parentLine = Messages.tr("pom.parent", gav(s.parent()));
                if (!s.inheritedFrom().isEmpty()) {
                    parentLine += " · " + Messages.tr("pom.parentRead", String.join(" → ", s.inheritedFrom()));
                }
                box.getChildren().add(styledLabel(parentLine, "pom-parent"));
            }
            if (!s.url().isEmpty()) {
                box.getChildren().add(styledLabel(Messages.tr("pom.url", s.url()), "pom-parent"));
            }
            if (!s.scmUrl().isEmpty()) {
                box.getChildren().add(styledLabel(Messages.tr("pom.scm", s.scmUrl()), "pom-parent"));
            }
            return box;
        }

        private static String coordinateLine(PomSummary s) {
            String gav = gav(s.coordinates());
            return s.packaging().isEmpty() ? gav : gav + " · " + s.packaging();
        }

        private static String gav(PomSummary.Coordinates c) {
            StringBuilder sb = new StringBuilder();
            if (!c.groupId().isEmpty()) {
                sb.append(c.groupId()).append(':');
            }
            sb.append(c.artifactId().isEmpty() ? Messages.tr("pom.untitled") : c.artifactId());
            if (!c.version().isEmpty()) {
                sb.append(':').append(c.version());
            }
            return sb.toString();
        }

        // --- sections --------------------------------------------------------------------------------

        private void addModules(GridPane g, PomSummary s, boolean indented) {
            List<String> modules = s.modules();
            if (modules.isEmpty()) {
                return;
            }
            addSection(g, Messages.tr("pom.modules", modules.size()), indented, false);
            for (int i = 0; i < modules.size(); i++) {
                int line = i < s.moduleLines().size() ? s.moduleLines().get(i) : 0;
                addRow(g, link(mono(modules.get(i), "pom-name"), line), null, null, indented, modules.get(i));
            }
        }

        private void addProperties(
                GridPane g, List<PomSummary.Property> properties, boolean indented, boolean collapsed) {
            if (properties.isEmpty()) {
                return;
            }
            addSection(g, Messages.tr("pom.properties", properties.size()), indented, collapsed);
            for (PomSummary.Property p : properties) {
                int row = g.getRowCount();
                Node name = indent(link(mono(p.name(), "pom-name"), p.line()), indented);
                g.add(name, 0, row);
                Label value = mono(p.value(), "pom-value");
                // A property value can be far longer than any version (a path, a timestamp format), so let it
                // run into the tag column instead of widening the version column for every dependency below it.
                GridPane.setColumnSpan(value, 2);
                g.add(value, 1, row);
                current.rows.add(new Row(List.of(name, value), p.name() + " " + p.value()));
            }
        }

        private void addDependencies(
                GridPane g, String title, List<PomSummary.Dependency> deps, PomSummary s, boolean indented) {
            if (deps.isEmpty()) {
                return;
            }
            addSection(g, title, indented, false);
            for (PomSummary.Dependency d : deps) {
                List<String> tags = dependencyTags(d, s);
                addRow(
                        g,
                        link(artifactName(d.groupId(), d.artifactId()), d.line()),
                        link(versionCell(d.version(), d.effectiveVersion(), d.managed(), d.managedBy(), s), d.line()),
                        tagFlow(tags),
                        indented,
                        d.groupId() + ":" + d.artifactId() + " " + d.effectiveVersion() + " " + d.version() + " "
                                + String.join(" ", tags));
            }
        }

        private void addPlugins(
                GridPane g, String title, List<PomSummary.Plugin> plugins, PomSummary s, boolean indented) {
            if (plugins.isEmpty()) {
                return;
            }
            addSection(g, title, indented, false);
            for (PomSummary.Plugin p : plugins) {
                List<String> tags = new ArrayList<>();
                appendTag(tags, versionSource(p.version(), p.effectiveVersion(), p.managed(), p.managedBy()));
                String goals = p.goals().isEmpty() ? "" : String.join(", ", p.goals());
                Row row = addRow(
                        g,
                        link(artifactName(p.groupId(), p.artifactId()), p.line()),
                        link(versionCell(p.version(), p.effectiveVersion(), p.managed(), p.managedBy(), s), p.line()),
                        tagFlow(tags),
                        indented,
                        p.groupId() + ":" + p.artifactId() + " " + p.effectiveVersion() + " " + p.version() + " "
                                + String.join(" ", tags) + " " + goals);
                if (!goals.isEmpty()) {
                    Node line = addFullWidth(g, styledLabel(Messages.tr("pom.goals", goals), "pom-goals"), indented);
                    row.nodes().add(line); // shown and hidden with its plugin's row
                }
            }
        }

        private void addRepositories(GridPane g, List<PomSummary.Repository> repositories) {
            if (repositories.isEmpty()) {
                return;
            }
            addSection(g, Messages.tr("pom.repositories", repositories.size()), false, false);
            for (PomSummary.Repository r : repositories) {
                List<String> tags = new ArrayList<>();
                appendTag(tags, r.plugin() ? Messages.tr("pom.pluginRepository") : "");
                appendTag(tags, r.central() ? "" : Messages.tr("pom.notCentral"));
                Label url = mono(r.url(), "pom-value");
                url.setWrapText(false);
                addRow(
                        g,
                        link(mono(r.id().isEmpty() ? Messages.tr("pom.untitled") : r.id(), "pom-name"), r.line()),
                        url,
                        tagFlow(tags),
                        false,
                        r.id() + " " + r.url() + " " + String.join(" ", tags));
            }
        }

        private void addProfile(GridPane g, PomSummary.Profile profile, PomSummary s) {
            String id = profile.id().isEmpty() ? Messages.tr("pom.untitled") : profile.id();
            String title =
                    profile.activeByDefault() ? Messages.tr("pom.profileActive", id) : Messages.tr("pom.profile", id);
            // A profile heading is a section of its own: its rows (properties/dependencies/plugins are
            // sub-sections) collapse under it, and it is hidden when no row of it matches the filter.
            profileSection = null;
            Section heading = addSection(g, title, false, false);
            heading.titleLabel.getStyleClass().setAll("pom-profile-title");
            link(heading.titleLabel, profile.line());
            profileSection = heading;
            if (profile.activation().conditional()) {
                Node when = addFullWidth(
                        g,
                        styledLabel(
                                Messages.tr(
                                        "pom.activatesWhen",
                                        profile.activation().describe()),
                                "pom-activation"),
                        true);
                heading.extras.add(when);
            }
            addProperties(g, profile.properties(), true, false);
            addDependencies(
                    g, Messages.tr("pom.dependencies", profile.dependencies().size()), profile.dependencies(), s, true);
            addPlugins(g, Messages.tr("pom.plugins", profile.plugins().size()), profile.plugins(), s, true);
            if (profile.properties().isEmpty()
                    && profile.dependencies().isEmpty()
                    && profile.plugins().isEmpty()) {
                Node none = addFullWidth(g, styledLabel(Messages.tr("pom.nothingDeclared"), "pom-empty"), true);
                heading.extras.add(none);
            }
            current = heading; // sub-sections ended: anything more belongs to the profile again
            profileSection = null;
        }

        // --- cells -----------------------------------------------------------------------------------

        /** {@code groupId:} muted, {@code artifactId} in the foreground — the part being looked for reads
         *  first. Two labels in an HBox, neither wrapping: a name is never broken inside a word. */
        private static Node artifactName(String groupId, String artifactId) {
            HBox box = new HBox();
            box.getStyleClass().add("pom-name");
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMinWidth(Region.USE_PREF_SIZE);
            if (!groupId.isEmpty()) {
                box.getChildren().add(mono(groupId + ":", "pom-group"));
            }
            box.getChildren().add(mono(artifactId, "pom-artifact"));
            return box;
        }

        private static Node versionCell(
                String written, String effective, boolean managed, String managedBy, PomSummary s) {
            String shown = !effective.isEmpty() ? effective : written;
            if (shown.isEmpty() || PomSummary.containsPlaceholder(shown)) {
                if (!managedBy.isEmpty()) {
                    return styledLabel("—", "pom-version-unknown"); // the tag says which BOM it probably comes from
                }
                boolean dangling = PomSummary.containsPlaceholder(written);
                String key = !dangling && s.parent() != null ? "pom.inherited" : "pom.unresolved";
                return styledLabel(Messages.tr(key), "pom-version-unknown");
            }
            return mono(shown, managed ? "pom-version-managed" : "pom-version");
        }

        /** Where an indirect version came from — the property reference as written, {@code managed}, or the
         *  file that manages it. */
        private static String versionSource(String written, String effective, boolean managed, String managedBy) {
            if (managed) {
                return managedBy.isEmpty() ? Messages.tr("pom.managed") : Messages.tr("pom.from", managedBy);
            }
            if (!managedBy.isEmpty()) {
                return Messages.tr("pom.probablyFrom", managedBy);
            }
            return !written.isEmpty() && !written.equals(effective) ? written : "";
        }

        private static List<String> dependencyTags(PomSummary.Dependency d, PomSummary s) {
            List<String> tags = new ArrayList<>();
            appendTag(tags, versionSource(d.version(), d.effectiveVersion(), d.managed(), d.managedBy()));
            // Scope is only worth the ink when it is not the default; type/classifier/optional always are.
            appendTag(tags, !d.scope().isEmpty() && !"compile".equals(d.scope()) ? d.scope() : "");
            appendTag(tags, !d.type().isEmpty() && !"jar".equals(d.type()) ? d.type() : "");
            appendTag(tags, d.classifier());
            appendTag(tags, d.optional() ? Messages.tr("pom.optional") : "");
            return tags;
        }

        private static void appendTag(List<String> tags, String tag) {
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }

        /** The tags as one label each, separated by " · " labels, in a pane that wraps only between them. */
        private static Node tagFlow(List<String> tags) {
            if (tags.isEmpty()) {
                return null;
            }
            FlowPane flow = new FlowPane(4, 0) {
                @Override
                protected double computeMinWidth(double height) {
                    // Never narrower than the widest tag: a FlowPane's own minimum is its wrap length, so a
                    // cramped column clipped "from junit-bom 5.11.0 (import)" to "from junit-" instead of
                    // letting the preview scroll sideways.
                    double widest = 0;
                    for (Node child : getManagedChildren()) {
                        widest = Math.max(widest, child.prefWidth(-1));
                    }
                    return snappedLeftInset() + widest + snappedRightInset();
                }
            };
            flow.getStyleClass().add("pom-tags");
            flow.setAlignment(Pos.CENTER_LEFT);
            // A FlowPane's preferred width is its wrap length (400 by default), which widened the grid past a
            // narrow split and wrapped the tags at 400 whatever the column got; ask for nothing and take the
            // column's slack instead, wrapping only when the tags really do not fit on one line.
            flow.setPrefWrapLength(1);
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) {
                    flow.getChildren().add(tagLabel("·"));
                }
                flow.getChildren().add(tagLabel(tags.get(i)));
            }
            return flow;
        }

        private static Label tagLabel(String text) {
            Label l = new Label(text);
            l.getStyleClass().add("pom-tag");
            l.setMinWidth(Region.USE_PREF_SIZE);
            return l;
        }

        /** Makes {@code node} take the editor to {@code line} (1-based) on click; a no-op without a target. */
        private <T extends Node> T link(T node, int line) {
            if (onNavigate == null || line <= 0) {
                return node;
            }
            node.getStyleClass().add("pom-link");
            node.setCursor(Cursor.HAND);
            node.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    onNavigate.accept(line);
                    e.consume();
                }
            });
            if (node instanceof javafx.scene.control.Control c) {
                c.setTooltip(new Tooltip(Messages.tr("pom.gotoLine", line)));
            }
            return node;
        }

        // --- layout helpers --------------------------------------------------------------------------

        private static GridPane grid() {
            GridPane g = new GridPane();
            g.getStyleClass().add("pom-grid");
            ColumnConstraints name = new ColumnConstraints();
            name.setHalignment(HPos.LEFT);
            name.setMinWidth(Region.USE_PREF_SIZE); // never narrower than the widest groupId:artifactId
            ColumnConstraints version = new ColumnConstraints();
            version.setMinWidth(MIN_VERSION_COLUMN_WIDTH);
            version.setHalignment(HPos.LEFT);
            // The slack goes to the trailing tag column, so name and version stay together at the left rather
            // than being pushed apart across the whole width of a wide preview.
            ColumnConstraints tags = new ColumnConstraints();
            tags.setHgrow(Priority.ALWAYS);
            tags.setHalignment(HPos.LEFT);
            g.getColumnConstraints().addAll(name, version, tags);
            return g;
        }

        private Row addRow(GridPane g, Node name, Node version, Node tags, boolean indented, String searchText) {
            int row = g.getRowCount();
            List<Node> nodes = new ArrayList<>();
            g.add(indent(name, indented), 0, row);
            nodes.add(name);
            if (version != null) {
                g.add(version, 1, row);
                nodes.add(version);
            }
            if (tags != null) {
                g.add(tags, 2, row);
                nodes.add(tags);
            }
            Row r = new Row(nodes, searchText.toLowerCase(Locale.ROOT));
            current.rows.add(r);
            return r;
        }

        private Section addSection(GridPane g, String title, boolean indented, boolean collapsed) {
            Label label = styledLabel(title, "pom-section");
            Node node = addFullWidth(g, label, indented);
            Section s = new Section(node, label, profileSection);
            s.collapsed = collapsed;
            if (interactive) {
                label.setCursor(Cursor.HAND);
                label.setOnMouseClicked(e -> {
                    s.collapsed = !s.collapsed;
                    applyVisibility();
                    e.consume();
                });
            }
            sections.add(s);
            current = s;
            return s;
        }

        /** Adds a node spanning all three columns — section titles, profile titles, a plugin's goal list. */
        private static Node addFullWidth(GridPane g, Node node, boolean indented) {
            GridPane.setColumnSpan(node, 3);
            g.add(indent(node, indented), 0, g.getRowCount());
            return node;
        }

        /** Applies the collapse state and the filter to every section (an active filter overrides collapsing). */
        private void applyVisibility() {
            boolean filtering = !filter.isEmpty();
            java.util.Set<Section> withMatches = new java.util.HashSet<>();
            for (Section s : sections) {
                // A collapsed profile heading hides its sub-sections too; a filter overrides every collapse.
                boolean open = filtering || !s.collapsed && (s.parent == null || !s.parent.collapsed);
                for (Row r : s.rows) {
                    boolean visible = !filtering || r.searchText().contains(filter);
                    if (visible) {
                        withMatches.add(s);
                        if (s.parent != null) {
                            withMatches.add(s.parent);
                        }
                    }
                    for (Node n : r.nodes()) {
                        show(n, visible && open);
                    }
                }
            }
            for (Section s : sections) {
                boolean open = filtering || !s.collapsed && (s.parent == null || !s.parent.collapsed);
                boolean headingShown = s.parent == null || filtering || !s.parent.collapsed;
                show(s.title, headingShown && (!filtering || withMatches.contains(s)));
                for (Node extra : s.extras) {
                    show(extra, !filtering && open);
                }
                if (interactive) {
                    String text = s.titleLabel.getText();
                    String bare = text.startsWith("▸ ") || text.startsWith("▾ ") ? text.substring(2) : text;
                    s.titleLabel.setText((s.collapsed && !filtering ? "▸ " : "▾ ") + bare);
                }
            }
        }

        private static void show(Node n, boolean shown) {
            n.setVisible(shown);
            n.setManaged(shown);
        }

        private static Node indent(Node node, boolean indented) {
            if (indented) {
                GridPane.setMargin(node, PROFILE_INDENT);
            }
            return node;
        }

        private static Label mono(String text, String styleClass) {
            Label l = new Label(text);
            l.getStyleClass().addAll("pom-mono", styleClass);
            l.setMinWidth(Region.USE_PREF_SIZE);
            return l;
        }

        private static Label styledLabel(String text, String styleClass) {
            Label l = new Label(text);
            l.getStyleClass().add(styleClass);
            l.setWrapText(true);
            return l;
        }
    }
}
