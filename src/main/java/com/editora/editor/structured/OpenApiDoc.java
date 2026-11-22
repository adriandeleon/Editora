package com.editora.editor.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.structured.OpenApiModel;
import org.jspecify.annotations.Nullable;

/**
 * Renders an {@link OpenApiModel} into browsable, Redoc-style API docs for the 3-mode preview: a title
 * header, servers, endpoints grouped by path (colored method badges + params + responses), and schemas.
 * Self-scrolling (wrapped in a {@code ScrollPane}) so it hosts directly as the Split/Preview side, like
 * {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 *
 * <p>The live docs are a virtualized list of those rows ({@link PreviewList}, PERF-08, Pauta 6.2.0): a spec of
 * hundreds of operations and schemas builds the rows on screen. {@link #content} builds them all, for a print.
 */
public final class OpenApiDoc {

    private OpenApiDoc() {}

    public static Node build(@Nullable OpenApiModel m) {
        return PreviewList.of(rows(m), "openapi-doc-list");
    }

    /**
     * The docs themselves, without the list {@link #build} shows them in — what Print and Export to PDF snapshot
     * (at a width they set), since a list would give them only the rows on screen.
     */
    public static VBox content(@Nullable OpenApiModel m) {
        return PreviewList.column(rows(m), -1, "openapi-doc");
    }

    /** The title and description, then the servers, the endpoints and the schemas, each under its heading. */
    private static List<Supplier<Node>> rows(@Nullable OpenApiModel m) {
        List<Supplier<Node>> rows = new ArrayList<>();
        if (m == null) {
            return rows;
        }

        String title = m.title() == null || m.title().isBlank() ? Messages.tr("openapi.untitled") : m.title();
        String heading = m.version() == null || m.version().isBlank() ? title : title + "  " + m.version();
        rows.add(() -> {
            Label h = new Label(heading);
            h.getStyleClass().add("openapi-title");
            h.setWrapText(true);
            return h;
        });
        String description = m.description();
        if (description != null && !description.isBlank()) {
            rows.add(() -> wrapped(description, "openapi-desc"));
        }

        if (!m.servers().isEmpty()) {
            rows.add(() -> section(Messages.tr("openapi.servers")));
            for (OpenApiModel.Server s : m.servers()) {
                rows.add(() -> wrapped(s.url(), "openapi-server"));
            }
        }

        if (!m.paths().isEmpty()) {
            rows.add(() -> section(Messages.tr("openapi.endpoints")));
            for (OpenApiModel.PathItem p : m.paths()) {
                for (OpenApiModel.Operation op : p.operations()) {
                    rows.add(() -> operationNode(p.path(), op));
                }
            }
        }

        if (!m.schemas().isEmpty()) {
            rows.add(() -> section(Messages.tr("openapi.schemas")));
            for (OpenApiModel.Schema s : m.schemas()) {
                rows.add(() -> schemaNode(s));
            }
        }
        return rows;
    }

    private static Node operationNode(String path, OpenApiModel.Operation op) {
        VBox box = new VBox();
        box.getStyleClass().add("openapi-op");

        Label badge = new Label(op.method());
        badge.getStyleClass()
                .addAll("openapi-method", "openapi-method-" + op.method().toLowerCase(Locale.ROOT));
        Label pathLabel = new Label(path);
        pathLabel.getStyleClass().add("openapi-path");
        HBox head = new HBox(8, badge, pathLabel);
        head.setAlignment(Pos.CENTER_LEFT);
        if (op.deprecated()) {
            Label dep = new Label(Messages.tr("openapi.deprecated"));
            dep.getStyleClass().add("openapi-deprecated");
            head.getChildren().add(dep);
        }
        box.getChildren().add(head);
        if (op.summary() != null && !op.summary().isBlank()) {
            box.getChildren().add(wrapped(op.summary(), "openapi-summary"));
        }

        if (!op.parameters().isEmpty()) {
            box.getChildren().add(subhead(Messages.tr("openapi.parameters")));
            for (OpenApiModel.Param p : op.parameters()) {
                StringBuilder sb = new StringBuilder(p.name());
                if (p.in() != null) {
                    sb.append(" (").append(p.in()).append(')');
                }
                if (p.type() != null && !p.type().isBlank()) {
                    sb.append(" : ").append(p.type());
                }
                if (p.required()) {
                    sb.append("  ").append(Messages.tr("openapi.required"));
                }
                box.getChildren().add(detail(sb.toString()));
            }
        }
        if (!op.responses().isEmpty()) {
            box.getChildren().add(subhead(Messages.tr("openapi.responses")));
            for (OpenApiModel.Response r : op.responses()) {
                String d = r.description() == null || r.description().isBlank() ? "" : " — " + r.description();
                box.getChildren().add(detail(r.code() + d));
            }
        }
        return box;
    }

    private static Node schemaNode(OpenApiModel.Schema s) {
        VBox box = new VBox();
        box.getStyleClass().add("openapi-schema");
        String type = s.type() == null || s.type().isBlank() ? "" : "  : " + s.type();
        Label name = new Label(s.name() + type);
        name.getStyleClass().add("openapi-schema-name");
        box.getChildren().add(name);
        for (OpenApiModel.Property p : s.properties()) {
            String req = p.required() ? "  " + Messages.tr("openapi.required") : "";
            box.getChildren().add(detail(p.name() + " : " + p.type() + req));
        }
        return box;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("openapi-section");
        return l;
    }

    private static Label subhead(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("openapi-subhead");
        return l;
    }

    private static Label detail(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("openapi-detail");
        l.setWrapText(true);
        return l;
    }

    private static Label wrapped(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }
}
