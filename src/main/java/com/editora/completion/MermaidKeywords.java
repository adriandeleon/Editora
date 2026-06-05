package com.editora.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Static Mermaid keyword/identifier source for autocomplete in {@code .mmd} buffers: diagram types,
 * structural keywords, and directions. Pure; {@link #startingWith} is unit-tested.
 */
public final class MermaidKeywords {

    // Kept sorted (case-insensitive) for stable, alphabetical completion order.
    private static final List<String> KEYWORDS = List.of(
            "activate", "actor", "alt", "and", "autonumber",
            "class", "classDef", "classDiagram", "click",
            "deactivate", "direction",
            "else", "end", "erDiagram",
            "flowchart",
            "gantt", "gitGraph", "graph",
            "journey",
            "link", "loop",
            "mindmap",
            "note",
            "opt", "over",
            "par", "participant", "pie",
            "quadrantChart",
            "requirementDiagram",
            "section", "sequenceDiagram", "state", "stateDiagram", "stateDiagram-v2", "style", "subgraph",
            "timeline", "title",
            // Directions
            "BT", "LR", "RL", "TB", "TD");

    private MermaidKeywords() {
    }

    /** Keywords starting with {@code prefix} (case-insensitive), excluding an exact match, capped at {@code max}. */
    public static List<String> startingWith(String prefix, int max) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String k : KEYWORDS) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.startsWith(p) && !lower.equals(p)) {
                out.add(k);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }
}
