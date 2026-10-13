package com.editora.office;

import java.math.BigInteger;

import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

/**
 * The parts of a {@code .docx} that a bare {@code new XWPFDocument()} does not have and that make the file a
 * document rather than formatted text: the <b>styles</b> part with {@code Normal} and {@code Heading1}–
 * {@code Heading6} (each with its outline level — what Word's navigation pane, "Insert Table of Contents" and
 * LibreOffice's Navigator read), and the <b>numbering</b> part with one bulleted and one numbered list
 * definition, so a list is a list (Enter continues it, the numbers renumber) instead of paragraphs that
 * start with the text "1. ".
 */
final class DocxScaffold {

    /** Heading font sizes in points, by level (1–6). */
    static final int[] HEADING_POINTS = {22, 18, 15, 13, 12, 12};

    /** Deepest list level a definition describes (Word's own limit is 9 levels). */
    static final int MAX_LEVEL = 8;

    private static final String[] BULLETS = {"•", "◦", "▪"};

    private final XWPFNumbering numbering;
    private final BigInteger bulletAbstract;
    private final BigInteger decimalAbstract;
    private BigInteger bulletNum;

    DocxScaffold(XWPFDocument doc) {
        installStyles(doc.createStyles());
        numbering = doc.createNumbering();
        bulletAbstract = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum(0, false), numbering));
        decimalAbstract = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum(1, true), numbering));
    }

    /** The one bulleted list instance (bullets have no counter to restart). */
    BigInteger bulletList() {
        if (bulletNum == null) {
            bulletNum = numbering.addNum(bulletAbstract);
        }
        return bulletNum;
    }

    /**
     * A new numbered list instance whose {@code level} starts counting at {@code start}. Every ordered list
     * of the document gets its own: instances of one definition otherwise continue each other's count, so
     * a second list would carry on from the first.
     */
    BigInteger numberedList(int level, int start) {
        BigInteger id = numbering.addNum(decimalAbstract);
        CTNumLvl override = numbering.getNum(id).getCTNum().addNewLvlOverride();
        override.setIlvl(BigInteger.valueOf(level));
        override.addNewStartOverride().setVal(BigInteger.valueOf(Math.max(0, start)));
        return id;
    }

    private static CTAbstractNum abstractNum(int id, boolean ordered) {
        CTAbstractNum an = CTAbstractNum.Factory.newInstance();
        an.setAbstractNumId(BigInteger.valueOf(id));
        for (int level = 0; level <= MAX_LEVEL; level++) {
            CTLvl lvl = an.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(level));
            lvl.addNewStart().setVal(BigInteger.ONE);
            lvl.addNewNumFmt().setVal(ordered ? STNumberFormat.DECIMAL : STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal(ordered ? "%" + (level + 1) + "." : BULLETS[level % BULLETS.length]);
            lvl.addNewLvlJc().setVal(STJc.LEFT);
            CTInd ind = lvl.addNewPPr().addNewInd();
            ind.setLeft(BigInteger.valueOf(720L * (level + 1)));
            ind.setHanging(BigInteger.valueOf(360));
        }
        return an;
    }

    private static void installStyles(XWPFStyles styles) {
        CTStyle normal = CTStyle.Factory.newInstance();
        normal.setStyleId("Normal");
        normal.setType(STStyleType.PARAGRAPH);
        normal.setDefault(true);
        normal.addNewName().setVal("Normal");
        normal.addNewQFormat();
        styles.addStyle(new XWPFStyle(normal));
        for (int level = 1; level <= 6; level++) {
            CTStyle h = CTStyle.Factory.newInstance();
            h.setStyleId("Heading" + level);
            h.setType(STStyleType.PARAGRAPH);
            h.addNewName().setVal("heading " + level); // Word recognises its built-in headings by this name
            h.addNewBasedOn().setVal("Normal");
            h.addNewNext().setVal("Normal");
            h.addNewUiPriority().setVal(BigInteger.valueOf(9));
            h.addNewQFormat();
            CTPPrGeneral ppr = h.addNewPPr();
            ppr.addNewKeepNext();
            ppr.addNewSpacing().setBefore(BigInteger.valueOf(level <= 2 ? 240 : 160));
            ppr.addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1L));
            CTRPr rpr = h.addNewRPr();
            rpr.addNewB();
            rpr.addNewSz().setVal(BigInteger.valueOf(HEADING_POINTS[level - 1] * 2L));
            styles.addStyle(new XWPFStyle(h));
        }
    }
}
