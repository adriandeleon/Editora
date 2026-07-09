package com.editora.markwhen;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializes a parsed {@link Timeline} to a clean, stable JSON document (pretty-printed) for the
 * "Export to JSON" action — the structure the app parsed, not a reproduction of {@code @markwhen/parser}'s
 * internal schema. Pure + unit-tested; never throws (a serialization failure returns {@code "{}"}).
 *
 * <p>Shape: {@code {title, tagColors:[{name,color}], settings:{}, nodes:[…]}} where each node is either
 * {@code {type:"group", name, section, line, children:[…]}} or {@code {type:"event", label, tags:[],
 * line, start, startGranularity, end|null, endGranularity|null}} (dates as ISO {@code yyyy-MM-dd} strings).
 */
public final class MarkwhenJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MarkwhenJson() {}

    /** The timeline as pretty-printed JSON (or {@code "{}"} if serialization somehow fails). */
    public static String toJson(Timeline t) {
        ObjectNode root = MAPPER.createObjectNode();
        if (t.title() == null) {
            root.putNull("title");
        } else {
            root.put("title", t.title());
        }
        ArrayNode colors = root.putArray("tagColors");
        for (Timeline.TagColor c : t.tagColors()) {
            ObjectNode o = colors.addObject();
            o.put("name", c.name());
            o.put("color", c.color());
        }
        ObjectNode settings = root.putObject("settings");
        t.settings().forEach(settings::put);
        root.set("nodes", nodesArray(t.nodes()));
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private static ArrayNode nodesArray(List<MwNode> nodes) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (MwNode n : nodes) {
            if (n instanceof MwNode.Group g) {
                ObjectNode o = arr.addObject();
                o.put("type", "group");
                o.put("name", g.name());
                o.put("section", g.isSection());
                o.put("line", g.line());
                o.set("children", nodesArray(g.children()));
            } else if (n instanceof MwNode.Event e) {
                ObjectNode o = arr.addObject();
                o.put("type", "event");
                o.put("label", e.label());
                o.put("line", e.line());
                o.put("start", e.start().start().toString());
                o.put("startGranularity", e.start().granularity().name());
                if (e.end() == null) {
                    o.putNull("end");
                    o.putNull("endGranularity");
                } else {
                    o.put("end", e.end().start().toString());
                    o.put("endGranularity", e.end().granularity().name());
                }
                ArrayNode tags = o.putArray("tags");
                e.tags().forEach(tags::add);
            }
        }
        return arr;
    }
}
