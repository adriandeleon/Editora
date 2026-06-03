package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LanguageRegistry;

import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Tool window content showing live information about the active editor: file metadata,
 * text settings, counts, caret position, and the character under the caret.
 */
public class FileInformationPanel extends VBox implements ToolWindowContent {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM uuuu 'at' h:mm a", Locale.getDefault());
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}_]*");

    private final TextField createdValue = value();
    private final TextField modifiedValue = value();
    private final TextField sizeValue = value();
    private final TextField tagsValue = value();
    private final TextField permissionsValue = value();
    private final TextField ownerValue = value();
    private final TextField fullPathValue = value();

    private final TextField encodingValue = value();
    private final TextField lineEndingsValue = value();
    private final TextField modeValue = value();

    private final TextField linesValue = value();
    private final TextField charsValue = value();
    private final TextField wordsValue = value();
    private final TextField locationValue = value();
    private final TextField lineValue = value();
    private final TextField columnValue = value();

    private final TextField codePointValue = value();
    private final TextField charNameValue = value();
    private final TextField charBlockValue = value();
    private final TextField charCategoryValue = value();

    private EditorBuffer attached;
    private final ChangeListener<Number> caretListener = (o, w, n) -> refresh();
    private final ChangeListener<String> textListener = (o, w, n) -> refresh();

    public FileInformationPanel() {
        getStyleClass().add("file-info-panel");
        setSpacing(6);
        buildUI();
        refresh();
    }

    @Override
    public void focusFirstItem() {
        // No navigable list here — just move focus into the panel (it's read-only info).
        setFocusTraversable(true);
        requestFocus();
    }

    public void attach(EditorBuffer buffer) {
        if (attached != null) {
            attached.getArea().caretPositionProperty().removeListener(caretListener);
            attached.getArea().textProperty().removeListener(textListener);
        }
        attached = buffer;
        if (buffer != null) {
            buffer.getArea().caretPositionProperty().addListener(caretListener);
            buffer.getArea().textProperty().addListener(textListener);
        }
        refresh();
    }

    // --- UI construction ---

    private void buildUI() {
        Region fileView = buildFileView();
        getChildren().add(fileView);
        VBox.setVgrow(fileView, Priority.ALWAYS);
    }

    private Region buildFileView() {
        VBox box = new VBox(10,
                section(tr("fileinfo.section.file"), buildFileGrid()),
                section(tr("fileinfo.section.textSettings"), buildTextSettingsGrid()),
                section(tr("fileinfo.section.count"), buildCountBox()),
                section(tr("fileinfo.section.character"), buildCharacterGrid()));
        box.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("file-info-scroll");
        return scroll;
    }

    private TitledPane section(String title, Region content) {
        TitledPane pane = new TitledPane(title, content);
        pane.getStyleClass().add("file-info-section");
        pane.setExpanded(true);
        return pane;
    }

    private GridPane buildFileGrid() {
        GridPane g = grid();
        int r = 0;
        addRow(g, r++, tr("fileinfo.row.created"), createdValue);
        addRow(g, r++, tr("fileinfo.row.modified"), modifiedValue);
        addRow(g, r++, tr("fileinfo.row.size"), sizeValue);
        addRow(g, r++, tr("fileinfo.row.tags"), tagsValue);
        addRow(g, r++, tr("fileinfo.row.permissions"), permissionsValue);
        addRow(g, r++, tr("fileinfo.row.owner"), ownerValue);
        addRow(g, r, tr("fileinfo.row.fullPath"), fullPathValue);
        return g;
    }

    private GridPane buildTextSettingsGrid() {
        GridPane g = grid();
        addRow(g, 0, tr("fileinfo.row.encoding"), encodingValue);
        addRow(g, 1, tr("fileinfo.row.lineEndings"), lineEndingsValue);
        addRow(g, 2, tr("fileinfo.row.mode"), modeValue);
        return g;
    }

    private VBox buildCountBox() {
        GridPane totals = grid();
        addRow(totals, 0, tr("fileinfo.row.lines"), linesValue);
        addRow(totals, 1, tr("fileinfo.row.characters"), charsValue);
        addRow(totals, 2, tr("fileinfo.row.words"), wordsValue);

        GridPane caret = grid();
        addRow(caret, 0, tr("fileinfo.row.location"), locationValue);
        addRow(caret, 1, tr("fileinfo.row.line"), lineValue);
        addRow(caret, 2, tr("fileinfo.row.column"), columnValue);

        return new VBox(10, totals, caret);
    }

    private GridPane buildCharacterGrid() {
        GridPane g = grid();
        addRow(g, 0, tr("fileinfo.row.codePoint"), codePointValue);
        addRow(g, 1, tr("fileinfo.row.name"), charNameValue);
        addRow(g, 2, tr("fileinfo.row.block"), charBlockValue);
        addRow(g, 3, tr("fileinfo.row.category"), charCategoryValue);
        return g;
    }

    private GridPane grid() {
        GridPane g = new GridPane();
        g.getStyleClass().add("file-info-grid");
        g.setHgap(12);
        g.setVgap(6);
        GridPane.setHgrow(new Region(), Priority.ALWAYS);
        return g;
    }

    private void addRow(GridPane g, int row, String key, TextField valueField) {
        Label k = new Label(key);
        k.getStyleClass().add("file-info-key");
        GridPane.setHgrow(valueField, Priority.ALWAYS);
        GridPane.setHalignment(valueField, HPos.RIGHT);
        g.add(k, 0, row);
        g.add(valueField, 1, row);
    }

    /**
     * A value field rendered as a read-only, borderless text field so values can be selected and
     * copied. {@code minWidth = 0} lets it shrink, so a long value (e.g. the full path) can't force
     * the grid wider than the tool window and trigger scrollbars / truncate the key labels.
     */
    private TextField value() {
        TextField f = new TextField("–");
        f.setEditable(false);
        f.getStyleClass().add("file-info-value");
        f.setAlignment(Pos.CENTER_RIGHT);
        f.setMaxWidth(Double.MAX_VALUE);
        f.setMinWidth(0);
        f.setPrefColumnCount(0);
        return f;
    }

    // --- Live refresh ---

    private void refresh() {
        if (attached == null) {
            clearAll();
            return;
        }
        refreshFile(attached.getPath());
        refreshTextSettings(attached);
        refreshCounts(attached);
        refreshCharacter(attached);
    }

    private void clearAll() {
        for (TextField l : new TextField[]{
                createdValue, modifiedValue, sizeValue, tagsValue,
                permissionsValue, ownerValue, fullPathValue,
                encodingValue, lineEndingsValue, modeValue,
                linesValue, charsValue, wordsValue,
                locationValue, lineValue, columnValue,
                codePointValue, charNameValue, charBlockValue, charCategoryValue
        }) {
            l.setText("–");
        }
    }

    private void refreshFile(Path path) {
        tagsValue.setText("–");
        if (path == null) {
            createdValue.setText("–");
            modifiedValue.setText("–");
            sizeValue.setText("–");
            permissionsValue.setText("–");
            ownerValue.setText("–");
            fullPathValue.setText("(untitled)");
            return;
        }
        fullPathValue.setText(path.toString());
        try {
            BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class);
            createdValue.setText(formatTime(basic.creationTime().toInstant()));
            modifiedValue.setText(formatTime(basic.lastModifiedTime().toInstant()));
            sizeValue.setText(formatSize(basic.size()));
        } catch (IOException e) {
            createdValue.setText("–");
            modifiedValue.setText("–");
            sizeValue.setText("–");
        }
        try {
            PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class);
            permissionsValue.setText(formatPermissions(posix.permissions()));
            ownerValue.setText(posix.owner().getName());
        } catch (IOException | UnsupportedOperationException e) {
            permissionsValue.setText("–");
            ownerValue.setText("–");
        }
    }

    private void refreshTextSettings(EditorBuffer buffer) {
        encodingValue.setText(tr("fileinfo.encoding.utf8"));
        lineEndingsValue.setText(EditorBuffer.detectLineEnding(buffer.getArea().getText()));
        modeValue.setText(modeLabel(buffer));
    }

    private static String modeLabel(EditorBuffer buffer) {
        String lang = buffer.getLanguage();
        if (lang == null || lang.isEmpty() || lang.equals(LanguageRegistry.plaintext())) {
            return tr("fileinfo.mode.general");
        }
        return lang.substring(0, 1).toUpperCase(Locale.ROOT) + lang.substring(1);
    }

    private void refreshCounts(EditorBuffer buffer) {
        var area = buffer.getArea();
        String text = area.getText();
        int lineCount = area.getParagraphs().size();
        int charCount = text.length();
        int wordCount = countWords(text);

        int selStartPara = area.getParagraphs().size() == 0 ? 0 : 0;
        int selectedLines;
        int selectedChars = area.getSelection().getLength();
        if (selectedChars > 0) {
            String selText = area.getSelectedText();
            selectedLines = (int) selText.lines().count();
            if (selText.endsWith("\n")) {
                selectedLines = Math.max(1, selectedLines);
            }
        } else {
            selectedLines = 0;
        }

        linesValue.setText(selectedLines > 0 ? formatNum(lineCount) + " (" + selectedLines + ")"
                : formatNum(lineCount));
        charsValue.setText(selectedChars > 0 ? formatNum(charCount) + " (" + selectedChars + ")"
                : formatNum(charCount));
        wordsValue.setText(formatNum(wordCount));

        locationValue.setText(formatNum(area.getCaretPosition()));
        lineValue.setText(formatNum(area.getCurrentParagraph() + 1));
        columnValue.setText(formatNum(area.getCaretColumn() + 1));
        // selStartPara unused — placeholder for future enhancement.
        if (selStartPara < 0) {
            // no-op
        }
    }

    private void refreshCharacter(EditorBuffer buffer) {
        var area = buffer.getArea();
        String text = area.getText();
        int caret = area.getCaretPosition();
        if (caret < 0 || caret >= text.length()) {
            codePointValue.setText("–");
            charNameValue.setText("–");
            charBlockValue.setText("–");
            charCategoryValue.setText("–");
            return;
        }
        int codePoint = text.codePointAt(caret);
        codePointValue.setText(String.format("U+%04X", codePoint));
        String name = Character.getName(codePoint);
        charNameValue.setText(name == null ? "–" : name);
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        charBlockValue.setText(block == null ? "–" : friendlyBlockName(block));
        charCategoryValue.setText(categoryName(Character.getType(codePoint)));
    }

    // --- Formatting helpers ---

    private static String formatTime(java.time.Instant instant) {
        return DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%,d kB (%,d bytes)", bytes / 1024, bytes);
        }
        return String.format("%.1f MB (%,d bytes)", bytes / (1024.0 * 1024.0), bytes);
    }

    private static String formatNum(long n) {
        return String.format("%,d", n);
    }

    private static String formatPermissions(Set<PosixFilePermission> perms) {
        int octal = 0;
        if (perms.contains(PosixFilePermission.OWNER_READ))     octal |= 0400;
        if (perms.contains(PosixFilePermission.OWNER_WRITE))    octal |= 0200;
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE))  octal |= 0100;
        if (perms.contains(PosixFilePermission.GROUP_READ))     octal |= 040;
        if (perms.contains(PosixFilePermission.GROUP_WRITE))    octal |= 020;
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE))  octal |= 010;
        if (perms.contains(PosixFilePermission.OTHERS_READ))    octal |= 04;
        if (perms.contains(PosixFilePermission.OTHERS_WRITE))   octal |= 02;
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) octal |= 01;
        return String.format("%o (-%s)", octal, PosixFilePermissions.toString(perms));
    }

    private static int countWords(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        var matcher = WORD.matcher(text);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private static String friendlyBlockName(Character.UnicodeBlock block) {
        String raw = block.toString();
        StringBuilder out = new StringBuilder(raw.length());
        boolean nextUpper = true;
        for (char c : raw.toCharArray()) {
            if (c == '_') {
                out.append(' ');
                nextUpper = true;
            } else if (nextUpper) {
                out.append(c);
                nextUpper = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static String categoryName(int type) {
        return switch (type) {
            case Character.UPPERCASE_LETTER -> tr("char.cat.Lu");
            case Character.LOWERCASE_LETTER -> tr("char.cat.Ll");
            case Character.TITLECASE_LETTER -> tr("char.cat.Lt");
            case Character.MODIFIER_LETTER -> tr("char.cat.Lm");
            case Character.OTHER_LETTER -> tr("char.cat.Lo");
            case Character.NON_SPACING_MARK -> tr("char.cat.Mn");
            case Character.COMBINING_SPACING_MARK -> tr("char.cat.Mc");
            case Character.ENCLOSING_MARK -> tr("char.cat.Me");
            case Character.DECIMAL_DIGIT_NUMBER -> tr("char.cat.Nd");
            case Character.LETTER_NUMBER -> tr("char.cat.Nl");
            case Character.OTHER_NUMBER -> tr("char.cat.No");
            case Character.SPACE_SEPARATOR -> tr("char.cat.Zs");
            case Character.LINE_SEPARATOR -> tr("char.cat.Zl");
            case Character.PARAGRAPH_SEPARATOR -> tr("char.cat.Zp");
            case Character.CONTROL -> tr("char.cat.Cc");
            case Character.FORMAT -> tr("char.cat.Cf");
            case Character.SURROGATE -> tr("char.cat.Cs");
            case Character.PRIVATE_USE -> tr("char.cat.Co");
            case Character.UNASSIGNED -> tr("char.cat.Cn");
            case Character.DASH_PUNCTUATION -> tr("char.cat.Pd");
            case Character.START_PUNCTUATION -> tr("char.cat.Ps");
            case Character.END_PUNCTUATION -> tr("char.cat.Pe");
            case Character.CONNECTOR_PUNCTUATION -> tr("char.cat.Pc");
            case Character.OTHER_PUNCTUATION -> tr("char.cat.Po");
            case Character.INITIAL_QUOTE_PUNCTUATION -> tr("char.cat.Pi");
            case Character.FINAL_QUOTE_PUNCTUATION -> tr("char.cat.Pf");
            case Character.MATH_SYMBOL -> tr("char.cat.Sm");
            case Character.CURRENCY_SYMBOL -> tr("char.cat.Sc");
            case Character.MODIFIER_SYMBOL -> tr("char.cat.Sk");
            case Character.OTHER_SYMBOL -> tr("char.cat.So");
            default -> tr("char.cat.unknown");
        };
    }
}
