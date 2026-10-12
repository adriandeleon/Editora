package com.editora.office;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * Reads a written {@code .docx} the way Word shows it: a list paragraph's text does not contain its number
 * or bullet — the numbering part supplies it — so these put it back for a test to read.
 */
final class DocxLists {

    private DocxLists() {}

    /** Every body paragraph's text, a list paragraph led by the {@code "• "} or {@code "3. "} Word draws. */
    static List<String> shown(XWPFDocument doc) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> counters = new HashMap<>();
        for (XWPFParagraph p : doc.getParagraphs()) {
            out.add(marker(p, counters) + p.getText());
        }
        return out;
    }

    /** All of {@link #shown}, one paragraph per line. */
    static String text(XWPFDocument doc) {
        return String.join("\n", shown(doc)) + "\n";
    }

    private static String marker(XWPFParagraph p, Map<String, Integer> counters) {
        if (p.getNumID() == null) {
            return "";
        }
        if (!"decimal".equals(p.getNumFmt())) {
            return "• ";
        }
        BigInteger start = p.getNumStartOverride();
        int first = start == null ? 1 : start.intValue();
        int n = counters.merge(p.getNumID() + "/" + p.getNumIlvl(), first, (old, unused) -> old + 1);
        return n + ". ";
    }
}
