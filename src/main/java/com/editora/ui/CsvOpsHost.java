package com.editora.ui;

import com.editora.editor.EditorBuffer;

/**
 * What {@link CsvCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class CsvOpsHost implements CsvCoordinator.Ops {

    private final MainController mc;

    CsvOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void jumpTo(int line, int col) {
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            return;
        }
        var area = b.getArea();
        int paragraphs = area.getParagraphs().size();
        int p = Math.max(0, Math.min(line, paragraphs - 1));
        int c = Math.max(0, Math.min(col, area.getParagraphLength(p)));
        area.moveTo(p, c);
        area.requestFollowCaret();
        area.requestFocus();
    }

    @Override
    public void exportPdf(String csvText, String baseName) {
        mc.exports.csvExportPdf(csvText, baseName);
    }

    @Override
    public void printCsv(String csvText) {
        mc.exports.csvPrint(csvText);
    }

    @Override
    public void exportSpreadsheet(String csvText, String baseName, boolean xlsx) {
        mc.exports.csvExportSpreadsheet(csvText, baseName, xlsx);
    }
}
