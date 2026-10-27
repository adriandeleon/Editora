package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.structured.DtdDeclarations;
import com.editora.structured.DtdDeclarations.Decl;
import com.editora.text.FoldRegions.Region;

/**
 * The Structure window's outline of a DTD without a language server (XML review 2 C11): a row per element, with
 * its attributes under it, per entity ({@code %name;} for a parameter entity, {@code &name;} for a general one)
 * and per notation, from {@link DtdDeclarations}. Pure; any thread.
 */
final class DtdOutline {

    private DtdOutline() {}

    static List<StructureNode> build(String text) {
        List<StructureNode> out = new ArrayList<>();
        for (Decl d : DtdDeclarations.parse(text)) {
            StructureNode node = node(d);
            for (Decl attribute : d.attributes()) {
                node.children().add(node(attribute));
            }
            out.add(node);
        }
        return out;
    }

    private static StructureNode node(Decl d) {
        Region region = new Region(d.line(), d.endLine());
        return switch (d.kind()) {
            case ELEMENT, ATTLIST -> new StructureNode(region, d.name(), "tag", d.line());
            case ATTRIBUTE -> new StructureNode(region, d.name(), "property", d.line());
            case ENTITY -> new StructureNode(region, "&" + d.name() + ";", "constant", d.line());
            case PARAMETER_ENTITY -> new StructureNode(region, "%" + d.name() + ";", "constant", d.line());
            case NOTATION -> new StructureNode(region, d.name(), "type", d.line());
        };
    }
}
