package com.editora.editor;

import java.util.List;

import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.i18n.Messages;
import com.editora.maven.PomSummary;

/**
 * Renders a {@link PomSummary} as the pom.xml preview: a header (artifact name, coordinates, packaging,
 * parent) followed by one section per concern — modules, properties, dependencies, managed dependencies,
 * plugins, managed plugins, and each profile's own set. Every row puts the artifact name and its version in
 * aligned columns, which is the whole point of the view: the XML tree can show the same facts, but only
 * spread over four nested rows per dependency.
 *
 * <p>A version that the file writes indirectly is shown <em>resolved</em>, with the indirection kept beside
 * it as a muted tag ({@code ${junit.version}}, {@code managed}) — so the number is readable at a glance
 * without hiding where it came from. A version this file cannot resolve (inherited from a parent pom) says
 * so rather than showing an empty cell.
 *
 * <p>Every section shares <b>one</b> {@link GridPane}, with section titles as full-width rows, because a
 * {@code GridPane} sizes its columns to its own contents: a grid per section made each one align only with
 * itself, so the version column visibly jumped left and right down the page — worst between a profile's
 * plugins and the top-level ones, which is exactly the comparison the view exists to make.
 *
 * <p>Self-scrolling; hosts as the Split/Preview side like {@link StructuredTree}. Kept in {@code editor}
 * (no {@code ui} dependency), mirroring {@link DockerfilePreview}.
 */
public final class PomPreview {

    /** Floor for the version column, so a short version can't leave its row's tags crowding it. */
    private static final double MIN_VERSION_COLUMN_WIDTH = 110;

    /** Indent applied to a profile's rows, so they read as belonging to the profile heading above them. */
    private static final javafx.geometry.Insets PROFILE_INDENT = new javafx.geometry.Insets(0, 0, 0, 14);

    private PomPreview() {}

    public static Node build(PomSummary summary) {
        ScrollPane sp = new ScrollPane(content(summary, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("pom-preview-scroll");
        return sp;
    }

    public static VBox content(PomSummary s, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("pom-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        if (!s.ok()) {
            root.getChildren().add(styledLabel(Messages.tr("pom.invalid", s.error()), "pom-error"));
            return root;
        }

        root.getChildren().add(header(s));

        GridPane g = grid();
        addModules(g, s.modules(), false);
        addProperties(g, s.properties(), false);
        addDependencies(g, Messages.tr("pom.dependencies", s.dependencies().size()), s.dependencies(), false);
        addDependencies(
                g,
                Messages.tr("pom.managedDependencies", s.managedDependencies().size()),
                s.managedDependencies(),
                false);
        addPlugins(g, Messages.tr("pom.plugins", s.plugins().size()), s.plugins(), false);
        addPlugins(g, Messages.tr("pom.managedPlugins", s.managedPlugins().size()), s.managedPlugins(), false);

        for (PomSummary.Profile profile : s.profiles()) {
            addProfile(g, profile);
        }
        if (g.getRowCount() > 0) {
            root.getChildren().add(g);
        }
        if (s.isEmpty()) {
            root.getChildren().add(styledLabel(Messages.tr("pom.nothingDeclared"), "pom-empty"));
        }
        return root;
    }

    // --- header ------------------------------------------------------------------------------------

    private static Node header(PomSummary s) {
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
            box.getChildren().add(styledLabel(Messages.tr("pom.parent", gav(s.parent())), "pom-parent"));
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

    // --- sections ----------------------------------------------------------------------------------

    private static void addModules(GridPane g, List<String> modules, boolean indented) {
        if (modules.isEmpty()) {
            return;
        }
        addSection(g, Messages.tr("pom.modules", modules.size()), indented);
        for (String module : modules) {
            addRow(g, mono(module, "pom-name"), null, null, indented);
        }
    }

    private static void addProperties(GridPane g, List<PomSummary.Property> properties, boolean indented) {
        if (properties.isEmpty()) {
            return;
        }
        addSection(g, Messages.tr("pom.properties", properties.size()), indented);
        for (PomSummary.Property p : properties) {
            int row = g.getRowCount();
            g.add(indent(mono(p.name(), "pom-name"), indented), 0, row);
            Label value = mono(p.value(), "pom-value");
            // A property value can be far longer than any version (a path, a timestamp format), so let it run
            // into the tag column instead of widening the version column for every dependency below it.
            GridPane.setColumnSpan(value, 2);
            g.add(value, 1, row);
        }
    }

    private static void addDependencies(GridPane g, String title, List<PomSummary.Dependency> deps, boolean indented) {
        if (deps.isEmpty()) {
            return;
        }
        addSection(g, title, indented);
        for (PomSummary.Dependency d : deps) {
            addRow(g, artifactName(d.groupId(), d.artifactId()), versionCell(d), dependencyTags(d), indented);
        }
    }

    private static void addPlugins(GridPane g, String title, List<PomSummary.Plugin> plugins, boolean indented) {
        if (plugins.isEmpty()) {
            return;
        }
        addSection(g, title, indented);
        for (PomSummary.Plugin p : plugins) {
            addRow(
                    g,
                    artifactName(p.groupId(), p.artifactId()),
                    versionCell(p.version(), p.effectiveVersion(), p.managed()),
                    versionTag(p.version(), p.effectiveVersion(), p.managed()),
                    indented);
            if (!p.goals().isEmpty()) {
                addFullWidth(
                        g, styledLabel(Messages.tr("pom.goals", String.join(", ", p.goals())), "pom-goals"), indented);
            }
        }
    }

    private static void addProfile(GridPane g, PomSummary.Profile profile) {
        String id = profile.id().isEmpty() ? Messages.tr("pom.untitled") : profile.id();
        String title =
                profile.activeByDefault() ? Messages.tr("pom.profileActive", id) : Messages.tr("pom.profile", id);
        addFullWidth(g, styledLabel(title, "pom-profile-title"), false);

        addProperties(g, profile.properties(), true);
        addDependencies(
                g, Messages.tr("pom.dependencies", profile.dependencies().size()), profile.dependencies(), true);
        addPlugins(g, Messages.tr("pom.plugins", profile.plugins().size()), profile.plugins(), true);
        if (profile.properties().isEmpty()
                && profile.dependencies().isEmpty()
                && profile.plugins().isEmpty()) {
            addFullWidth(g, styledLabel(Messages.tr("pom.nothingDeclared"), "pom-empty"), true);
        }
    }

    // --- cells -------------------------------------------------------------------------------------

    /** {@code groupId:} muted, {@code artifactId} in the foreground — the part being looked for reads first. */
    private static Node artifactName(String groupId, String artifactId) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("pom-name");
        if (!groupId.isEmpty()) {
            flow.getChildren().add(text(groupId + ":", "pom-group"));
        }
        flow.getChildren().add(text(artifactId, "pom-artifact"));
        return flow;
    }

    private static Node versionCell(PomSummary.Dependency d) {
        return versionCell(d.version(), d.effectiveVersion(), d.managed());
    }

    private static Node versionCell(String written, String effective, boolean managed) {
        String shown = !effective.isEmpty() ? effective : written;
        if (shown.isEmpty()) {
            return styledLabel(Messages.tr("pom.inherited"), "pom-version-unknown");
        }
        return mono(shown, managed ? "pom-version-managed" : "pom-version");
    }

    /** Where an indirect version came from — the property reference as written, or {@code managed}. */
    private static Node versionTag(String written, String effective, boolean managed) {
        String tag = versionSource(written, effective, managed);
        return tag.isEmpty() ? null : styledLabel(tag, "pom-tag");
    }

    private static String versionSource(String written, String effective, boolean managed) {
        if (managed) {
            return Messages.tr("pom.managed");
        }
        return !written.isEmpty() && !written.equals(effective) ? written : "";
    }

    private static Node dependencyTags(PomSummary.Dependency d) {
        StringBuilder sb = new StringBuilder(versionSource(d.version(), d.effectiveVersion(), d.managed()));
        // Scope is only worth the ink when it is not the default; type/classifier/optional always are.
        appendTag(sb, !d.scope().isEmpty() && !"compile".equals(d.scope()) ? d.scope() : "");
        appendTag(sb, !d.type().isEmpty() && !"jar".equals(d.type()) ? d.type() : "");
        appendTag(sb, d.classifier());
        appendTag(sb, d.optional() ? Messages.tr("pom.optional") : "");
        return sb.length() == 0 ? null : styledLabel(sb.toString(), "pom-tag");
    }

    private static void appendTag(StringBuilder sb, String tag) {
        if (!tag.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(tag);
        }
    }

    // --- layout helpers ----------------------------------------------------------------------------

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.getStyleClass().add("pom-grid");
        ColumnConstraints name = new ColumnConstraints();
        name.setHalignment(HPos.LEFT);
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

    private static void addRow(GridPane g, Node name, Node version, Node tags, boolean indented) {
        int row = g.getRowCount();
        g.add(indent(name, indented), 0, row);
        if (version != null) {
            g.add(version, 1, row);
        }
        if (tags != null) {
            g.add(tags, 2, row);
        }
    }

    private static void addSection(GridPane g, String title, boolean indented) {
        addFullWidth(g, styledLabel(title, "pom-section"), indented);
    }

    /** Adds a node spanning all three columns — section titles, profile titles, a plugin's goal list. */
    private static void addFullWidth(GridPane g, Node node, boolean indented) {
        GridPane.setColumnSpan(node, 3);
        g.add(indent(node, indented), 0, g.getRowCount());
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
        return l;
    }

    private static Label styledLabel(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        return l;
    }

    private static Text text(String content, String styleClass) {
        Text t = new Text(content);
        t.getStyleClass().add(styleClass);
        return t;
    }
}
